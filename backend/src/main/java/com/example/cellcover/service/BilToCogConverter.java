package com.example.cellcover.service;

import org.geotools.api.parameter.GeneralParameterValue;
import org.geotools.api.parameter.ParameterValue;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.io.imageio.GeoToolsWriteParams;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Converts .bil Float32 raster files to Cloud-Optimized GeoTIFF (tiled, EPSG:4326).
 *
 * Uses GDAL COG driver (-of COG) which writes overviews BEFORE full-res data in the file.
 * This is the correct COG layout: overview IFDs have lower byte offsets than the full-res IFD,
 * so GDAL/GeoServer can locate and read the correct overview level with minimal HTTP range
 * requests when the file is accessed via /vsis3/ (MinIO).
 *
 * Overview levels are computed automatically by the COG driver (OVERVIEWS=AUTO):
 *   - Each level halves the resolution until the image fits in one 256×256 block.
 *   - Resampling: AVERAGE (matches the previous gdaladdo approach).
 *
 * GeoServer picks the appropriate overview level automatically based on the
 * requested pixel size vs the overview resolution.
 */
@Component
public class BilToCogConverter {

    private static final Logger log = LoggerFactory.getLogger(BilToCogConverter.class);

    private final PixelMaxSignalService pixelMaxSignalService;

    public BilToCogConverter(PixelMaxSignalService pixelMaxSignalService) {
        this.pixelMaxSignalService = pixelMaxSignalService;
    }

    static {
        System.setProperty("org.geotools.referencing.forceXY", "true");
    }

    /** Float32 NoData value written to COG (matches GDAL convention for Float32). */
    private static final float NODATA = -3.4028235E38f;

    // -------------------------------------------------------------------------

    public void convert(String cellId, Path cellDir, Map<String, Double> hdr,
                        String epsgCode, Path cogDir) throws Exception {

        Path bil = cellDir.resolve(cellId + ".bil");
        if (!Files.exists(bil)) return;

        Path binOut  = cogDir.resolve("binary").resolve(cellId + "_svr.tif");
        Path contOut = cogDir.resolve("continuous").resolve(cellId + ".tif");

        if (Files.exists(binOut)) {
            log.info("Binary COG already exists, skipping: {}", binOut.getFileName());
        } else {
            log.info("Converting {} → binary/{}_svr.tif", cellId, cellId);
            convertFile(bil, hdr, epsgCode, binOut);
        }

        if (Files.exists(contOut)) {
            log.info("Continuous COG already exists, skipping: {}", contOut.getFileName());
        } else {
            log.info("Converting {} → continuous/{}.tif", cellId, cellId);
            convertFileContinuous(bil, hdr, epsgCode, contOut);
            // Invalidate cached geo-transform so the new file is re-read on next tile request
            pixelMaxSignalService.invalidateMeta(cellId);
        }
    }

    // -------------------------------------------------------------------------

    private void convertFile(Path bilFile, Map<String, Double> hdr,
                             String epsgCode, Path outputFile) throws Exception {
        int ncols = hdr.get("ncols").intValue();
        int nrows = hdr.get("nrows").intValue();

        // 1. Read raw Float32 pixels
        float[] pixels = readRawPixels(bilFile, (long) ncols * nrows);

        // 2. Create a single-band Float32 BufferedImage
        BufferedImage image = buildFloat32Image(pixels, ncols, nrows);

        // 3. Compute native bbox (outer edges, not pixel centres)
        double ulxmap = hdr.get("ulxmap");
        double ulymap = hdr.get("ulymap");
        double xdim   = hdr.get("xdim");
        double ydim   = hdr.get("ydim");
        double nativeMinX = ulxmap - xdim / 2.0;
        double nativeMaxY = ulymap + ydim / 2.0;
        double nativeMaxX = nativeMinX + ncols * xdim;
        double nativeMinY = nativeMaxY - nrows * ydim;

        // 4. Create GridCoverage2D in native CRS and write to temp UTM GeoTIFF
        Files.createDirectories(outputFile.getParent());
        Path tempUtm = outputFile.resolveSibling(outputFile.getFileName() + ".utm.tif");
        try {
            CoordinateReferenceSystem nativeCrs = CRS.decode(epsgCode, true);
            ReferencedEnvelope nativeEnv = new ReferencedEnvelope(
                    nativeMinX, nativeMaxX, nativeMinY, nativeMaxY, nativeCrs);
            GridCoverageFactory factory = new GridCoverageFactory();
            GridCoverage2D coverage = factory.create(bilFile.getFileName().toString(), image, nativeEnv);

            GeoTiffWriteParams writeParams = new GeoTiffWriteParams();
            writeParams.setCompressionMode(GeoTiffWriteParams.MODE_EXPLICIT);
            writeParams.setCompressionType("Deflate");
            ParameterValue<GeoToolsWriteParams> wp = GeoTiffFormat.GEOTOOLS_WRITE_PARAMS.createValue();
            wp.setValue(writeParams);

            GeoTiffWriter tempWriter = new GeoTiffWriter(tempUtm.toFile());
            tempWriter.write(coverage, new GeneralParameterValue[]{wp});
            tempWriter.dispose();
            coverage.dispose(true);

            // 5. Reproject to EPSG:4326 and write true COG (overviews-first layout).
            //    -srcnodata/-dstnodata tells gdalwarp to treat NODATA as transparent, not interpolate it.
            runGdalwarp(tempUtm, outputFile, epsgCode);
        } finally {
            Files.deleteIfExists(tempUtm);
        }

        log.info("Wrote COG: {}", outputFile);
    }

    /**
     * Converts a BIL file to a continuous signal COG using a GDAL VRT → gdalwarp pipeline.
     * This bypasses GeoTools ColorModel normalization which zeroes-out negative float values.
     * Output is a true COG (-of COG): overview IFDs appear before full-res data in the file.
     */
    private void convertFileContinuous(Path bilFile, Map<String, Double> hdr,
                                        String epsgCode, Path outputFile) throws Exception {
        int ncols = hdr.get("ncols").intValue();
        int nrows = hdr.get("nrows").intValue();
        double ulxmap = hdr.get("ulxmap");
        double ulymap = hdr.get("ulymap");
        double xdim   = hdr.get("xdim");
        double ydim   = hdr.get("ydim");
        double minX   = ulxmap - xdim / 2.0;
        double maxY   = ulymap + ydim / 2.0;

        Files.createDirectories(outputFile.getParent());
        Path vrt = outputFile.resolveSibling(outputFile.getFileName() + ".vrt");
        try {
            // Build a VRT that interprets the BIL bytes as Float32 (GDAL reads it as UInt32 otherwise)
            String vrtXml = String.format(
                "<VRTDataset rasterXSize=\"%d\" rasterYSize=\"%d\">\n" +
                "  <SRS dataAxisToSRSAxisMapping=\"1,2\">%s</SRS>\n" +
                "  <GeoTransform>%.6f, %.6f, 0.0, %.6f, 0.0, -%.6f</GeoTransform>\n" +
                "  <VRTRasterBand dataType=\"Float32\" band=\"1\" subClass=\"VRTRawRasterBand\">\n" +
                "    <SourceFilename relativeToVRT=\"0\">%s</SourceFilename>\n" +
                "    <ByteOrder>LSB</ByteOrder>\n" +
                "    <ImageOffset>0</ImageOffset>\n" +
                "    <PixelOffset>4</PixelOffset>\n" +
                "    <LineOffset>%d</LineOffset>\n" +
                "  </VRTRasterBand>\n" +
                "</VRTDataset>",
                ncols, nrows, epsgCode,
                minX, xdim, maxY, ydim,
                bilFile.toAbsolutePath(),
                ncols * 4);
            Files.writeString(vrt, vrtXml);
            runGdalwarpVrt(vrt, outputFile);
        } finally {
            Files.deleteIfExists(vrt);
        }

        log.info("Wrote continuous COG: {}", outputFile);
    }

    private void runGdalwarpVrt(Path srcVrt, Path dstTif) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "gdalwarp",
                "-t_srs", "EPSG:4326",
                "-tr", "0.000036", "0.000036",   // ~4 m/pixel at VN latitude
                "-r", "bilinear",
                "-ot", "Float32",
                "-dstnodata", String.valueOf(NODATA),
                "-of", "COG",
                "-co", "BLOCKSIZE=256",
                "-co", "COMPRESS=DEFLATE",
                "-co", "OVERVIEW_RESAMPLING=AVERAGE",
                srcVrt.toAbsolutePath().toString(),
                dstTif.toAbsolutePath().toString()
        );
        pb.environment().put("GDAL_VRT_RAWRASTERBAND_ALLOWED_SOURCE", "ALL");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new IOException("gdalwarp (VRT) failed for " + srcVrt.getFileName() + ": " + output.trim());
        }
        log.info("gdalwarp VRT completed for: {}", dstTif.getFileName());
    }

    private void runGdalwarp(Path srcTif, Path dstTif, String srcEpsg) throws Exception {
        String nodataStr = String.valueOf(NODATA);
        ProcessBuilder pb = new ProcessBuilder(
                "gdalwarp",
                "-s_srs", srcEpsg,
                "-t_srs", "EPSG:4326",
                "-tr", "0.000036", "0.000036",   // ~4 m/pixel at VN latitude
                "-r", "bilinear",
                "-srcnodata", nodataStr,
                "-dstnodata", nodataStr,
                "-of", "COG",
                "-co", "BLOCKSIZE=256",
                "-co", "COMPRESS=DEFLATE",
                "-co", "OVERVIEW_RESAMPLING=AVERAGE",
                srcTif.toAbsolutePath().toString(),
                dstTif.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new IOException("gdalwarp failed for " + srcTif.getFileName() + ": " + output.trim());
        }
        log.info("gdalwarp completed for: {}", dstTif.getFileName());
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private BufferedImage buildFloat32Image(float[] pixels, int w, int h) {
        DataBufferFloat db  = new DataBufferFloat(pixels, pixels.length);
        ComponentSampleModel sm = new ComponentSampleModel(
                DataBuffer.TYPE_FLOAT, w, h, 1, w, new int[]{0});
        WritableRaster raster = Raster.createWritableRaster(sm, db, null);
        ColorSpace   cs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
        ColorModel   cm = new ComponentColorModel(
                cs, false, false, Transparency.OPAQUE, DataBuffer.TYPE_FLOAT);
        return new BufferedImage(cm, raster, false, null);
    }

    private float[] readRawPixels(Path bilFile, long totalPixels) throws IOException {
        float[] pixels     = new float[(int) totalPixels];
        int     bufferSize = Math.min((int) totalPixels, 1 << 20);
        ByteBuffer buf     = ByteBuffer.allocate(bufferSize * Float.BYTES)
                                       .order(ByteOrder.LITTLE_ENDIAN);

        try (FileChannel fc = FileChannel.open(bilFile, StandardOpenOption.READ)) {
            int  offset    = 0;
            long remaining = totalPixels;
            while (remaining > 0) {
                int toRead = (int) Math.min(remaining, bufferSize);
                buf.clear().limit(toRead * Float.BYTES);
                int bytesRead = fc.read(buf);
                if (bytesRead <= 0) break;
                buf.flip();
                int floatsRead = bytesRead / Float.BYTES;
                buf.asFloatBuffer().get(pixels, offset, floatsRead);
                offset    += floatsRead;
                remaining -= floatsRead;
            }
        }
        return pixels;
    }
}

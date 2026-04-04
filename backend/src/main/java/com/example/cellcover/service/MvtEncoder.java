package com.example.cellcover.service;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal Mapbox Vector Tile (MVT spec 2.1) encoder.
 *
 * Encodes polygon features into a single-layer MVT protobuf.
 * Implemented directly on protobuf-java to avoid the JTS version conflict
 * in the {@code java-vector-tile} library (which calls the removed
 * {@code Polygon.getExteriorRing():LineString} descriptor).
 *
 * Protobuf field layout:
 *   Tile  { repeated Layer layers = 3 }
 *   Layer { uint32 version=15; string name=1; Feature[] features=2;
 *           string[] keys=3; Value[] values=4; uint32 extent=5 }
 *   Feature { uint32[] tags=2[packed]; GeomType type=3; uint32[] geometry=4[packed] }
 *   Value { string string_value=1; float float_value=2; double double_value=3;
 *           int64 int_value=4; uint64 uint_value=5; sint64 sint_value=6; bool bool_value=7 }
 *   GeomType { UNKNOWN=0, POINT=1, LINESTRING=2, POLYGON=3 }
 */
public class MvtEncoder {

    private static final int EXTENT = 4096;

    // GeomType enum values
    private static final int GEOM_POLYGON = 3;

    // MVT command IDs
    private static final int CMD_MOVE_TO    = 1;
    private static final int CMD_LINE_TO    = 2;
    private static final int CMD_CLOSE_PATH = 7;

    /**
     * A polygon feature to encode.
     *
     * @param properties  Key-value attributes (String, Integer, Long, Float, Double, Boolean)
     * @param ring        Closed pixel-space ring: [[x0,y0],[x1,y1],...,[x0,y0]]
     *                    Coordinates must be in tile pixel space [0, extent].
     */
    public record Feature(Map<String, Object> properties, int[][] ring) {}

    // Internal wrapper for value deduplication
    private record Val(Object v) {
        @Override public boolean equals(Object o) {
            if (!(o instanceof Val other)) return false;
            return Objects.equals(v, other.v);
        }
        @Override public int hashCode() { return Objects.hashCode(v); }
    }

    /**
     * Encode a list of polygon features into an MVT byte array with a single layer.
     *
     * @param layerName  Name of the MVT layer (e.g. "coverage")
     * @param features   Features to encode; empty list produces a valid empty tile
     * @return MVT bytes (Content-Type: application/vnd.mapbox-vector-tile)
     */
    public static byte[] encode(String layerName, List<Feature> features) throws IOException {
        byte[] layerBytes = encodeLayer(layerName, features);

        ByteArrayOutputStream tileBuf = new ByteArrayOutputStream(layerBytes.length + 8);
        CodedOutputStream tile = CodedOutputStream.newInstance(tileBuf);

        // Tile field 3: layers (length-delimited message)
        tile.writeTag(3, WireFormat.WIRETYPE_LENGTH_DELIMITED);
        tile.writeUInt32NoTag(layerBytes.length);
        tile.writeRawBytes(layerBytes);
        tile.flush();

        return tileBuf.toByteArray();
    }

    // ── Layer ────────────────────────────────────────────────────────────────

    private static byte[] encodeLayer(String name, List<Feature> features) throws IOException {
        // Build global key / value index tables from all features
        List<String> keys   = new ArrayList<>();
        List<Val>    vals   = new ArrayList<>();
        for (Feature f : features) {
            for (Map.Entry<String, Object> e : f.properties().entrySet()) {
                if (!keys.contains(e.getKey()))      keys.add(e.getKey());
                Val ve = new Val(e.getValue());
                if (!vals.contains(ve))              vals.add(ve);
            }
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        CodedOutputStream out = CodedOutputStream.newInstance(buf);

        // version = field 15 (uint32)
        out.writeUInt32(15, 2);

        // name = field 1 (string)
        out.writeString(1, name);

        // extent = field 5 (uint32)
        out.writeUInt32(5, EXTENT);

        // keys = field 3 (repeated string)
        for (String k : keys) {
            out.writeString(3, k);
        }

        // values = field 4 (repeated embedded message)
        for (Val ve : vals) {
            byte[] vb = encodeValue(ve.v);
            out.writeTag(4, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            out.writeUInt32NoTag(vb.length);
            out.writeRawBytes(vb);
        }

        // features = field 2 (repeated embedded message)
        for (Feature f : features) {
            byte[] fb = encodeFeature(f, keys, vals);
            out.writeTag(2, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            out.writeUInt32NoTag(fb.length);
            out.writeRawBytes(fb);
        }

        out.flush();
        return buf.toByteArray();
    }

    // ── Feature ───────────────────────────────────────────────────────────────

    private static byte[] encodeFeature(Feature f,
                                         List<String> keys,
                                         List<Val> vals) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        CodedOutputStream out = CodedOutputStream.newInstance(buf);

        // type = field 3 (enum = varint)
        out.writeEnum(3, GEOM_POLYGON);

        // tags = field 2 (packed uint32)
        byte[] tagBytes = encodeTags(f.properties(), keys, vals);
        if (tagBytes.length > 0) {
            out.writeTag(2, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            out.writeUInt32NoTag(tagBytes.length);
            out.writeRawBytes(tagBytes);
        }

        // geometry = field 4 (packed uint32)
        byte[] geomBytes = encodeRing(f.ring());
        out.writeTag(4, WireFormat.WIRETYPE_LENGTH_DELIMITED);
        out.writeUInt32NoTag(geomBytes.length);
        out.writeRawBytes(geomBytes);

        out.flush();
        return buf.toByteArray();
    }

    // ── Geometry encoding ─────────────────────────────────────────────────────

    /**
     * Encode a closed ring as MVT geometry commands.
     * Input is a closed ring: last point == first point.
     * We emit: MoveTo(1 pt) + LineTo(N-1 pts) + ClosePath
     */
    private static byte[] encodeRing(int[][] ring) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(ring.length * 4 + 8);
        CodedOutputStream out = CodedOutputStream.newInstance(buf);

        if (ring.length < 2) {
            out.flush();
            return buf.toByteArray();
        }

        int numLinePoints = ring.length - 1; // last point is the close, not repeated as LineTo

        // MoveTo first point
        out.writeUInt32NoTag(cmdInteger(CMD_MOVE_TO, 1));
        out.writeUInt32NoTag(zigzag(ring[0][0]));
        out.writeUInt32NoTag(zigzag(ring[0][1]));

        // LineTo remaining points (ring[1] … ring[numLinePoints-1])
        // ring[numLinePoints] is the close point (same as ring[0]) — encode as ClosePath
        int lineCount = numLinePoints - 1; // points from ring[1] to ring[numLinePoints-1]
        if (lineCount > 0) {
            out.writeUInt32NoTag(cmdInteger(CMD_LINE_TO, lineCount));
            int curX = ring[0][0], curY = ring[0][1];
            for (int i = 1; i < numLinePoints; i++) {
                int dx = ring[i][0] - curX;
                int dy = ring[i][1] - curY;
                out.writeUInt32NoTag(zigzag(dx));
                out.writeUInt32NoTag(zigzag(dy));
                curX = ring[i][0];
                curY = ring[i][1];
            }
        }

        // ClosePath
        out.writeUInt32NoTag(cmdInteger(CMD_CLOSE_PATH, 1));

        out.flush();
        return buf.toByteArray();
    }

    // ── Tags (packed uint32) ──────────────────────────────────────────────────

    private static byte[] encodeTags(Map<String, Object> props,
                                      List<String> keys,
                                      List<Val> vals) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(props.size() * 4);
        CodedOutputStream out = CodedOutputStream.newInstance(buf);
        for (Map.Entry<String, Object> e : props.entrySet()) {
            out.writeUInt32NoTag(keys.indexOf(e.getKey()));
            out.writeUInt32NoTag(vals.indexOf(new Val(e.getValue())));
        }
        out.flush();
        return buf.toByteArray();
    }

    // ── Value message ─────────────────────────────────────────────────────────

    private static byte[] encodeValue(Object v) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(16);
        CodedOutputStream out = CodedOutputStream.newInstance(buf);
        if (v instanceof String s)        out.writeString(1, s);
        else if (v instanceof Float fl)   out.writeFloat(2, fl);
        else if (v instanceof Double d)   out.writeDouble(3, d);
        else if (v instanceof Integer i)  out.writeSInt64(6, i);
        else if (v instanceof Long l)     out.writeSInt64(6, l);
        else if (v instanceof Boolean b)  out.writeBool(7, b);
        out.flush();
        return buf.toByteArray();
    }

    // ── MVT helpers ───────────────────────────────────────────────────────────

    /** Pack command ID and count into a single uint32. */
    private static int cmdInteger(int id, int count) {
        return (id & 0x7) | (count << 3);
    }

    /** Zigzag encode a signed integer to unsigned (for delta-encoding coordinates). */
    private static int zigzag(int n) {
        return (n << 1) ^ (n >> 31);
    }
}

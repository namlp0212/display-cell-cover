package com.example.cellcover.controller;

import com.example.cellcover.dto.UploadInitRequest;
import com.example.cellcover.entity.ImportJob;
import com.example.cellcover.entity.SourceFileMapping;
import com.example.cellcover.entity.SourceRasterFile;
import com.example.cellcover.entity.UploadSession;
import com.example.cellcover.repository.ImportJobErrorRepository;
import com.example.cellcover.repository.SourceFileMappingRepository;
import com.example.cellcover.repository.SourceRasterFileRepository;
import com.example.cellcover.service.SourceRasterService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * CN-04.01: Source raster file upload and management endpoints.
 *
 * POST /api/source-raster/upload/single            → single BIL/HDR upload
 * POST /api/source-raster/upload/init              → init chunked upload session
 * PUT  /api/source-raster/upload/{id}/chunk/{idx}  → upload a chunk
 * GET  /api/source-raster/upload/{id}/status       → session status
 * POST /api/source-raster/upload/{id}/complete     → merge & trigger import
 * GET  /api/source-raster/files?page=0             → list source files
 * DELETE /api/source-raster/files/{id}             → delete source file record
 * POST /api/source-raster/mappings                 → create manual filename→cellId mapping
 * GET  /api/source-raster/mappings                 → list mappings
 */
@RestController
@RequestMapping("/api/source-raster")
public class SourceRasterController {

    private final SourceRasterService sourceRasterService;
    private final SourceRasterFileRepository sourceFileRepo;
    private final SourceFileMappingRepository mappingRepo;
    private final ImportJobErrorRepository importErrorRepo;

    public SourceRasterController(SourceRasterService sourceRasterService,
                                  SourceRasterFileRepository sourceFileRepo,
                                  SourceFileMappingRepository mappingRepo,
                                  ImportJobErrorRepository importErrorRepo) {
        this.sourceRasterService = sourceRasterService;
        this.sourceFileRepo      = sourceFileRepo;
        this.mappingRepo         = mappingRepo;
        this.importErrorRepo     = importErrorRepo;
    }

    // ── Single file upload ────────────────────────────────────────────────────

    @PostMapping("/upload/single")
    public ResponseEntity<?> uploadSingle(
            @RequestParam("bil") MultipartFile bil,
            @RequestParam("hdr") MultipartFile hdr,
            @RequestParam("cellId") String cellId,
            @RequestParam(value = "uploadedBy", required = false, defaultValue = "system") String uploadedBy) {
        try {
            SourceRasterFile rec = sourceRasterService.uploadSingle(bil, hdr, cellId, uploadedBy);
            return ResponseEntity.ok(Map.of(
                    "id",     rec.getId(),
                    "cellId", rec.getCellId(),
                    "status", "uploaded"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── Chunked upload ────────────────────────────────────────────────────────

    @PostMapping("/upload/init")
    public ResponseEntity<?> initUpload(
            @RequestBody UploadInitRequest req,
            @RequestHeader(value = "X-Created-By", required = false, defaultValue = "system") String createdBy) {
        UploadSession session = sourceRasterService.initUpload(req, createdBy);
        return ResponseEntity.ok(Map.of(
                "uploadId",    session.getUploadId(),
                "status",      session.getStatus(),
                "totalChunks", session.getTotalChunks()
        ));
    }

    @PutMapping("/upload/{uploadId}/chunk/{chunkIndex}")
    public ResponseEntity<?> uploadChunk(
            @PathVariable String uploadId,
            @PathVariable int chunkIndex,
            @RequestBody byte[] data) {
        try {
            UploadSession session = sourceRasterService.uploadChunk(uploadId, chunkIndex, data);
            return ResponseEntity.ok(Map.of(
                    "uploadId",        session.getUploadId(),
                    "chunkIndex",      chunkIndex,
                    "receivedChunks",  session.getReceivedChunks()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/upload/{uploadId}/status")
    public ResponseEntity<?> getUploadStatus(@PathVariable String uploadId) {
        try {
            UploadSession session = sourceRasterService.getUploadStatus(uploadId);
            return ResponseEntity.ok(session);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/upload/{uploadId}/complete")
    public ResponseEntity<?> completeUpload(@PathVariable String uploadId) {
        try {
            ImportJob job = sourceRasterService.completeUpload(uploadId);
            return ResponseEntity.accepted().body(Map.of(
                    "jobId",  job.getJobId(),
                    "status", job.getStatus()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Unmatched files for a job ─────────────────────────────────────────────

    @GetMapping("/jobs/{jobId}/unmatched")
    public ResponseEntity<?> getUnmatched(@PathVariable String jobId) {
        return ResponseEntity.ok(importErrorRepo.findByJobId(jobId).stream()
                .filter(e -> "NO_MAPPING".equals(e.getErrorType()))
                .toList());
    }

    // ── Source file management ────────────────────────────────────────────────

    @GetMapping("/files")
    public ResponseEntity<?> listFiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SourceRasterFile> result = sourceFileRepo.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "uploadedAt")));
        return ResponseEntity.ok(Map.of(
                "data",  result.getContent(),
                "total", result.getTotalElements(),
                "page",  page,
                "size",  size
        ));
    }

    @DeleteMapping("/files/{id}")
    public ResponseEntity<?> deleteFile(@PathVariable Long id) {
        if (!sourceFileRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        sourceFileRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Filename → cell_id mappings ───────────────────────────────────────────

    @PostMapping("/mappings")
    public ResponseEntity<?> createMapping(@RequestBody Map<String, String> body) {
        String stem   = body.get("filenameStem");
        String cellId = body.get("cellId");
        String by     = body.getOrDefault("createdBy", "system");
        if (stem == null || cellId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "filenameStem and cellId are required"));
        }
        SourceFileMapping mapping = mappingRepo.findByFilenameStem(stem)
                .orElseGet(SourceFileMapping::new);
        mapping.setFilenameStem(stem);
        mapping.setCellId(cellId);
        mapping.setCreatedBy(by);
        if (mapping.getCreatedAt() == null) mapping.setCreatedAt(LocalDateTime.now());
        mapping = mappingRepo.save(mapping);
        return ResponseEntity.ok(Map.of(
                "id",           mapping.getId(),
                "filenameStem", mapping.getFilenameStem(),
                "cellId",       mapping.getCellId()
        ));
    }

    @GetMapping("/mappings")
    public ResponseEntity<?> listMappings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(mappingRepo.findAll(PageRequest.of(page, size)));
    }
}

package com.example.cellcover.controller;

import com.example.cellcover.dto.ProcessingJobRequest;
import com.example.cellcover.entity.ProcessingJob;
import com.example.cellcover.entity.ProcessingJobError;
import com.example.cellcover.repository.ProcessingJobErrorRepository;
import com.example.cellcover.repository.ProcessingJobRepository;
import com.example.cellcover.service.ProcessingJobService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CN-04.03: Processing job management.
 *
 * POST /api/admin/processing-jobs         → trigger new job (202) or 409 if running
 * GET  /api/admin/processing-jobs/{jobId} → status + progress
 * GET  /api/admin/processing-jobs/{jobId}/errors → error list
 * GET  /api/admin/processing-jobs?page=0&size=20 → history
 */
@RestController
@RequestMapping("/api/admin/processing-jobs")
public class ProcessingJobController {

    private final ProcessingJobService processingJobService;
    private final ProcessingJobRepository jobRepo;
    private final ProcessingJobErrorRepository errorRepo;

    public ProcessingJobController(ProcessingJobService processingJobService,
                                   ProcessingJobRepository jobRepo,
                                   ProcessingJobErrorRepository errorRepo) {
        this.processingJobService = processingJobService;
        this.jobRepo   = jobRepo;
        this.errorRepo = errorRepo;
    }

    @PostMapping
    public ResponseEntity<?> triggerJob(
            @RequestBody ProcessingJobRequest req,
            @RequestHeader(value = "X-Triggered-By", required = false, defaultValue = "system") String triggeredBy) {
        if (processingJobService.isRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A processing job is already running"));
        }
        try {
            String jobId = processingJobService.triggerProcessing(req, triggeredBy);
            return ResponseEntity.accepted()
                    .body(Map.of("jobId", jobId, "status", "queued"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<?> getJob(@PathVariable String jobId) {
        return jobRepo.findByJobId(jobId)
                .map(ResponseEntity::ok)
                .<ResponseEntity<?>>map(r -> r)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{jobId}/errors")
    public ResponseEntity<?> getErrors(@PathVariable String jobId) {
        List<ProcessingJobError> errors = errorRepo.findByJobId(jobId);
        return ResponseEntity.ok(errors);
    }

    @GetMapping
    public ResponseEntity<?> listJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ProcessingJob> result = jobRepo.findAllByOrderByCreatedAtDesc(
                PageRequest.of(page, size));
        return ResponseEntity.ok(Map.of(
                "data",  result.getContent(),
                "total", result.getTotalElements(),
                "page",  page,
                "size",  size
        ));
    }
}

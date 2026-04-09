package com.example.cellcover.repository;

import com.example.cellcover.entity.ProcessingJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, Long> {

    Optional<ProcessingJob> findByJobId(String jobId);

    List<ProcessingJob> findByStatus(String status);

    Page<ProcessingJob> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

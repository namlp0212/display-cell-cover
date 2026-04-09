package com.example.cellcover.repository;

import com.example.cellcover.entity.ImportJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {

    Optional<ImportJob> findByJobId(String jobId);

    Page<ImportJob> findByStatus(String status, Pageable pageable);

    Page<ImportJob> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

package com.example.cellcover.repository;

import com.example.cellcover.entity.ProcessingJobError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProcessingJobErrorRepository extends JpaRepository<ProcessingJobError, Long> {

    List<ProcessingJobError> findByJobId(String jobId);
}

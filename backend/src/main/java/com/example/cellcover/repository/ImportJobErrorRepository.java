package com.example.cellcover.repository;

import com.example.cellcover.entity.ImportJobError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportJobErrorRepository extends JpaRepository<ImportJobError, Long> {

    List<ImportJobError> findByJobId(String jobId);
}

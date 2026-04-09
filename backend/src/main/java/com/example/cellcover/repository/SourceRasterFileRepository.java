package com.example.cellcover.repository;

import com.example.cellcover.entity.SourceRasterFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SourceRasterFileRepository extends JpaRepository<SourceRasterFile, Long> {

    List<SourceRasterFile> findByCellId(String cellId);

    Optional<SourceRasterFile> findByCellIdAndIsActive(String cellId, boolean isActive);

    Page<SourceRasterFile> findAll(Pageable pageable);
}

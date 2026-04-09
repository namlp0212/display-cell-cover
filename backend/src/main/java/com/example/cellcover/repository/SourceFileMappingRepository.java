package com.example.cellcover.repository;

import com.example.cellcover.entity.SourceFileMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SourceFileMappingRepository extends JpaRepository<SourceFileMapping, Long> {

    Optional<SourceFileMapping> findByFilenameStem(String filenameStem);
}

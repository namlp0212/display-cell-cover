package com.example.cellcover.repository;

import com.example.cellcover.entity.CatLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CatLocationRepository extends JpaRepository<CatLocation, Long> {

    Optional<CatLocation> findByLocationCode(String locationCode);
}

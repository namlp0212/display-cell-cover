package com.example.cellcover.repository;

import com.example.cellcover.entity.InfraStation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InfraStationRepository extends JpaRepository<InfraStation, Long> {

    List<InfraStation> findByDeptId(Long deptId);

    Optional<InfraStation> findByStationCode(String stationCode);
}

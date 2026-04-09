package com.example.cellcover.repository;

import com.example.cellcover.entity.InfraRaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InfraRasterRepository extends JpaRepository<InfraRaster, Long> {

    List<InfraRaster> findByStationId(Long stationId);

    List<InfraRaster> findByNetworkTypeAndRowStatus(String networkType, int rowStatus);

    Optional<InfraRaster> findByRasterCode(String rasterCode);
}

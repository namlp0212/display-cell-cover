package com.example.cellcover.repository;

import com.example.cellcover.entity.Simulation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SimulationRepository extends JpaRepository<Simulation, String> {

    List<Simulation> findByStatus(Simulation.SimulationStatus status);
}

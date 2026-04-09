package com.example.cellcover.repository;

import com.example.cellcover.entity.SimulationOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SimulationOverrideRepository extends JpaRepository<SimulationOverride, SimulationOverride.PK> {

    @Query("SELECT so.cellId FROM SimulationOverride so WHERE so.simulationId = :simId AND so.forcedStatus = false")
    List<String> findForcedOffCellIds(@Param("simId") String simId);

    /** Bulk DELETE — avoids the N-round-trip derived-query delete. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SimulationOverride so WHERE so.simulationId = :simId")
    void deleteBySimulationId(@Param("simId") String simId);
}

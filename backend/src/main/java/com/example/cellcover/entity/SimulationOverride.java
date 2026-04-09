package com.example.cellcover.entity;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "simulation_overrides")
@IdClass(SimulationOverride.PK.class)
public class SimulationOverride implements Persistable<SimulationOverride.PK> {

    @Id
    @Column(name = "simulation_id", length = 36)
    private String simulationId;

    @Id
    @Column(name = "cell_id", length = 50)
    private String cellId;

    @Column(name = "forced_status", nullable = false)
    private boolean forcedStatus = false;

    /** True for newly created instances so Hibernate calls persist() instead of merge().
     *  Without this, composite-PK entities trigger a SELECT before every INSERT. */
    @Transient
    private boolean isNew = true;

    @Override
    public PK getId() { return new PK(simulationId, cellId); }

    @Override
    public boolean isNew() { return isNew; }

    @PostPersist
    @PostLoad
    void markNotNew() { this.isNew = false; }

    public String getSimulationId() { return simulationId; }
    public void setSimulationId(String simulationId) { this.simulationId = simulationId; }

    public String getCellId() { return cellId; }
    public void setCellId(String cellId) { this.cellId = cellId; }

    public boolean isForcedStatus() { return forcedStatus; }
    public void setForcedStatus(boolean forcedStatus) { this.forcedStatus = forcedStatus; }

    public static class PK implements Serializable {
        private String simulationId;
        private String cellId;

        public PK() {}
        public PK(String simulationId, String cellId) {
            this.simulationId = simulationId;
            this.cellId = cellId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(simulationId, pk.simulationId) &&
                   Objects.equals(cellId, pk.cellId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(simulationId, cellId);
        }
    }
}

package com.example.cellcover.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "simulation_overrides")
@IdClass(SimulationOverride.PK.class)
public class SimulationOverride {

    @Id
    @Column(name = "simulation_id", length = 36)
    private String simulationId;

    @Id
    @Column(name = "cell_id", length = 50)
    private String cellId;

    @Column(name = "forced_status", nullable = false)
    private boolean forcedStatus = false;

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

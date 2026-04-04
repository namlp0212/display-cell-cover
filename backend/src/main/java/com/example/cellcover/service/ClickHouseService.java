package com.example.cellcover.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Executes raw queries against ClickHouse for H3 tile data.
 * ClickHouse is write-once (populated by H3IndexPipelineService) and never mutated at query time.
 */
@Service
public class ClickHouseService {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseService.class);

    private final DataSource clickHouseDataSource;

    public ClickHouseService(@Qualifier("clickHouseDataSource") DataSource clickHouseDataSource) {
        this.clickHouseDataSource = clickHouseDataSource;
    }

    /**
     * Result row from a tile query.
     */
    public record TileRow(long h3Index, int onDensity, float onSignal, int offDensity) {}

    /**
     * Query H3 hexagons that intersect the given tile cells at the given resolution,
     * filtering by on/off cell sets.
     *
     * Uses IN (exact h3_index list) instead of BETWEEN to avoid full-resolution
     * range scans — critical at res 12–13 where BETWEEN spans the entire dataset.
     *
     * @param h3Res      H3 resolution (5–13)
     * @param tileCells  Exact H3 index values covering the tile bbox (from polygonToCells)
     * @param onCells    Cell IDs currently ON
     * @param offCells   Cell IDs currently OFF
     * @return List of TileRow — one per H3 hexagon that has data
     */
    public List<TileRow> queryTile(int h3Res,
                                   List<Long> tileCells,
                                   Collection<String> onCells,
                                   Collection<String> offCells) {
        if (onCells.isEmpty() && offCells.isEmpty()) {
            return Collections.emptyList();
        }
        if (tileCells.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> allCells = new HashSet<>(onCells);
        allCells.addAll(offCells);

        String h3InList   = buildLongInList(tileCells);
        String cellInList = buildInList(allCells);
        String onList     = buildInList(onCells);

        // Single scan: exact h3_index IN list replaces BETWEEN
        String sql =
                "SELECT h3_index," +
                " countIf(cell_id IN (" + onList + ")) AS on_density," +
                " maxIf(max_signal, cell_id IN (" + onList + ")) AS on_signal," +
                " countIf(cell_id NOT IN (" + onList + ")) AS off_density" +
                " FROM cell_h3_coverage" +
                " WHERE h3_res = ?" +
                "   AND h3_index IN (" + h3InList + ")" +
                "   AND cell_id IN (" + cellInList + ")" +
                " GROUP BY h3_index";

        List<TileRow> rows = new ArrayList<>();
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, h3Res);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new TileRow(
                            rs.getLong("h3_index"),
                            rs.getInt("on_density"),
                            rs.getFloat("on_signal"),
                            rs.getInt("off_density")
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("ClickHouse tile query failed: {}", e.getMessage(), e);
        }
        return rows;
    }

    /**
     * Bulk-insert rows into cell_h3_coverage.
     * Called by H3IndexPipelineService.
     */
    public void insertBatch(List<Object[]> rows) throws SQLException {
        if (rows.isEmpty()) return;
        String sql = "INSERT INTO cell_h3_coverage (h3_res, h3_index, cell_id, max_signal) VALUES (?,?,?,?)";
        final int CHUNK = 500_000;
        try (Connection conn = clickHouseDataSource.getConnection()) {
            for (int start = 0; start < rows.size(); start += CHUNK) {
                List<Object[]> chunk = rows.subList(start, Math.min(start + CHUNK, rows.size()));
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (Object[] row : chunk) {
                        ps.setInt(1, (int) row[0]);
                        ps.setLong(2, (long) row[1]);
                        ps.setString(3, (String) row[2]);
                        ps.setFloat(4, (float) row[3]);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        }
    }

    /**
     * Delete all rows for a specific cell — called before re-importing.
     */
    public void deleteByCellId(String cellId) throws SQLException {
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "ALTER TABLE cell_h3_coverage DELETE WHERE cell_id = ?")) {
            ps.setString(1, cellId);
            ps.executeUpdate();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String buildInList(Collection<String> ids) {
        if (ids.isEmpty()) return "''";
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append('\'').append(id.replace("'", "\\'")).append('\'');
        }
        return sb.toString();
    }

    private String buildLongInList(Collection<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }
}

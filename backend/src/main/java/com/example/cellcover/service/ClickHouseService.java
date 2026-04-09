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
 *
 * Two query strategies:
 *
 *   queryTileReal()     — uses cell_state_real Dictionary; no large cell IN list.
 *                         SQL is ~2KB regardless of cell count. Suitable for production (500k cells).
 *
 *   queryTileDictSim()  — uses dict for base state + small delta IN lists for simulation overrides.
 *                         deltaOn/deltaOff contain only cells that differ from real state (typically
 *                         tens to hundreds), keeping SQL small.
 *
 *   queryTile()         — legacy: embeds full on/off cell sets as SQL IN lists.
 *                         Kept for backward-compatibility; do not use with large cell counts.
 */
@Service
public class ClickHouseService {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseService.class);

    private final DataSource clickHouseDataSource;

    public ClickHouseService(@Qualifier("clickHouseDataSource") DataSource clickHouseDataSource) {
        this.clickHouseDataSource = clickHouseDataSource;
    }

    /** Result row from a tile query. */
    public record TileRow(long h3Index, int onDensity, float onSignal, int offDensity) {}

    // ── Dict-based query: real state ─────────────────────────────────────────

    /**
     * Query H3 hexagons for a tile using the ClickHouse cell_state_real Dictionary.
     * No cell IN list in SQL — state is resolved per-row via dictGet.
     *
     * @param h3Res       H3 resolution (5–13)
     * @param tileCells   H3 index values covering the tile bbox (from polygonToCells)
     * @param networkMask Bitmask of requested network types (2G=1,3G=2,4G=4,5G=8; 0xFF=all)
     */
    public List<TileRow> queryTileReal(int h3Res, List<Long> tileCells, int networkMask) {
        if (tileCells.isEmpty()) return Collections.emptyList();

        String h3InList = buildLongInList(tileCells);
        String netCheck = networkMaskCheck(networkMask);

        String sql =
            "SELECT h3_index," +
            " countIf(" +
            "   dictGetOrDefault('cell_state_real','is_on',cell_id,toUInt8(255))=1" + netCheck +
            " ) AS on_density," +
            " maxIf(max_signal," +
            "   dictGetOrDefault('cell_state_real','is_on',cell_id,toUInt8(255))=1" + netCheck +
            " ) AS on_signal," +
            " countIf(" +
            "   dictGetOrDefault('cell_state_real','is_on',cell_id,toUInt8(255))=0" + netCheck +
            " ) AS off_density" +
            " FROM cell_h3_coverage" +
            " WHERE h3_res=?" +
            "   AND h3_index IN (" + h3InList + ")" +
            " GROUP BY h3_index" +
            " HAVING on_density>0 OR off_density>0";

        return executeQuery(sql, h3Res);
    }

    // ── Dict-based query: simulation delta ───────────────────────────────────

    /**
     * Query H3 hexagons for a simulation tile using the Dictionary for base state
     * plus small delta lists for simulation overrides.
     *
     * Base state comes from cell_state_real Dictionary (real/alert-synced state).
     * Only cells that differ between real and sim state appear in deltaOn / deltaOff.
     *
     * @param h3Res       H3 resolution (5–13)
     * @param tileCells   H3 index values covering the tile bbox
     * @param networkMask Bitmask of requested network types (0xFF=all)
     * @param deltaOn     Cells OFF in real but ON in sim (sim turns them on)
     * @param deltaOff    Cells ON in real but OFF in sim (sim turns them off)
     */
    public List<TileRow> queryTileDictSim(int h3Res,
                                          List<Long> tileCells,
                                          int networkMask,
                                          Collection<String> deltaOn,
                                          Collection<String> deltaOff) {
        if (tileCells.isEmpty()) return Collections.emptyList();
        if (deltaOn.isEmpty() && deltaOff.isEmpty()) {
            return queryTileReal(h3Res, tileCells, networkMask);
        }

        String h3InList    = buildLongInList(tileCells);
        String netCheck    = networkMaskCheck(networkMask);

        // Build on/off conditions — skip NOT IN / IN clauses when delta is empty
        String onBase  = "dictGetOrDefault('cell_state_real','is_on',cell_id,toUInt8(255))=1";
        String offBase = "dictGetOrDefault('cell_state_real','is_on',cell_id,toUInt8(255))=0";

        String onCondition;
        if (deltaOff.isEmpty()) {
            onCondition = onBase;
        } else {
            onCondition = "(" + onBase + " AND cell_id NOT IN (" + buildInList(deltaOff) + "))";
        }
        if (!deltaOn.isEmpty()) {
            onCondition = "(" + onCondition + " OR cell_id IN (" + buildInList(deltaOn) + "))";
        }

        String offCondition;
        if (deltaOn.isEmpty()) {
            offCondition = offBase;
        } else {
            offCondition = "(" + offBase + " AND cell_id NOT IN (" + buildInList(deltaOn) + "))";
        }
        if (!deltaOff.isEmpty()) {
            offCondition = "(" + offCondition + " OR cell_id IN (" + buildInList(deltaOff) + "))";
        }

        String sql =
            "SELECT h3_index," +
            " countIf(" + onCondition  + netCheck + ") AS on_density," +
            " maxIf(max_signal," + onCondition + netCheck + ") AS on_signal," +
            " countIf(" + offCondition + netCheck + ") AS off_density" +
            " FROM cell_h3_coverage" +
            " WHERE h3_res=?" +
            "   AND h3_index IN (" + h3InList + ")" +
            " GROUP BY h3_index" +
            " HAVING on_density>0 OR off_density>0";

        return executeQuery(sql, h3Res);
    }

    // ── Legacy query (full IN list) ──────────────────────────────────────────

    /**
     * @deprecated Use {@link #queryTileReal} or {@link #queryTileDictSim} instead.
     *             This method embeds all cell IDs as SQL IN lists and does not scale
     *             beyond ~50k cells.
     */
    @Deprecated
    public List<TileRow> queryTile(int h3Res,
                                   List<Long> tileCells,
                                   Collection<String> onCells,
                                   Collection<String> offCells) {
        if (onCells.isEmpty() && offCells.isEmpty()) return Collections.emptyList();
        if (tileCells.isEmpty()) return Collections.emptyList();

        Set<String> allCells = new HashSet<>(onCells);
        allCells.addAll(offCells);

        String h3InList   = buildLongInList(tileCells);
        String cellInList = buildInList(allCells);
        String onList     = buildInList(onCells);

        String sql =
            "SELECT h3_index," +
            " countIf(cell_id IN (" + onList + ")) AS on_density," +
            " maxIf(max_signal, cell_id IN (" + onList + ")) AS on_signal," +
            " countIf(cell_id NOT IN (" + onList + ")) AS off_density" +
            " FROM cell_h3_coverage" +
            " WHERE h3_res=?" +
            "   AND h3_index IN (" + h3InList + ")" +
            "   AND cell_id IN (" + cellInList + ")" +
            " GROUP BY h3_index";

        return executeQuery(sql, h3Res);
    }

    // ── Bulk insert / delete ─────────────────────────────────────────────────

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

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns the AND clause for network mask filtering, or empty string if all networks.
     * Appended directly inside countIf/maxIf conditions.
     */
    private static String networkMaskCheck(int networkMask) {
        if (networkMask == 0xFF) return "";  // all networks — skip bitAnd
        return " AND bitAnd(dictGetOrDefault('cell_state_real','network_mask',cell_id,toUInt8(0)),"
                + networkMask + ")>0";
    }

    private List<TileRow> executeQuery(String sql, int h3Res) {
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

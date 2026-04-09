package com.example.cellcover.controller;

import com.example.cellcover.repository.CellRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Internal endpoint consumed by ClickHouse to populate the cell_state_real dictionary.
 *
 * ClickHouse polls GET /internal/ch/cell-states/real every LIFETIME (30–60s).
 * Response is NDJSON (one JSON object per line) with fields:
 *   cell_id      String
 *   is_on        UInt8   (1=ON, 0=OFF)
 *   network_mask UInt8   (bitmask: 2G=1, 3G=2, 4G=4, 5G=8)
 *
 * NOTE: This endpoint is internal — restrict access via network policy in production.
 */
@RestController
@RequestMapping("/internal/ch")
public class ClickHouseDictController {

    private final CellRepository cellRepo;

    public ClickHouseDictController(CellRepository cellRepo) {
        this.cellRepo = cellRepo;
    }

    @GetMapping(value = "/cell-states/real", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> realCellStates() {
        StreamingResponseBody body = out -> {
            List<Object[]> rows = cellRepo.findAllCellsForDict();
            for (Object[] row : rows) {
                String cellId     = (String) row[0];
                int    isOn       = row[1] instanceof Boolean b ? (b ? 1 : 0) : ((Number) row[1]).intValue();
                String network    = (String) row[2];
                int    mask       = networkToMask(network);

                // Escape cell_id to guard against special chars (cell IDs are alphanumeric in practice)
                String escaped = cellId.replace("\\", "\\\\").replace("\"", "\\\"");
                String line = "{\"cell_id\":\"" + escaped + "\",\"is_on\":" + isOn
                        + ",\"network_mask\":" + mask + "}\n";
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(body);
    }

    /**
     * Convert network type string to bitmask.
     * 2G=1, 3G=2, 4G=4, 5G=8.
     * Unknown/null → 15 (all bits set, matches any network filter).
     */
    public static int networkToMask(String networkType) {
        if (networkType == null) return 15;
        return switch (networkType.toUpperCase()) {
            case "2G" -> 1;
            case "3G" -> 2;
            case "4G" -> 4;
            case "5G" -> 8;
            default   -> 15;
        };
    }
}

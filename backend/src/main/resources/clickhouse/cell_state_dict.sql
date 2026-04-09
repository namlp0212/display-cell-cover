-- ============================================================
-- ClickHouse Dictionary: cell_state_real
-- Run this once on the ClickHouse instance (not via Flyway).
--
-- The dictionary polls the backend's internal endpoint every
-- 30–60 seconds to refresh cell ON/OFF state and network mask.
-- ============================================================

-- Run against the 'cellcover' database (same DB as cell_h3_coverage table)
-- clickhouse-client --database=cellcover --query "$(cat cell_state_dict.sql)"
CREATE DICTIONARY IF NOT EXISTS cellcover.cell_state_real
(
    cell_id      String,
    is_on        UInt8,    -- 1=ON, 0=OFF
    network_mask UInt8     -- bitmask: 2G=1, 3G=2, 4G=4, 5G=8
)
PRIMARY KEY cell_id
SOURCE(HTTP(
    url    'http://localhost:8080/internal/ch/cell-states/real'
    format 'JSONEachRow'
))
LAYOUT(HASHED())
LIFETIME(MIN 30 MAX 60);

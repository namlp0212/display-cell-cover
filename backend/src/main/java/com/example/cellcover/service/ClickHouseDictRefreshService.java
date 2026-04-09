package com.example.cellcover.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Triggers an immediate reload of the ClickHouse cell_state_real dictionary
 * after a cell state change (alert sync), so tile queries reflect the new
 * state without waiting for the full LIFETIME cycle (30–60s).
 *
 * Called asynchronously — does not block the alert sync response.
 */
@Service
public class ClickHouseDictRefreshService {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseDictRefreshService.class);
    private static final String DICT_NAME = "cell_state_real";

    private final RestTemplate restTemplate;
    private final String chHttpUrl;
    private final String chUsername;
    private final String chPassword;

    public ClickHouseDictRefreshService(
            RestTemplate restTemplate,
            @Value("${clickhouse.http-url:http://localhost:8123}") String chHttpUrl,
            @Value("${clickhouse.username:default}") String chUsername,
            @Value("${clickhouse.password:}") String chPassword) {
        this.restTemplate = restTemplate;
        this.chHttpUrl    = chHttpUrl;
        this.chUsername   = chUsername;
        this.chPassword   = chPassword;
    }

    /**
     * Sends SYSTEM RELOAD DICTIONARY to ClickHouse via HTTP API.
     * Fire-and-forget: failures are logged but do not propagate.
     */
    @Async("jobExecutor")
    public void reloadCellStateDict() {
        try {
            String url = buildReloadUrl();
            restTemplate.postForEntity(url, null, String.class);
            log.info("ClickHouse dictionary '{}' reload triggered", DICT_NAME);
        } catch (Exception e) {
            // Non-fatal: dict will self-refresh within LIFETIME seconds
            log.warn("Failed to trigger ClickHouse dictionary reload: {}", e.getMessage());
        }
    }

    private String buildReloadUrl() {
        String query = "SYSTEM+RELOAD+DICTIONARY+" + DICT_NAME;
        if (chUsername != null && !chUsername.isBlank()) {
            return chHttpUrl + "/?query=" + query
                    + "&user=" + chUsername
                    + "&password=" + chPassword;
        }
        return chHttpUrl + "/?query=" + query;
    }
}

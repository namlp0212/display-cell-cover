package com.example.cellcover.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * RestTemplate-based stub implementation of NimsApiClient.
 *
 * NOTE: The actual NIMS API endpoint paths, authentication mechanism, and
 * response JSON structure are unknown and must be filled in by the integration team.
 * All TODO markers indicate places that need real implementation.
 */
@Component
public class NimsApiClientImpl implements NimsApiClient {

    private static final Logger log = LoggerFactory.getLogger(NimsApiClientImpl.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String credential;
    private final int pageSize;

    public NimsApiClientImpl(
            RestTemplate restTemplate,
            @Value("${nims.api.base-url:http://nims-api/api}") String baseUrl,
            @Value("${nims.api.credential:}") String credential,
            @Value("${nims.api.page-size:500}") int pageSize) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.credential = credential;
        this.pageSize = pageSize;
    }

    @Override
    public List<Map<String, Object>> fetchDepartments(String fromTime, String toTime, int page, int pageSize) {
        // TODO: Replace "/departments" with the actual NIMS API endpoint path.
        // TODO: Adjust query parameters (from, to, page, size) to match NIMS API spec.
        // TODO: Parse the actual response structure — may be wrapped in { data: [...], total: N }.
        return fetch("/departments", fromTime, toTime, page, pageSize);
    }

    @Override
    public List<Map<String, Object>> fetchLocations(String fromTime, String toTime, int page, int pageSize) {
        // TODO: Replace "/locations" with the actual NIMS API endpoint path.
        return fetch("/locations", fromTime, toTime, page, pageSize);
    }

    @Override
    public List<Map<String, Object>> fetchStations(String fromTime, String toTime, int page, int pageSize) {
        // TODO: Replace "/stations" with the actual NIMS API endpoint path.
        return fetch("/stations", fromTime, toTime, page, pageSize);
    }

    @Override
    public List<Map<String, Object>> fetchRasters(String fromTime, String toTime, int page, int pageSize) {
        // TODO: Replace "/rasters" with the actual NIMS API endpoint path.
        return fetch("/rasters", fromTime, toTime, page, pageSize);
    }

    @Override
    public List<Map<String, Object>> fetchCells(String fromTime, String toTime, int page, int pageSize) {
        // TODO: Replace "/cells" with the actual NIMS API endpoint path.
        return fetch("/cells", fromTime, toTime, page, pageSize);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetch(String path, String fromTime, String toTime, int page, int size) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromHttpUrl(baseUrl + path)
                    .queryParam("page", page)
                    .queryParam("size", size);
            if (fromTime != null) builder.queryParam("from", fromTime);
            if (toTime != null)   builder.queryParam("to",   toTime);

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            // TODO: Replace with actual NIMS authentication header (Bearer token, Basic auth, API key, etc.)
            if (credential != null && !credential.isBlank()) {
                headers.set("Authorization", "Bearer " + credential);
            }

            ResponseEntity<Object> response = restTemplate.exchange(
                    builder.toUriString(),
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Object.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object body = response.getBody();
                // TODO: Adapt to actual NIMS response envelope structure.
                // Case 1: Response is a direct array → [ {...}, {...} ]
                if (body instanceof List) {
                    return (List<Map<String, Object>>) body;
                }
                // Case 2: Response is an object with a "data" field → { data: [...], total: N }
                if (body instanceof Map) {
                    Object data = ((Map<?, ?>) body).get("data");
                    if (data instanceof List) {
                        return (List<Map<String, Object>>) data;
                    }
                }
            }
        } catch (Exception e) {
            log.error("NIMS API call failed [path={}, page={}]: {}", path, page, e.getMessage());
        }
        return Collections.emptyList();
    }
}

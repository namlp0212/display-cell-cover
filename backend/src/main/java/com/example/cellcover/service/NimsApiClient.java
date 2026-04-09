package com.example.cellcover.service;

import java.util.List;
import java.util.Map;

/**
 * Interface for fetching data from the NIMS (Network Information Management System) API.
 *
 * All methods return raw Map objects representing individual records.
 * TODO: Replace Map<String, Object> with typed DTOs once the actual NIMS API
 *       response format is confirmed.
 */
public interface NimsApiClient {

    /**
     * Fetch departments with optional time range filter.
     * @param fromTime ISO datetime string, e.g. "2024-01-01T00:00:00"
     * @param toTime   ISO datetime string
     * @param page     0-based page number
     * @param pageSize records per page
     * @return list of raw records (Map of field name → value)
     */
    List<Map<String, Object>> fetchDepartments(String fromTime, String toTime, int page, int pageSize);

    /**
     * Fetch locations/provinces/districts with optional time range filter.
     */
    List<Map<String, Object>> fetchLocations(String fromTime, String toTime, int page, int pageSize);

    /**
     * Fetch base stations with optional time range filter.
     */
    List<Map<String, Object>> fetchStations(String fromTime, String toTime, int page, int pageSize);

    /**
     * Fetch antenna/raster configurations (sector definitions) with optional time range filter.
     */
    List<Map<String, Object>> fetchRasters(String fromTime, String toTime, int page, int pageSize);

    /**
     * Fetch cell definitions with optional time range filter.
     */
    List<Map<String, Object>> fetchCells(String fromTime, String toTime, int page, int pageSize);
}

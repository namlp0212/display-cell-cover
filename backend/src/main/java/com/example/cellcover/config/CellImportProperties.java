package com.example.cellcover.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cellcover")
public class CellImportProperties {

    private String rasterDir      = "/Users/lenam/Data/cell-data";
    private String cogDir         = "data/cog";
    private int    parallelThreads = 4;
    private Geoserver geoserver   = new Geoserver();

    public String getRasterDir() { return rasterDir; }
    public void setRasterDir(String rasterDir) { this.rasterDir = rasterDir; }

    public String getCogDir() { return cogDir; }
    public void setCogDir(String cogDir) { this.cogDir = cogDir; }

    public int getParallelThreads() { return parallelThreads; }
    public void setParallelThreads(int parallelThreads) { this.parallelThreads = parallelThreads; }

    public Geoserver getGeoserver() { return geoserver; }
    public void setGeoserver(Geoserver geoserver) { this.geoserver = geoserver; }

    public static class Geoserver {
        private String url           = "http://localhost:8081/geoserver";
        private String username      = "admin";
        private String password      = "geoserver";
        private String workspace     = "cellcover";
        private int    overlapGroups = 8;

        public String getUrl()                           { return url; }
        public void setUrl(String url)                   { this.url = url; }

        public String getUsername()                      { return username; }
        public void setUsername(String username)         { this.username = username; }

        public String getPassword()                      { return password; }
        public void setPassword(String password)         { this.password = password; }

        public String getWorkspace()                     { return workspace; }
        public void setWorkspace(String workspace)       { this.workspace = workspace; }

        public int getOverlapGroups()                    { return overlapGroups; }
        public void setOverlapGroups(int overlapGroups)  { this.overlapGroups = overlapGroups; }
    }
}

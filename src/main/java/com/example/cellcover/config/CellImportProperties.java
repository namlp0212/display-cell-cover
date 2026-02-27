package com.example.cellcover.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cellcover")
public class CellImportProperties {

    private String rasterDir = "data/rasters";
    private Geoserver geoserver = new Geoserver();

    public String getRasterDir() {
        return rasterDir;
    }

    public void setRasterDir(String rasterDir) {
        this.rasterDir = rasterDir;
    }

    public Geoserver getGeoserver() {
        return geoserver;
    }

    public void setGeoserver(Geoserver geoserver) {
        this.geoserver = geoserver;
    }

    public static class Geoserver {
        private String containerName = "cellcover-geoserver";
        private String initScript = "/opt/geoserver-init.sh";

        public String getContainerName() {
            return containerName;
        }

        public void setContainerName(String containerName) {
            this.containerName = containerName;
        }

        public String getInitScript() {
            return initScript;
        }

        public void setInitScript(String initScript) {
            this.initScript = initScript;
        }
    }
}

package com.example.cellcover.dto;

public class UploadInitRequest {

    private String filename;
    private long fileSizeBytes;
    private int totalChunks;
    private int chunkSizeBytes;
    private String archiveType;
    private String networkType;

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public int getChunkSizeBytes() { return chunkSizeBytes; }
    public void setChunkSizeBytes(int chunkSizeBytes) { this.chunkSizeBytes = chunkSizeBytes; }

    public String getArchiveType() { return archiveType; }
    public void setArchiveType(String archiveType) { this.archiveType = archiveType; }

    public String getNetworkType() { return networkType; }
    public void setNetworkType(String networkType) { this.networkType = networkType; }
}

package com.lqx.ragmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.upload")
public class UploadProperties {

    /** 20MB, matches docs/02-api-contract.md section 1.2. */
    private long maxFileSizeBytes = 20L * 1024 * 1024;

    private int maxPdfPages = 500;

    private List<String> allowedExtensions = new ArrayList<>(List.of("pdf", "docx", "md", "markdown", "txt"));

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public int getMaxPdfPages() {
        return maxPdfPages;
    }

    public void setMaxPdfPages(int maxPdfPages) {
        this.maxPdfPages = maxPdfPages;
    }

    public List<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }
}

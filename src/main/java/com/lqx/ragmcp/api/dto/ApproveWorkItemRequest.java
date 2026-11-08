package com.lqx.ragmcp.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.List;

/**
 * POST /api/v1/work-items/{id}/approve request body (docs/02-api-contract.md 2.7).
 *
 * <p>The contract distinguishes an absent {@code allowedDocIds} field from an explicit JSON
 * {@code null}: when the WorkItem requested all documents, absence is a 400 and explicit null
 * means "all documents". The custom setter records presence; Jackson does not call it for an
 * absent field.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApproveWorkItemRequest {

    private Integer ttlSeconds;
    private List<String> allowedTools;
    private Integer maxTopK;
    private String reason;
    private List<String> allowedDocIds;
    private boolean allowedDocIdsProvided;

    public Integer getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(Integer ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools;
    }

    public Integer getMaxTopK() {
        return maxTopK;
    }

    public void setMaxTopK(Integer maxTopK) {
        this.maxTopK = maxTopK;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<String> getAllowedDocIds() {
        return allowedDocIds;
    }

    @JsonSetter("allowedDocIds")
    public void setAllowedDocIds(List<String> allowedDocIds) {
        this.allowedDocIds = allowedDocIds;
        this.allowedDocIdsProvided = true;
    }

    /** True when the request JSON contained the {@code allowedDocIds} key, even if its value was null. */
    @JsonIgnore
    public boolean isAllowedDocIdsProvided() {
        return allowedDocIdsProvided;
    }
}

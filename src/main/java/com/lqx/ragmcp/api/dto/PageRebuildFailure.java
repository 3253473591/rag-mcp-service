package com.lqx.ragmcp.api.dto;

/** A document whose page rebuild failed. The rest of the rebuild continues. */
public record PageRebuildFailure(String docId, String error, String message) {
}

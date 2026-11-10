package com.lqx.ragmcp.search;

import java.util.List;
import java.util.UUID;

/**
 * Shared SQL fragment builder for the document-level filters used by every retrieval route:
 * version mode / effective interval / explicit version / docIds.
 *
 * <p>Extracted from {@code SearchRepository} in Phase 10 so the page route applies exactly the
 * same filters in SQL. Keeping this in one place is the point: a filter added to one route and
 * forgotten in the other would silently change what the other route can return.</p>
 */
public final class DocumentFilterSql {

    private DocumentFilterSql() {
    }

    /**
     * Appends {@code AND ...} predicates over the {@code kb_document d} alias (and matching
     * arguments). The caller is responsible for the {@code FROM/JOIN kb_document d}.
     */
    public static void appendFilters(StringBuilder sql, List<Object> args, SearchQuery query) {
        switch (query.versionMode()) {
            case DEFAULT -> sql.append(" AND d.status = 'ACTIVE'");
            case HISTORICAL, INCLUDE_SUPERSEDED ->
                    sql.append(" AND d.status IN ('ACTIVE','SUPERSEDED')");
        }

        if (query.versionMode() == SearchVersionMode.INCLUDE_SUPERSEDED) {
            sql.append(" AND d.effective_from <= ?::date");
            args.add(query.asOf().toString());
        } else {
            sql.append(" AND d.effective_from <= ?::date")
                    .append(" AND (d.effective_to IS NULL OR ?::date <= d.effective_to)");
            args.add(query.asOf().toString());
            args.add(query.asOf().toString());
        }

        if (query.version() != null) {
            sql.append(" AND d.version = ?");
            args.add(query.version());
        }

        if (!query.docIds().isEmpty()) {
            sql.append(" AND d.id IN (");
            appendUuidPlaceholders(sql, args, query.docIds());
            sql.append(')');
        }
    }

    public static void appendUuidPlaceholders(StringBuilder sql, List<Object> args, List<UUID> ids) {
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?::uuid");
            args.add(ids.get(i).toString());
        }
    }
}

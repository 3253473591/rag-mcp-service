package com.lqx.ragmcp.search;

import java.time.LocalDate;

/**
 * Three filtering modes from docs/02-api-contract.md 2.9 and docs/03-data-model.md 5.4.
 *
 * <ul>
 *   <li>{@link #DEFAULT}: only ACTIVE documents whose effective interval contains asOf.</li>
 *   <li>{@link #HISTORICAL}: asOf is in the past; ACTIVE and SUPERSEDED, still honour
 *       effective_to because the historical point-in-time must contain asOf.</li>
 *   <li>{@link #INCLUDE_SUPERSEDED}: explicit opt-in; ACTIVE and SUPERSEDED, only require
 *       effective_from &lt;= asOf and deliberately ignore effective_to.</li>
 * </ul>
 *
 * <p>DRAFT is never included by any mode.</p>
 */
public enum SearchVersionMode {

    DEFAULT,
    HISTORICAL,
    INCLUDE_SUPERSEDED;

    public static SearchVersionMode forRequest(LocalDate asOf, boolean includeSuperseded, LocalDate today) {
        if (includeSuperseded) {
            return INCLUDE_SUPERSEDED;
        }
        return asOf.isBefore(today) ? HISTORICAL : DEFAULT;
    }
}

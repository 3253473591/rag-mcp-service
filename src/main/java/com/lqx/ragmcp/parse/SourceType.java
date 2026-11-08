package com.lqx.ragmcp.parse;

import java.util.Locale;
import java.util.Optional;

public enum SourceType {

    PDF("pdf"),
    DOCX("docx"),
    MD("md"),
    TXT("txt");

    private final String dbValue;

    SourceType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue.toUpperCase(Locale.ROOT);
    }

    public static Optional<SourceType> fromFilename(String filename) {
        if (filename == null) {
            return Optional.empty();
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return Optional.of(PDF);
        }
        if (lower.endsWith(".docx")) {
            return Optional.of(DOCX);
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return Optional.of(MD);
        }
        if (lower.endsWith(".txt")) {
            return Optional.of(TXT);
        }
        return Optional.empty();
    }
}

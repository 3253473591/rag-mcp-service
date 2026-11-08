package com.lqx.ragmcp.document;

import com.lqx.ragmcp.api.error.ApiException;

/**
 * B-06: use only the basename, never the client path; reject traversal attempts.
 */
public final class FileNames {

    private FileNames() {
    }

    public static String basename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw ApiException.validationField("file", "文件名为空");
        }
        String normalized = originalFilename.replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        String base = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        base = base.trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..") || base.contains("..")) {
            throw ApiException.validationField("file", "文件名包含非法路径片段");
        }
        return base;
    }

    public static String extension(String basename) {
        if (basename == null) {
            return "";
        }
        int dot = basename.lastIndexOf('.');
        if (dot < 0 || dot == basename.length() - 1) {
            return "";
        }
        return basename.substring(dot + 1).toLowerCase();
    }

    public static String titleFromFilename(String basename) {
        int dot = basename.lastIndexOf('.');
        return dot > 0 ? basename.substring(0, dot) : basename;
    }
}

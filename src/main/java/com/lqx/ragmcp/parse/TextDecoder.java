package com.lqx.ragmcp.parse;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * P-08: try strict UTF-8 first, then GB18030. Returns null when both fail.
 */
public final class TextDecoder {

    private static final Charset GB18030 = Charset.forName("GB18030");

    private TextDecoder() {
    }

    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        String utf8 = tryDecode(bytes, offset, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return stripBom(utf8);
        }
        String gb = tryDecode(bytes, offset, GB18030);
        return gb == null ? null : stripBom(gb);
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
    }

    private static String tryDecode(byte[] bytes, int offset, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
}

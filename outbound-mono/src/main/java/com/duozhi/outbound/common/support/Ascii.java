package com.duozhi.outbound.common.support;

/** Protocol identifiers use ASCII case rules; natural-language text is unchanged. */
public final class Ascii {
    private Ascii() {}

    public static String lower(String value) {
        return fold(value, false);
    }

    public static String upper(String value) {
        return fold(value, true);
    }

    private static String fold(String value, boolean upper) {
        char[] chars = value.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (upper && c >= 'a' && c <= 'z') chars[i] = (char) (c - 'a' + 'A');
            else if (!upper && c >= 'A' && c <= 'Z') chars[i] = (char) (c - 'A' + 'a');
        }
        return new String(chars);
    }
}

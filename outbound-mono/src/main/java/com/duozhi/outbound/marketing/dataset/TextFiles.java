package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.common.exception.ApiError;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Upload decoding (UTF-8 with or without BOM, else GB18030) and a strict RFC 4180 reader. */
final class TextFiles {
    private TextFiles() {}

    static final int MAX_RECORD_CHARS = 64 * 1024;

    record Decoded(String text, String encoding) {}

    static Decoded decode(byte[] content) {
        if (content.length >= 3
                && (content[0] & 0xff) == 0xEF
                && (content[1] & 0xff) == 0xBB
                && (content[2] & 0xff) == 0xBF)
            return new Decoded(
                    new String(content, 3, content.length - 3, StandardCharsets.UTF_8),
                    "UTF-8-BOM");
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(content))
                            .toString();
            return new Decoded(text, "UTF-8");
        } catch (CharacterCodingException notUtf8) {
            try {
                String text =
                        Charset.forName("GB18030")
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(content))
                                .toString();
                return new Decoded(text, "GB18030");
            } catch (CharacterCodingException invalid) {
                throw new ApiError(422, "文件编码无法识别，请使用 UTF-8 或 GB18030 文本");
            }
        }
    }

    /** One CSV record with its 1-based starting line. */
    record Record(int line, List<String> fields) {}

    static List<Record> csv(String text) {
        if (text.indexOf('\0') >= 0) throw new ApiError(422, "请上传文本格式的 CSV/TXT");
        List<Record> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, afterQuote = false;
        int line = 1, start = 1, recordChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (++recordChars > MAX_RECORD_CHARS)
                throw new ApiError(422, "第 " + start + " 行内容过长，请只保留号码及必要属性");
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        afterQuote = true;
                    }
                } else {
                    if (c == '\n') line++;
                    field.append(c);
                }
                continue;
            }
            if (c == '"' && field.isEmpty() && !afterQuote) {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                afterQuote = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                fields.add(field.toString());
                field.setLength(0);
                afterQuote = false;
                records.add(new Record(start, List.copyOf(fields)));
                fields.clear();
                line++;
                start = line;
                recordChars = 0;
            } else if (afterQuote) {
                throw new ApiError(422, "第 " + start + " 行 CSV 格式不正确，请检查引号");
            } else {
                field.append(c);
            }
        }
        if (quoted) throw new ApiError(422, "第 " + start + " 行 CSV 引号未闭合");
        if (!field.isEmpty() || !fields.isEmpty()) {
            fields.add(field.toString());
            records.add(new Record(start, List.copyOf(fields)));
        }
        records.removeIf(r -> r.fields().stream().allMatch(f -> f.isBlank()));
        return records;
    }

    /** Spreadsheet-safe text: strips the leading quote ant-platform adds to formula-like cells. */
    static String cell(List<String> fields, int index) {
        if (index < 0 || index >= fields.size()) return "";
        String value = fields.get(index).trim();
        return value.startsWith("'") ? value.substring(1).trim() : value;
    }
}

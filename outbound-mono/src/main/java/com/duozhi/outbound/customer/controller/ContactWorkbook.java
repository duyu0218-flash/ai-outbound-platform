package com.duozhi.outbound.customer.controller;

import com.duozhi.outbound.common.exception.ApiError;
import java.io.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;

/** Converts the published Excel template to the same validated CSV import path. */
public final class ContactWorkbook {
    private ContactWorkbook() {}

    public static String csv(byte[] bytes, int maxRows) throws IOException {
        // Bound inflated archive and converted text as well as compressed upload size.
        // SAX sheet parsing avoids materializing hundreds of thousands of rows.
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            long total = 0;
            int entries = 0;
            byte[] buffer = new byte[8192];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > 400) throw new ApiError(413, "Excel 压缩包包含过多文件");
                long part = 0;
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    part += n;
                    total += n;
                    if (total > 128L * 1024 * 1024
                            || part > 80L * 1024 * 1024
                            || entry.getName().contains("sharedStrings")
                                    && part > 32L * 1024 * 1024)
                        throw new ApiError(413, "Excel 解压内容过大，请拆分文件");
                }
            }
        }
        try (var pkg =
                org.apache.poi.openxml4j.opc.OPCPackage.open(new ByteArrayInputStream(bytes))) {
            var reader = new org.apache.poi.xssf.eventusermodel.XSSFReader(pkg);
            var strings = reader.getSharedStringsTable();
            var output =
                    new StringWriter() {
                        @Override
                        public void write(String str, int off, int len) {
                            if (getBuffer().length() + len > 20 * 1024 * 1024)
                                throw new ApiError(413, "转换后的名单超过20MB，请拆分文件");
                            super.write(str, off, len);
                        }

                        @Override
                        public void write(int c) {
                            if (getBuffer().length() >= 20 * 1024 * 1024)
                                throw new ApiError(413, "转换后的名单超过20MB，请拆分文件");
                            super.write(c);
                        }
                    };
            var csv = new CSVPrinter(output, CSVFormat.DEFAULT);
            var sheets =
                    (org.apache.poi.xssf.eventusermodel.XSSFReader.SheetIterator)
                            reader.getSheetsData();
            boolean parsed = false;
            while (sheets.hasNext()) {
                try (var input = sheets.next()) {
                    if (parsed
                            || !sheets.getSheetName().equals("外呼名单")
                                    && sheets.getSheetName().equals("填写说明")) continue;
                    var xml = org.apache.poi.util.XMLHelper.newXMLReader();
                    xml.setContentHandler(
                            new org.xml.sax.helpers.DefaultHandler() {
                                final List<String> fields = new ArrayList<>();
                                final StringBuilder value = new StringBuilder();
                                String type = "";
                                int column = 0, rows = 0;
                                boolean capture = false;

                                @Override
                                public void startElement(
                                        String uri,
                                        String local,
                                        String name,
                                        org.xml.sax.Attributes attrs) {
                                    if (name.equals("row")) {
                                        fields.clear();
                                        if (++rows > maxRows + 1
                                                || Integer.parseInt(
                                                                Objects.requireNonNullElse(
                                                                        attrs.getValue("r"), "1"))
                                                        > maxRows + 1)
                                            throw new ApiError(413, "表格超过允许的导入行数");
                                    }
                                    if (name.equals("c")) {
                                        String ref = attrs.getValue("r");
                                        column = 0;
                                        if (ref != null)
                                            for (char c : ref.toCharArray()) {
                                                if (c < 'A' || c > 'Z') break;
                                                column = column * 26 + c - 'A' + 1;
                                            }
                                        else column = fields.size() + 1;
                                        if (column > 64) throw new ApiError(413, "导入最多64列");
                                        type = Objects.toString(attrs.getValue("t"), "");
                                        value.setLength(0);
                                    }
                                    if (name.equals("f"))
                                        throw new ApiError(400, "第 " + rows + " 行含公式，请粘贴为值后上传");
                                    if (name.equals("v") || name.equals("t")) capture = true;
                                }

                                @Override
                                public void characters(char[] c, int start, int length) {
                                    if (capture) {
                                        if (value.length() + length > 20000)
                                            throw new ApiError(413, "单元格超过20000字符");
                                        value.append(c, start, length);
                                    }
                                }

                                @Override
                                public void endElement(String uri, String local, String name)
                                        throws org.xml.sax.SAXException {
                                    if (name.equals("v") || name.equals("t")) capture = false;
                                    if (name.equals("c")) {
                                        String text = value.toString();
                                        if (type.equals("s") && !text.isBlank())
                                            text =
                                                    strings.getItemAt(Integer.parseInt(text))
                                                            .getString();
                                        if (text.length() > 20000)
                                            throw new ApiError(413, "单元格超过20000字符");
                                        while (fields.size() < column) fields.add("");
                                        fields.set(column - 1, text);
                                    }
                                    if (name.equals("row"))
                                        try {
                                            csv.printRecord(fields);
                                        } catch (IOException e) {
                                            throw new org.xml.sax.SAXException(e);
                                        }
                                }
                            });
                    xml.parse(new org.xml.sax.InputSource(input));
                    parsed = true;
                }
            }
            if (!parsed) throw new ApiError(400, "Excel 没有名单页");
            csv.flush();
            return output.toString();
        } catch (ApiError e) {
            throw e;
        } catch (Exception e) {
            if (e.getCause() instanceof ApiError a) throw a;
            throw new ApiError(400, "Excel 文件无法读取，请使用未加密的 .xlsx 模板");
        }
    }

    public static String column(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "手机号", "手机号码", "号码", "手机号（必填）" -> "phone";
            case "姓名", "客户姓名" -> "name";
            case "标签" -> "tags";
            case "同意状态", "联系授权状态" -> "consent_state";
            case "禁止呼叫", "是否禁呼" -> "dnc";
            case "禁呼原因" -> "dnc_reason";
            case "时区" -> "timezone";
            default -> value;
        };
    }

    static String consent(String value) {
        return switch (value) {
            case "已同意" -> "consented";
            case "未同意" -> "not_consented";
            case "已撤回" -> "revoked";
            case "未知", "未确认" -> "unknown";
            default -> value;
        };
    }
}

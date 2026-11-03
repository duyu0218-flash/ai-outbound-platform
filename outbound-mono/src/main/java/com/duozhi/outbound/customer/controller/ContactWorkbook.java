package com.duozhi.outbound.customer.controller;

import com.duozhi.outbound.common.exception.ApiError;
import java.io.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Converts the published Excel template to the same validated CSV import path. */
public final class ContactWorkbook {
    private ContactWorkbook() {}

    public static String csv(byte[] bytes, int maxRows) throws IOException {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes));
                var output = new StringWriter();
                var csv = new CSVPrinter(output, CSVFormat.DEFAULT)) {
            Sheet sheet = workbook.getSheet("外呼名单");
            if (sheet == null) sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > maxRows) throw new ApiError(413, "表格超过允许的导入行数");
            var formatter = new DataFormatter(Locale.ROOT);
            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    csv.printRecord(Collections.nCopies(7, ""));
                    continue;
                }
                if (row.getLastCellNum() > 32) throw new ApiError(400, "导入列数超过限制，请使用平台模板");
                List<String> fields = new ArrayList<>();
                for (int n = 0; n < row.getLastCellNum(); n++) {
                    Cell cell = row.getCell(n);
                    if (cell != null && cell.getCellType() == CellType.FORMULA)
                        throw new ApiError(400, "第 " + (row.getRowNum() + 1) + " 行含公式，请粘贴为值后上传");
                    fields.add(
                            cell != null && cell.getCellType() == CellType.NUMERIC
                                    ? org.apache.poi.ss.util.NumberToTextConverter.toText(
                                            cell.getNumericCellValue())
                                    : formatter.formatCellValue(cell));
                }
                csv.printRecord(fields);
            }
            csv.flush();
            return output.toString();
        } catch (ApiError e) {
            throw e;
        } catch (Exception e) {
            throw new ApiError(400, "Excel 文件无法读取，请使用未加密的 .xlsx 模板");
        }
    }

    public static String column(String value) {
        return switch (value) {
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

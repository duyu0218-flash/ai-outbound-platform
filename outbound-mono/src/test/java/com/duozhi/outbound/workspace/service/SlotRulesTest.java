package com.duozhi.outbound.workspace.service;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SlotRulesTest {
    @Test
    void numericRangeDateAndRequiredChoiceAreChecked() {
        assertThat(
                        SlotRules.invalid(
                                Map.of("min_value", 18, "max_value", 70),
                                Map.of("kind", "integer"),
                                "17"))
                .contains("范围");
        assertThat(SlotRules.invalid(Map.of(), Map.of("kind", "date"), "2026-02-30"))
                .contains("日期");
        assertThat(
                        SlotRules.invalid(
                                Map.of(),
                                Map.of("kind", "choice", "choices", List.of("北京", "上海")),
                                "北京或上海"))
                .contains("一个");
        assertThat(SlotRules.invalid(Map.of("max_length", 3), Map.of("kind", "text"), "😀😀😀"))
                .isEmpty();
    }

    @Test
    void ruleMustReferenceExistingSlotAndHaveSensibleBounds() {
        assertThatThrownBy(
                        () ->
                                SlotRules.normalize(
                                        List.of(Map.of("key", "missing")),
                                        List.of(Map.of("key", "age"))))
                .hasMessageContaining("未关联");
        assertThatThrownBy(
                        () ->
                                SlotRules.normalize(
                                        List.of(
                                                Map.of(
                                                        "key",
                                                        "age",
                                                        "min_value",
                                                        80,
                                                        "max_value",
                                                        18)),
                                        List.of(Map.of("key", "age"))))
                .hasMessageContaining("下限");
        assertThat(
                        SlotRules.normalize(
                                List.of(
                                        Map.of(
                                                "key",
                                                "age",
                                                "max_retries",
                                                2,
                                                "missing_action",
                                                "handoff")),
                                List.of(Map.of("key", "age"))))
                .hasSize(1);
    }

    @Test
    void workbookStreamingKeepsTextAndRejectsFormulaInsteadOfUsingCachedValues() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = book.createSheet("外呼名单");
            sheet.createRow(0).createCell(0).setCellValue("手机号");
            sheet.createRow(1).createCell(0).setCellValue("0013800000000");
            book.write(out);
        }
        assertThat(
                        com.duozhi.outbound.customer.controller.ContactWorkbook.csv(
                                out.toByteArray(), 100))
                .contains("0013800000000");
        var bad = new java.io.ByteArrayOutputStream();
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            book.createSheet("外呼名单").createRow(0).createCell(0).setCellFormula("1+1");
            book.write(bad);
        }
        assertThatThrownBy(
                        () ->
                                com.duozhi.outbound.customer.controller.ContactWorkbook.csv(
                                        bad.toByteArray(), 100))
                .hasMessageContaining("公式");
    }
}

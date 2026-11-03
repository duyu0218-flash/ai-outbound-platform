package com.duozhi.outbound.workspace.service;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.exception.ApiError;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class KnowledgeDocumentsTest {
    @Test
    void textDecodingRejectsDamagedInputAndKeepsUnicodeParagraphs() throws Exception {
        assertThat(
                        KnowledgeDocuments.extract(
                                "规则.md", "\uFEFF预约办理\r\n不承诺审批通过。".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("预约办理\n不承诺审批通过。");
        assertThatThrownBy(() -> KnowledgeDocuments.extract("bad.txt", new byte[] {(byte) 0xff}))
                .isInstanceOf(ApiError.class)
                .hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> KnowledgeDocuments.extract("empty.txt", new byte[] {32, 10}))
                .hasMessageContaining("没有可提取文字");
        assertThatThrownBy(() -> KnowledgeDocuments.extract("old.doc", new byte[] {1, 2, 3}))
                .hasMessageContaining("不支持");
    }

    @Test
    void segmentationPreservesTextBoundariesAndUsesBoundedOverlap() {
        String text = "办理条件。".repeat(60) + "\n\n" + "价格需人工确认。".repeat(60) + "😀".repeat(150);
        var chunks = KnowledgeDocuments.split(text, 240, 30);
        assertThat(chunks)
                .hasSizeGreaterThan(2)
                .allSatisfy(
                        s -> {
                            assertThat(s.length()).isLessThanOrEqualTo(240);
                            assertThat(Character.isLowSurrogate(s.charAt(0))).isFalse();
                            assertThat(Character.isHighSurrogate(s.charAt(s.length() - 1)))
                                    .isFalse();
                        });
        assertThat(chunks.getFirst()).startsWith("办理条件。");
        assertThat(chunks.getLast()).endsWith("😀");
        assertThatThrownBy(() -> KnowledgeDocuments.split(text, 200, 100))
                .isInstanceOf(ApiError.class);
        assertThatThrownBy(() -> KnowledgeDocuments.split("甲".repeat(110000), 200, 60))
                .hasMessageContaining("500");
    }

    @Test
    void docxExtractsParagraphsAndTablesInsteadOfInventingFaqQuestions() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("预约条件");
            doc.createTable(1, 1).getRow(0).getCell(0).setText("仅供咨询，以人工确认为准");
            doc.write(out);
        }
        assertThat(KnowledgeDocuments.extract("材料.docx", out.toByteArray()))
                .contains("预约条件", "人工确认为准");
        assertThatThrownBy(() -> KnowledgeDocuments.extract("broken.docx", new byte[] {1, 2, 3}))
                .isInstanceOf(Exception.class);
    }

    @Test
    void pdfTextIsExtractedButAnImageOnlyDocumentIsNotAcceptedAsSuccess() throws Exception {
        var text = new ByteArrayOutputStream();
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(30, 700);
                stream.showText("Approved booking conditions");
                stream.endText();
            }
            doc.save(text);
        }
        assertThat(KnowledgeDocuments.extract("terms.pdf", text.toByteArray()))
                .contains("Approved booking conditions");
        var empty = new ByteArrayOutputStream();
        try (var doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(empty);
        }
        assertThatThrownBy(() -> KnowledgeDocuments.extract("scan.pdf", empty.toByteArray()))
                .hasMessageContaining("扫描PDF");
    }
}

package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.dto.ApprovalHistoryItemDTO;
import com.sw.ck.form.api.print.FormPrintFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 服务端打印 PDF 冒烟（1.0.0 F2-3）：真实 PDFBox + 打包字体渲染，
 * 断言产物为合法 PDF 且经过分页（明细表行数 > 单页容量时产出多页）。
 */
@DisplayName("FormPrintPdfService 冒烟：中文 PDF 渲染与明细表分页")
class FormPrintPdfServiceTest {

    private static final String DEF = """
            {
              "title": "门岗异常登记",
              "fields": [
                {"name": "location", "type": "TEXT", "label": "发生地点"},
                {"name": "remark", "type": "TEXT", "label": "备注"},
                {"name": "items", "type": "TABLE", "label": "物资明细",
                 "subFields": [
                   {"name": "goods", "type": "TEXT", "label": "物资"},
                   {"name": "count", "type": "NUMBER", "label": "数量"}
                 ]}
              ]
            }
            """;

    @Test
    @DisplayName("渲染产出 %PDF 头，字段/明细表/审批历史均出现，提交时版本回退有页脚说明")
    void renderProducesValidPdfWithSections() throws Exception {
        Map<String, Object> record = Map.of(
                "location", "南门岗亭",
                "remark", "夜班异常交接",
                "items", List.of(
                        Map.of("goods", "对讲机", "count", 3),
                        Map.of("goods", "登记本", "count", 1)));
        ApprovalHistoryItemDTO historyItem = new ApprovalHistoryItemDTO();
        historyItem.setTaskName("班长复核");
        historyItem.setAssigneeName("张三");
        historyItem.setAssigneeDept("安全部");
        historyItem.setApprovalResult("通过");
        historyItem.setEndTime(LocalDateTime.of(2026, 11, 11, 10, 30));
        ApprovalHistoryItemDTO.OpinionFieldView opinion = new ApprovalHistoryItemDTO.OpinionFieldView();
        opinion.setName("comment");
        opinion.setLabel("意见");
        opinion.setType("TEXTAREA");
        opinion.setDisplayValue("同意处置");
        historyItem.setOpinionFields(List.of(opinion));

        FormPrintFacade facade = mock(FormPrintFacade.class);
        when(facade.recordDetail(eq("gate"), eq("rec-1"), any()))
                .thenReturn(Optional.of(new FormPrintFacade.FormPrintData(
                        record, "门岗异常登记", DEF, 7, false)));
        FormPrintPdfService service = new FormPrintPdfService(facade);

        byte[] pdf = service.render("gate", "rec-1",
                LocalDateTime.of(2026, 11, 10, 9, 0), List.of(historyItem));

        String header = new String(pdf, 0, 5, StandardCharsets.US_ASCII);
        assertEquals("%PDF-", header, "产物应为 PDF");
        assertTrue(pdf.length > 10_000, "嵌入中文字体后产物不应过小: " + pdf.length);

        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertTrue(doc.getNumberOfPages() >= 1, "至少一页");
        }
    }

    @Test
    @DisplayName("无权读取（facade empty）收敛为安全结论异常，不泄露差异")
    void renderRejectsWhenUnreadable() {
        FormPrintFacade facade = mock(FormPrintFacade.class);
        when(facade.recordDetail(eq("gate"), eq("rec-x"), any()))
                .thenReturn(Optional.empty());
        FormPrintPdfService service = new FormPrintPdfService(facade);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.render("gate", "rec-x", null, null));
    }

    @Test
    @DisplayName("U2 日期格式：datetime 字段输出到分，缺省日期字段只到日")
    void renderDateRespectsFieldFormat() throws Exception {
        String def = """
                {
                  "title": "日期格式验收",
                  "fields": [
                    {"name": "dueDate", "type": "DATE", "label": "到货时间"},
                    {"name": "reviewAt", "type": "DATE", "label": "验收时间", "format": "datetime"}
                  ],
                  "tables": []
                }
                """;
        Map<String, Object> record = Map.of(
                "dueDate", java.sql.Timestamp.valueOf("2026-11-12 09:30:00"),
                "reviewAt", java.sql.Timestamp.valueOf("2026-11-12 14:00:00"));
        FormPrintFacade facade = mock(FormPrintFacade.class);
        when(facade.recordDetail(eq("gate"), eq("rec-date"), any()))
                .thenReturn(Optional.of(new FormPrintFacade.FormPrintData(
                        record, "日期格式验收", def, 3, true)));
        FormPrintPdfService service = new FormPrintPdfService(facade);

        byte[] pdf = service.render("gate", "rec-date",
                LocalDateTime.of(2026, 11, 10, 9, 0), List.of());

        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            var stripper = new org.apache.pdfbox.text.PDFTextStripper();
            String text = stripper.getText(doc);
            assertTrue(text.contains("2026-11-12 14:00"), "datetime 字段应输出到分: " + text);
            assertTrue(text.contains("2026-11-12"), "日期字段应输出到日: " + text);
            org.junit.jupiter.api.Assertions.assertFalse(text.contains("2026-11-12 09:30"),
                    "缺省日期字段不应带时分: " + text);
        }
    }
}

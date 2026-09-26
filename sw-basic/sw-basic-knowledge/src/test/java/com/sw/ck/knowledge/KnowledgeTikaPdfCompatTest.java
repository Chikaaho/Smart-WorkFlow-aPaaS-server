package com.sw.ck.knowledge;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6A 补证 G2 · Knowledge 文档解析调用域兼容行为锚点。
 *
 * <p>
 * 本模块声明 Tika + PDFBox 作为文档解析能力，但此前无任何测试类，Phase 6A 的依赖收敛
 * （bcprov / commons-io / commons-logging / antlr4-runtime / jackcess 均为 Tika 传递树成员）
 * 在该域没有行为锚点。本测试补上最小、离线、内存内的真实解析链：
 * PDFBox 生成内存 PDF → Tika {@link AutoDetectParser} 实际解析 → 断言哨兵文本与 MIME。
 * </p>
 *
 * <p>
 * 不访问网络、不读写磁盘、不依赖 Spring 上下文；失败即说明 Tika/PDFBox 在收敛后的
 * 版本组合上无法完成文档解析这一真实调用域行为。
 * </p>
 */
@DisplayName("Phase 6A G2：Knowledge Tika/PDFBox 内存解析兼容行为")
class KnowledgeTikaPdfCompatTest {

    /** 哨兵文本：ASCII，保证 Standard14 HELVETICA 可编码，且可被文本抽取原样读回。 */
    private static final String SENTINEL = "PHASE6A-TIKA-PDF-SENTINEL-42";

    @Test
    @DisplayName("PDFBox 生成内存 PDF → Tika 实际解析 → 哨兵文本与 application/pdf 断言")
    void pdfBoxGeneratedPdfIsParsedByTika() throws Exception {
        byte[] pdf = buildPdfWithSentinel(SENTINEL);

        Metadata metadata = new Metadata();
        BodyContentHandler handler = new BodyContentHandler();
        new AutoDetectParser().parse(new ByteArrayInputStream(pdf), handler, metadata, new ParseContext());
        String text = handler.toString();

        assertThat(text)
                .as("Tika 必须能从 PDFBox 生成的 PDF 中抽取到哨兵文本")
                .contains(SENTINEL);
        assertThat(metadata.get(Metadata.CONTENT_TYPE))
                .as("探测器必须识别为 PDF")
                .contains("application/pdf");

        System.out.println("[P6A-G2] knowledge tika-pdf bytes=" + pdf.length
                + " mime=" + metadata.get(Metadata.CONTENT_TYPE)
                + " sentinelPresent=" + text.contains(SENTINEL));
    }

    private static byte[] buildPdfWithSentinel(String sentinel) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(sentinel);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}

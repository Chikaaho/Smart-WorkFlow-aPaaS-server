package com.sw.ck.knowledge.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("F1 阶段 3：PDF/Word/TXT/Markdown 文档解析")
class KnowledgeDocumentParserTest {

    private final KnowledgeDocumentParser parser = new KnowledgeDocumentParser();

    @Test
    @DisplayName("TXT/Markdown 文本直接抽取")
    void parse_plainTextAndMarkdown() {
        assertThat(parser.parse("# 标题\n\n正文内容".getBytes(StandardCharsets.UTF_8)))
                .contains("标题").contains("正文内容");
    }

    @Test
    @DisplayName("DOCX（最小 OOXML）抽取正文哨兵文本")
    void parse_docx() throws Exception {
        String sentinel = "DOCX-SENTINEL-蓝鲸七号";
        byte[] docx = minimalDocx(sentinel);
        assertThat(parser.parse(docx)).contains("DOCX-SENTINEL").contains("蓝鲸七号");
    }

    private static byte[] minimalDocx(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "</Relationships>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body><w:p><w:r><w:t>" + text + "</w:t></w:r></w:p></w:body></w:document>")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}

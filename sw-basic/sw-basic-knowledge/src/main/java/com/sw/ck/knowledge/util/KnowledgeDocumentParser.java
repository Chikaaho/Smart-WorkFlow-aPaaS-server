package com.sw.ck.knowledge.util;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/**
 * 文档文本抽取（F1 阶段 3）：Tika 自动识别 PDF / Word / TXT / Markdown。
 * 输入为上传字节；不落临时文件，避免原文残留。
 */
@Component
public class KnowledgeDocumentParser {

    private final Tika tika = new Tika();

    public String parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文档内容为空");
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            String text = tika.parseToString(in);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("未能从文档中抽取到文本内容");
            }
            return text;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("文档解析失败: " + e.getMessage(), e);
        }
    }
}

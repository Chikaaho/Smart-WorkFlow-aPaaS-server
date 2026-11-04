package com.sw.ck.knowledge.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量文本切分（F1 阶段 3）：按字符窗口切分，支持可配置的块大小与重叠。
 * <p>
 * 不引入额外分词/语言模型依赖；对中文、英文、Markdown 均按字符处理。
 * 切分前统一换行并折叠多余空行；纯空白块不进入结果。
 * </p>
 */
public final class KnowledgeTextChunker {

    private KnowledgeTextChunker() {
    }

    /** 单文档最大分片数（防御超大文件产生过量 Embedding 调用）。 */
    public static final int MAX_CHUNKS = 2000;

    public static List<String> chunk(String raw, int chunkSize, int overlap) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        int size = Math.max(1, chunkSize);
        int ov = Math.max(0, Math.min(overlap, size - 1));
        String text = normalize(raw);
        if (text.isEmpty()) {
            return List.of();
        }
        int step = size - ov;
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + size, text.length());
            String part = text.substring(start, end).trim();
            if (!part.isEmpty()) {
                chunks.add(part);
                if (chunks.size() > MAX_CHUNKS) {
                    throw new IllegalArgumentException("文档分片数超过上限 " + MAX_CHUNKS
                            + "，请增大切分块大小或拆分文档");
                }
            }
            if (end == text.length()) {
                break;
            }
        }
        return chunks;
    }

    static String normalize(String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replaceAll("[\\t\\u000B\\f]+", " ");
        text = text.replaceAll(" {2,}", " ");
        text = text.replaceAll("\n{3,}", "\n\n");
        return text.trim();
    }
}

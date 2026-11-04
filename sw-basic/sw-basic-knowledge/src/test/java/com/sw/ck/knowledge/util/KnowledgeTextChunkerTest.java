package com.sw.ck.knowledge.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("F1 阶段 3：知识库文本切分")
class KnowledgeTextChunkerTest {

    @Test
    @DisplayName("按块大小与重叠切分，覆盖全文")
    void chunk_shouldCoverTextWithOverlap() {
        String text = "0123456789ABCDEFGHIJ";
        List<String> chunks = KnowledgeTextChunker.chunk(text, 8, 2);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0)).isEqualTo("01234567");
        assertThat(chunks.get(1)).isEqualTo("6789ABCD");
        assertThat(chunks).containsExactly("01234567", "6789ABCD", "CDEFGHIJ");
    }

    @Test
    @DisplayName("空白文本返回空列表；重叠被限制在块大小内")
    void chunk_blankOrIllegalOverlap() {
        assertThat(KnowledgeTextChunker.chunk("   ", 10, 5)).isEmpty();
        // 重叠被夹到 size-1（3），步长 1 → 滑动窗口
        List<String> chunks = KnowledgeTextChunker.chunk("abcdef", 4, 99);
        assertThat(chunks).containsExactly("abcd", "bcde", "cdef");
    }

    @Test
    @DisplayName("换行/制表符归一化后切分")
    void chunk_shouldNormalizeWhitespace() {
        List<String> chunks = KnowledgeTextChunker.chunk("a\t\tb\r\n\r\n\r\nc", 100, 0);
        assertThat(chunks).containsExactly("a b\n\nc");
    }
}

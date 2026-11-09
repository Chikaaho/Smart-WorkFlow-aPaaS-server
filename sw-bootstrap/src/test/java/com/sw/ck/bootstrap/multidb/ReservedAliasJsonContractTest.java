package com.sw.ck.bootstrap.multidb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.entity.BpmTaskOpinionDraft;
import com.sw.ck.notify.entity.NotifyMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S6：`read` / `comment` 两个 Java 字段改名后，对外 JSON 契约保持不变，Web 无需改动。
 */
@DisplayName("S6：保留字别名改名后的 JSON 契约")
class ReservedAliasJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("NotifyMessage 序列化键仍为 read，不出现 isRead")
    void notifyReadKeyUnchanged() throws Exception {
        NotifyMessage message = new NotifyMessage();
        message.setIsRead(true);
        String json = mapper.writeValueAsString(message);
        assertTrue(json.contains("\"read\":true"), "JSON 应包含 read=true，实际: " + json);
        assertFalse(json.contains("\"isRead\""), "JSON 不应暴露 isRead，实际: " + json);
    }

    @Test
    @DisplayName("BpmTaskOpinionDraft 序列化键仍为 comment，不出现 opinionComment")
    void opinionCommentKeyUnchanged() throws Exception {
        BpmTaskOpinionDraft draft = new BpmTaskOpinionDraft();
        draft.setOpinionComment("保留意见");
        String json = mapper.writeValueAsString(draft);
        assertTrue(json.contains("\"comment\":\"保留意见\""), "JSON 应包含 comment，实际: " + json);
        assertFalse(json.contains("\"opinionComment\""), "JSON 不应暴露 opinionComment，实际: " + json);
    }
}

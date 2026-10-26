package com.sw.ck.bpm.process.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 催办请求（可空 body：旧客户端无 body 调用保持可用）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrgeReqDTO {

    /** 发起人可选催办留言（最长 200 字，空白视为无留言；随站内催办通知送达并留痕）。 */
    private String message;

    /**
     * 可选催办接收人（稳定用户 ID）：必须全部属于服务端核定的当前实例合法待办人集合；
     * 为空表示沿用实例级旧语义（催办全部当前待办人）。
     */
    private java.util.List<Long> recipientIds;
}

package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.util.List;

/**
 * 后台批量受理请求（P62 后台批量形态：受控动作批量调用）。
 */
@Data
public class TxnBatchSubmitRequest {

    /** 批次键（同租户唯一；重放返回原批次）。 */
    private String batchKey;

    /** 绑定的已发布受控动作。 */
    private String actionId;

    /** 批次项（1—500）。 */
    private List<Item> items;

    @Data
    public static class Item {

        /** 批次内稳定项键。 */
        private String itemKey;

        /** 目标业务记录标识。 */
        private String recordId;

        /** 数量（精度由动作配置裁决）。 */
        private String quantity;
    }
}

package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.util.List;

/**
 * 后台批量批次视图（批次 + 逐项结果；U07 批量项可独立追踪）。
 */
@Data
public class TxnBatchView {

    private String batchKey;

    private String actionId;

    private Integer actionVersion;

    private String status;

    private Integer totalCount;

    private Integer succeededCount;

    private Integer failedCount;

    private Long commandId;

    /** 批次重放命中原批次时为 true。 */
    private boolean replay;

    private List<Item> items;

    @Data
    public static class Item {

        private String itemKey;

        private String recordId;

        private String quantity;

        private String status;

        private String invocationId;

        private Integer errorCode;

        private String errorMsg;

        private Integer attemptCount;
    }
}

package com.sw.ck.openapi.biz.vo;

import lombok.Data;

/**
 * 开放接口嵌入式任务办理结果。
 */
@Data
public class OpenApiCompleteTaskResultVO {

    private String taskId;

    private boolean completed;
}

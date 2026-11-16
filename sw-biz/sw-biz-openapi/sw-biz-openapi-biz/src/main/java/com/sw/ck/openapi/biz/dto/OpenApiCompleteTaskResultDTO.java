package com.sw.ck.openapi.biz.dto;

import lombok.Data;

/**
 * 开放接口嵌入式任务办理结果。
 */
@Data
public class OpenApiCompleteTaskResultDTO {

    private String taskId;

    private boolean completed;
}

package com.sw.ck.bpm.process.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 任务节点业务表单读取结果。
 * <p>
 * 未绑定流程时仅回 {@code bound=false}；已绑定时返回表单标识、冻结定义与当前数据。
 * 定义与数据均为动态结构（Map），保持原有 JSON 形状；未建立的字段按原契约省略。
 * </p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskNodeFormDTO {

    private boolean bound;

    private String formKey;

    private String formName;

    private String formVersion;

    private Map<String, Object> definition;

    private String status;

    private Map<String, Object> data;

    private Long roundNo;
}

package com.sw.ck.agent.dto;

import lombok.Data;

import java.util.Map;

/** 人工任务提交请求（G3-2）：填写值与决策动作。 */
@Data
public class AgentHumanTaskSubmitDTO {

    /** 表单填写值（字段名 → 值） */
    private Map<String, String> values;

    /** 决策动作（按钮 keyword；超时由服务端内部使用） */
    private String decision;
}

package com.sw.ck.agent.dto;

import lombok.Data;

import java.util.Map;

/**
 * 图执行请求 DTO（M07-F02 Step8 + Step10 多变量执行上下文）。
 * <p>
 * 单一 {@code input} 文本写入执行上下文默认变量（Step10：节点未指定变量名时读写
 * 默认变量，旧图零迁移；节点可经 config.inputVar/outputVar 存取命名变量）。
 * </p>
 */
@Data
public class AgentGraphExecuteReqDTO {

    /** 执行入参文本（旧契约：写入默认变量 input；与 inputs 至少一个非空） */
    private String input;

    /** 结构化入参（G1-4：开始节点参数名 → 值；缺必填参数由执行层拒绝并指明参数名） */
    private Map<String, String> inputs;
}

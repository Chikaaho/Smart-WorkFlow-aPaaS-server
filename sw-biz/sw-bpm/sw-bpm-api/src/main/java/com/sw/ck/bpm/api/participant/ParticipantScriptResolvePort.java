package com.sw.ck.bpm.api.participant;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 审批人表达式脚本解析接缝（业务侧实现）。
 * <p>
 * 流程中的表达式是**计算值**的适配器：脚本以 {@code return} 结束，返回值即参与人集合。
 * 引擎只面向本接口，脚本执行与组织目录/实例变量快照由业务侧（sw-bpm-process）提供，
 * 与触发器脚本共用同一执行引擎（GraalJS + {@code #} 函数语法糖）。
 * </p>
 */
public interface ParticipantScriptResolvePort {

    /**
     * 执行审批人表达式脚本并返回参与人集合。
     *
     * @param tenantId          租户
     * @param processInstanceId 流程实例（用于实例变量快照；预览场景可为 null）
     * @param script            脚本（函数体，以 return 返回参与人）
     * @param variables         调用方已知的变量（与实例变量快照合并使用）
     * @return present = 参与人列表（脚本返回空/空白时为空列表，属合法零匹配）；
     *         当前契约恒 present
     * @throws com.sw.ck.bpm.api.exception.BpmErrorCode 语义外的执行失败以运行时异常上抛
     */
    Optional<List<String>> resolve(Long tenantId, String processInstanceId,
                                   String script, Map<String, Object> variables);
}

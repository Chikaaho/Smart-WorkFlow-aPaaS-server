package com.sw.ck.agent.api.port;

import java.util.List;

/**
 * 已发布图的固定版本绑定描述（发布校验与设计期选择的数据源）。
 *
 * @param graphDefId             图定义 id
 * @param graphKey               图 key（冻结）
 * @param name                   图名称
 * @param defVersion             绑定版本（不可变快照版本号）
 * @param executionTimeoutSeconds 图自身执行超时上限（1–3600s，节点超时不得超过它）
 * @param containsHumanNode      该版本是否含人工节点（含子图递归；1.2.0 口径：同步调用方发布期拒绝）
 * @param startParams            开始节点参数声明（G1-4：name/type/required/description）
 * @param endParams              结束节点输出声明（G1-4：name/description；声明即必产出，
 *                               运行结果缺失按 OUTPUT_MISSING 失败——E1-4「不能节点成功但出参为空」）
 */
public record AgentGraphVersionBinding(
        Long graphDefId,
        String graphKey,
        String name,
        Integer defVersion,
        Integer executionTimeoutSeconds,
        boolean containsHumanNode,
        List<ParamDecl> startParams,
        List<OutputDecl> endParams) {

    /** 开始节点参数声明（StartPanel：name/type(text|number|boolean|json)/required/description）。 */
    public record ParamDecl(String name, String type, boolean required, String description) {
    }

    /** 结束节点输出声明（EndPanel：name/source/description；对外只暴露 name/description）。 */
    public record OutputDecl(String name, String description) {
    }
}

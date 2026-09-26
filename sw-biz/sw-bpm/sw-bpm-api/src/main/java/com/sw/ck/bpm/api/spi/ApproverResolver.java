package com.sw.ck.bpm.api.spi;

import com.sw.ck.bpm.api.dto.ApproverContext;

import java.util.Optional;

/**
 * 审批人解析 SPI。
 * <p>
 * 定义在 <code>sw-bpm-api</code>（system.md §1 跨模块通信），
 * 各实现（固定审批人、角色、岗位、主管、上级、字段匹配、自选等）注册于
 * <code>sw-bpm-process</code>，经 Spring 注入调用。
 * </p>
 *
 * @deprecated 已被 {@link com.sw.ck.bpm.api.spi.assignee.NodeApproverResolver} 取代。
 *     新实现一律接 {@code NodeApproverResolver}，勿再接本接口。
 *     本接口及现有 {@link com.sw.ck.bpm.process.resolver.FixedApproverResolver} 仅
 *     保留给老 skeleton 注入路径（{@code ${approver}} / {@code ProcessStartService} /
 *     {@code skeleton_approval.bpmn20.xml}）使用。
 *     退休本接口 + 老 skeleton 路径为独立小刀，排在脚本档之后执行。
 * @see com.sw.ck.bpm.api.dto.ApproverContext
 */
@Deprecated
public interface ApproverResolver {

    /**
     * 根据发起上下文解析审批人。
     *
     * @param context 流程发起上下文（至少含 formKey、submittedData、submitter、tenantId）
     * @return present = 审批人用户 ID（字符串形式，对应 Flowable assignee 变量 ${approver}）；
     *         当前契约恒 present，解析失败抛明确异常，不得返回空值
     */
    Optional<String> resolve(ApproverContext context);
}

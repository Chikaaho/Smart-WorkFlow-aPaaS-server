package com.sw.ck.form.api.port;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 表单事务动作受控调用 Port（form-api 定义，form-biz 实现）。
 * <p>
 * 供 BPM 生产轻流程节点等<strong>内部调用方</strong>复用首阶段交付的事务内核
 * （冻结版本、幂等/指纹、预占/台账、C1 保护），不复制动作逻辑、不直连动作表。
 * 调用方须已还原可信 {@code LoginUserHolder}（租户/用户/权限）；本 Port 在服务端校验
 * 操作者权限与动作租户归属——<strong>不因绕过 HTTP 控制器而放宽授权</strong>。
 * </p>
 * <p>
 * 发布期绑定校验用 {@link #describe(String)}；运行期调用用 {@link #invoke(TxnActionCommand)}。
 * 失败以业务异常或显式 REJECTED 结果表达，不静默成功。
 * </p>
 */
public interface FormTxnActionPort {

    /** 受控调用已发布事务动作（幂等键沿用调用方稳定键，如 NODE:{instanceId}:{nodeId}）。 */
    TxnActionResult invoke(TxnActionCommand command);

    /** 发布期绑定校验：动作存在、同租户且已发布（未发布或无权限时返回 empty）。 */
    Optional<TxnActionDescriptor> describe(String actionId);

    /** 调用输入（字段与 {@code TxnInvokeRequest} 同口径，仅暴露跨模块所需最小集合）。 */
    record TxnActionCommand(String actionId, String recordId, String quantity, String invocationKey,
                            Long expectedVersion, String reservationId, Integer actionVersion) {

        /** 六参兼容构造（未指定发布版本 = 最新版本）。 */
        public TxnActionCommand(String actionId, String recordId, String quantity, String invocationKey,
                                Long expectedVersion, String reservationId) {
            this(actionId, recordId, quantity, invocationKey, expectedVersion, reservationId, null);
        }
    }

    /** 调用结果（状态/错误码/业务对象/冻结版本；重放时 replay=true 且返回原结果）。 */
    record TxnActionResult(String invocationId, String status, Integer actionVersion,
                           String reservationId, BigDecimal quantity, BigDecimal balanceAfter,
                           BigDecimal reservedAfter, Integer errorCode, String errorMsg,
                           Long durationMs, boolean replay) {
    }

    /** 动作描述（发布期绑定校验用）。 */
    record TxnActionDescriptor(String id, String formId, String actionKey, String actionType,
                               String status, Integer currentVersion) {
    }

    /**
     * 按调用键只读回查动作调用行（P62 资源保障 RG05：命令/目标关联查询；
     * 仅暴露跨模块所需最小集合，身份明细经授权查询端点关联）。
     * 调用方须已持合法身份与租户边界（实现按租户过滤，跨租户按 empty 处理）。
     */
    Optional<TxnInvocationSummary> findInvocationByKey(String invocationKey);

    /**
     * 按业务记录 id 只读回查关联调用行（P62 资源保障 RG05：轻流程受理→目标动作关联查询；
     * 时间倒序，最多 limit 行）。调用方须已持合法身份与租户边界。
     */
    java.util.List<TxnInvocationSummary> listInvocationsByBizRecord(String bizRecordId, int limit);

    /** 调用行只读摘要（关联查询用）。 */
    record TxnInvocationSummary(String invocationId, String actionId, Integer actionVersion,
                                String status, Integer errorCode, String errorMsg,
                                String bizRecordId, java.time.LocalDateTime createTime) {
    }
}

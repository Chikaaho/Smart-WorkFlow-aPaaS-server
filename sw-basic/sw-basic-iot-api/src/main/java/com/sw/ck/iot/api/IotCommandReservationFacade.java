package com.sw.ck.iot.api;

import java.util.List;
import java.util.Optional;

/**
 * IoT 命令预约门面（P63 一次性预约：流程成功结束后创建、到点下发）。
 * <p>
 * 意图在同一成功完成事务内持久化（持久化失败=该次完成不提交）；幂等身份为
 * （租户, 流程实例）：重复成功事件/恢复重放复用既有意图，不产生第二个预约。
 * 创建时刻已到/已过的预约保留记录并直接置 EXPIRED（不立即补发）。
 * </p>
 */
public interface IotCommandReservationFacade {

    /**
     * 同事务创建预约意图（幂等；契约参数全部为 JDK 类型）。
     *
     * @return present = 意图 ID（新建或既有）；意图已存在时返回既有 ID（幂等命中）；
     *         设备/租户上下文缺失等真实错误抛出；empty 契约不使用（恒 present 或抛出）
     */
    Optional<Long> createIntent(Long tenantId, String processInstanceId, String processDefKey,
                                Integer defVersion, String formKey, String recordId,
                                String deviceKey, String productId, String deviceName,
                                String commandKey, String commandType, String payloadJson,
                                java.time.LocalDateTime dueAtUtc, String timezoneId,
                                String dueLocalText, int lateWindowSeconds);

    /**
     * 取消待触发预约（条件更新：仅 PENDING 可取消；与到点认领竞争只一个结果生效）。
     *
     * @return present("CANCELED") = 取消成功（无后续外发）；
     *         present("NOT_CANCELLABLE") = 已进入下发阶段/已取消/已过期（明确结果，不假称撤销设备动作）；
     *         empty = 意图不存在或租户不符
     */
    Optional<String> cancel(Long tenantId, Long reservationId, Long actorUserId, String reason);

    /** 取消结果常量（契约层不引入枚举成员，保持 JDK-only 签名口径）。 */
    String CANCEL_CANCELED = "CANCELED";
    String CANCEL_NOT_CANCELLABLE = "NOT_CANCELLABLE";

    /** 按流程实例查询预约（关联链：流程 → 预约 → 命令 → 回执）。 */
    Optional<List<IotReservationView>> findByProcessInstance(Long tenantId, String processInstanceId);

    /** 按主键查询单条预约。 */
    Optional<IotReservationView> find(Long tenantId, Long reservationId);

    /** 预约视图（查询/流程详情/结果回查）。 */
    record IotReservationView(Long id, Long tenantId, String processInstanceId, String processDefKey,
                              Integer defVersion, String formKey, String recordId,
                              String deviceKey, String productId, String deviceName,
                              String commandKey, String commandType, String payloadJson,
                              java.time.LocalDateTime dueAtUtc, String timezoneId,
                              String dueLocalText, int lateWindowSeconds,
                              String status, Long commandId, String rejectReason,
                              Long cancelBy, String cancelReason,
                              java.time.LocalDateTime cancelTime,
                              java.time.LocalDateTime createTime) {
    }
}

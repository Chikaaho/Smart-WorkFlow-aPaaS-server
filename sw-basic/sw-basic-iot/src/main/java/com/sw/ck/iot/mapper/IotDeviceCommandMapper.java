package com.sw.ck.iot.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.iot.entity.IotDeviceCommand;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * IoT 设备命令 Mapper。
 * <p>
 * 支持按 productId + deviceName 查询命令列表，支持过期命令检测。
 * </p>
 */
@Mapper
public interface IotDeviceCommandMapper extends BaseMapperX<IotDeviceCommand> {

    /**
     * 查询设备待发送命令（按创建时间排序）。
     *
     * @param productId  腾讯云产品 ID
     * @param deviceName 腾讯云设备名称
     * @param tenantId   租户 ID
     * @return 待发送命令列表
     */
    default List<IotDeviceCommand> selectPendingByProductAndDevice(String productId, String deviceName, Long tenantId) {
        return selectList(new LambdaQueryWrapper<IotDeviceCommand>()
                .eq(IotDeviceCommand::getProductId, productId)
                .eq(IotDeviceCommand::getDeviceName, deviceName)
                .eq(IotDeviceCommand::getTenantId, tenantId)
                .eq(IotDeviceCommand::getDeleted, 0)
                .in(IotDeviceCommand::getStatus, "QUEUED", "FAILED")
                .orderByAsc(IotDeviceCommand::getCreateTime));
    }

    /**
     * 查询过期命令（状态为 QUEUED 且已过期）。
     *
     * @param now      当前时间
     * @param tenantId 租户 ID
     * @return 过期命令列表
     */
    default List<IotDeviceCommand> selectExpired(LocalDateTime now, Long tenantId) {
        return selectList(new LambdaQueryWrapper<IotDeviceCommand>()
                .eq(IotDeviceCommand::getTenantId, tenantId)
                .eq(IotDeviceCommand::getDeleted, 0)
                .eq(IotDeviceCommand::getStatus, "QUEUED")
                .le(IotDeviceCommand::getExpiryTime, now));
    }

    /**
     * 查询回执超时命令（P62 S4：已真实发出 SENT/DELIVERED/ACKED，
     * 超过回执等待窗口仍无确定业务回执 → 待核实 UNKNOWN；据此转换，禁止自动重发）。
     *
     * @param now      当前时间
     * @param tenantId 租户 ID（null = 跨租户扫描，供补偿调度挂起租户过滤使用）
     * @return 回执超时命令列表
     */
    default List<IotDeviceCommand> selectReceiptTimeout(LocalDateTime now, Long tenantId) {
        return selectList(new LambdaQueryWrapper<IotDeviceCommand>()
                .eq(tenantId != null, IotDeviceCommand::getTenantId, tenantId)
                .eq(IotDeviceCommand::getDeleted, 0)
                .in(IotDeviceCommand::getStatus, "SENT", "DELIVERED", "ACKED")
                .le(IotDeviceCommand::getExpiryTime, now));
    }

    /**
     * 按幂等键查询命令（防重复）。
     *
     * @param idempotentKey 幂等键
     * @return 命令实体，不存在时返回 null
     */
    default IotDeviceCommand selectByIdempotentKey(String idempotentKey) {
        return selectOne(new LambdaQueryWrapper<IotDeviceCommand>()
                .eq(IotDeviceCommand::getIdempotentKey, idempotentKey)
                .eq(IotDeviceCommand::getDeleted, 0));
    }

    /**
     * 查询设备滞留命令（状态为 QUEUED 且超过指定时间未处理）。
     *
     * @param productId  腾讯云产品 ID
     * @param deviceName 腾讯云设备名称
     * @param since      起始时间
     * @param tenantId   租户 ID
     * @return 滞留命令列表
     */
    default List<IotDeviceCommand> selectStuckCommands(String productId, String deviceName, LocalDateTime since, Long tenantId) {
        return selectList(new LambdaQueryWrapper<IotDeviceCommand>()
                .eq(IotDeviceCommand::getProductId, productId)
                .eq(IotDeviceCommand::getDeviceName, deviceName)
                .eq(IotDeviceCommand::getTenantId, tenantId)
                .eq(IotDeviceCommand::getDeleted, 0)
                .eq(IotDeviceCommand::getStatus, "QUEUED")
                .le(IotDeviceCommand::getCreateTime, since));
    }
}

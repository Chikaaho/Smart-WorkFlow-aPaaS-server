package com.sw.ck.iot.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.entity.IotProduct;
import com.sw.ck.iot.entity.IotThingModel;
import com.sw.ck.iot.mapper.IotDeviceCommandMapper;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.mapper.IotProductMapper;
import com.sw.ck.iot.mapper.IotThingModelMapper;
import com.sw.ck.iot.service.IotDeviceService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * IoT 设备控制服务实现。
 * <p>
 * 设备身份固定为 {@code productId + deviceName}，二者均不可为空。
 * 支持两类控制语义：延迟生效（DEFERRED）和在线确认（ONLINE_CONFIRM）。
 * </p>
 */
@Service
public class IotDeviceServiceImpl extends BaseServiceImpl<IotDeviceMapper, IotDevice>
        implements IotDeviceService {

    private static final Logger log = LoggerFactory.getLogger(IotDeviceServiceImpl.class);

    private static final Set<String> REPORTABLE_STATUS = Set.of("SUCCESS", "FAILED");

    private final IotDeviceCommandMapper commandMapper;
    private final IotProductMapper productMapper;
    private final IotThingModelMapper thingModelMapper;

    public IotDeviceServiceImpl(IotDeviceCommandMapper commandMapper,
                                IotProductMapper productMapper,
                                IotThingModelMapper thingModelMapper) {
        this.commandMapper = commandMapper;
        this.productMapper = productMapper;
        this.thingModelMapper = thingModelMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDevice register(IotDevice device) {
        if (device.getProductId() == null || device.getProductId().isBlank()) {
            throw new BaseException(400, "productId 不能为空");
        }
        if (device.getDeviceName() == null || device.getDeviceName().isBlank()) {
            throw new BaseException(400, "deviceName 不能为空");
        }
        if (device.getName() == null || device.getName().isBlank()) {
            throw new BaseException(400, "设备名称不能为空");
        }
        IotDevice existing = getByProductAndDeviceName(device.getProductId(), device.getDeviceName());
        if (existing != null) {
            throw new BaseException(400, "设备已存在: productId=" + device.getProductId()
                    + ", deviceName=" + device.getDeviceName());
        }
        if (device.getStatus() == null || device.getStatus().isBlank()) {
            device.setStatus("OFFLINE");
        }
        if (device.getTencentStatus() == null || device.getTencentStatus().isBlank()) {
            device.setTencentStatus("offline");
        }
        save(device);
        log.info("设备已注册: productId={}, deviceName={}, name={}, status={}",
                device.getProductId(), device.getDeviceName(), device.getName(), device.getStatus());
        return device;
    }

    @Override
    public IotDevice getByProductAndDeviceName(String productId, String deviceName) {
        Long tenantId = getCurrentTenantId();
        return baseMapper.selectByProductAndDeviceName(productId, deviceName, tenantId);
    }

    @Override
    public IotDevice getByDeviceKey(String deviceKey) {
        return lambdaQuery()
                .eq(IotDevice::getDeviceKey, deviceKey)
                .last("limit 1")
                .one();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceCommand dispatchCommand(String productId, String deviceName,
                                            String commandKey, String commandType,
                                            String payload, String approvalBizId) {
        // 无调用方幂等身份时保留随机键（历史语义），审批驱动路径必须使用幂等键重载
        return enqueueCommand(productId, deviceName, commandKey, commandType, payload,
                approvalBizId, UUID.randomUUID().toString());
    }

    @Override
    public com.sw.ck.iot.api.IotDeviceFacade.DeviceTarget resolveDeviceTarget(Long tenantId, Long deviceId) {
        if (tenantId == null || deviceId == null) {
            return null;
        }
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            IotDevice device = baseMapper.selectById(deviceId);
            if (device == null || !tenantId.equals(device.getTenantId())
                    || Integer.valueOf(1).equals(device.getDeleted())
                    // P63 G03a：resolve=权威解析可执行目标——同租户也须发布态+流程接入授权
                    || !"PUBLISHED".equals(device.getManageStatus())
                    || device.getProcessAccessEnabled() == null || device.getProcessAccessEnabled() != 1) {
                return null;
            }
            return new com.sw.ck.iot.api.IotDeviceFacade.DeviceTarget(
                    device.getDeviceKey(), device.getProductId(), device.getDeviceName());
        }
    }

    @Override
    public com.sw.ck.iot.api.IotDeviceFacade.DeviceTarget resolveDeviceTargetByKey(Long tenantId, String deviceKey) {
        if (tenantId == null || deviceKey == null || deviceKey.isBlank()) {
            return null;
        }
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            IotDevice device = baseMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<IotDevice>lambdaQuery()
                            .eq(IotDevice::getDeviceKey, deviceKey)
                            .eq(IotDevice::getTenantId, tenantId)
                            .eq(IotDevice::getDeleted, 0)
                            // P63 G03a：到点权威重核执行授权（发布态+流程接入），拒绝可查
                            .eq(IotDevice::getManageStatus, "PUBLISHED")
                            .eq(IotDevice::getProcessAccessEnabled, 1)
                            .last("LIMIT 1"));
            if (device == null || !tenantId.equals(device.getTenantId())) {
                return null;
            }
            return new com.sw.ck.iot.api.IotDeviceFacade.DeviceTarget(
                    device.getDeviceKey(), device.getProductId(), device.getDeviceName());
        }
    }

    public IotDeviceCommand dispatchCommandIdempotent(String productId, String deviceName,
                                                      String commandKey, String commandType,
                                                      String payload, String approvalBizId,
                                                      String idempotentKey) {
        if (idempotentKey == null || idempotentKey.isBlank()) {
            throw new BaseException(400, "idempotentKey 不能为空");
        }
        IotDeviceCommand existing = commandMapper.selectByIdempotentKey(idempotentKey);
        if (existing != null) {
            log.info("设备命令幂等命中，复用既有命令: id={}, idempotentKey={}",
                    existing.getId(), idempotentKey);
            return existing;
        }
        return enqueueCommand(productId, deviceName, commandKey, commandType, payload,
                approvalBizId, idempotentKey);
    }

    /**
     * 入队实现：只落库，不做外部 I/O（设备在线补发由既有发送路径负责）。
     * <p>唯一键冲突（并发同键）时回读既有命令，保证同一业务动作只有一条命令记录。</p>
     */
    private IotDeviceCommand enqueueCommand(String productId, String deviceName,
                                            String commandKey, String commandType,
                                            String payload, String approvalBizId,
                                            String idempotentKey) {
        IotDevice device = getByProductAndDeviceName(productId, deviceName);
        if (device == null) {
            // 调度线程（预约到点下发）无登录态：按幂等键前缀识别预约来源并回退显式租户解析
            if (idempotentKey != null && idempotentKey.startsWith("RESERVATION:")) {
                try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                             com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
                    device = baseMapper.selectOne(
                            com.baomidou.mybatisplus.core.toolkit.Wrappers.<IotDevice>lambdaQuery()
                                    .eq(IotDevice::getProductId, productId)
                                    .eq(IotDevice::getDeviceName, deviceName)
                                    .eq(IotDevice::getDeleted, 0)
                                    .last("LIMIT 1"));
                }
            }
        }
        if (device == null) {
            throw new BaseException(404, "设备不存在: productId=" + productId
                    + ", deviceName=" + deviceName);
        }
        if (commandKey == null || commandKey.isBlank()) {
            throw new BaseException(400, "commandKey 不能为空");
        }
        if (commandType == null || commandType.isBlank()) {
            commandType = "PROPERTY";
        }

        IotDeviceCommand command = new IotDeviceCommand();
        command.setTenantId(device.getTenantId());
        command.setProductId(productId);
        command.setDeviceName(deviceName);
        command.setDeviceKey(device.getDeviceKey());
        command.setCommandType(commandType);
        command.setCommandKey(commandKey);
        command.setSemanticMode("DEFERRED");
        command.setPayload(payload);
        command.setStatus("QUEUED");
        command.setIdempotentKey(idempotentKey);
        command.setExpiryTime(LocalDateTime.now().plusHours(24));
        command.setRetryCount(0);
        command.setApprovalBizId(approvalBizId);
        try {
            commandMapper.insert(command);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            IotDeviceCommand winner = commandMapper.selectByIdempotentKey(idempotentKey);
            if (winner != null) {
                log.info("设备命令并发同键，复用既有命令: id={}, idempotentKey={}",
                        winner.getId(), idempotentKey);
                return winner;
            }
            throw duplicate;
        }

        log.info("设备命令已入队: id={}, productId={}, deviceName={}, commandKey={}, idempotentKey={}",
                command.getId(), productId, deviceName, commandKey, idempotentKey);

        return command;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceCommand reportResult(Long commandId, String status, String result) {
        IotDeviceCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new BaseException(404, "命令不存在: " + commandId);
        }
        if (status == null || !REPORTABLE_STATUS.contains(status)) {
            throw new BaseException(400, "结果状态只能是 SUCCESS / FAILED");
        }
        // P62 分级执行 S4 安全合同（G4）：本端点是运维回写原通道，不是独立核实通道——
        // 结果未知（UNKNOWN）只能经独立授权人工核实（iot:command:verify + 可信依据）收敛；
        // 已确定结果（SUCCESS/FAILED/EXPIRED）不得被任何入口覆盖（冲突走受控回执留审计）。
        if ("UNKNOWN".equals(command.getStatus())) {
            throw new BaseException(409, "结果未知的命令须经独立授权人工核实通道收敛，"
                    + "不接受运维回写（缺少 iot:command:verify 时无法补依据）");
        }
        if ("SUCCESS".equals(command.getStatus()) || "FAILED".equals(command.getStatus())
                || "EXPIRED".equals(command.getStatus())) {
            throw new BaseException(409, "命令已是确定终态（" + command.getStatus()
                    + "），不接受结果覆盖");
        }
        command.setStatus(status);
        // R8c：result 是设备可控文本（可能携带堆栈帧、绝对路径、超长噪声），
        // 与 MQTT ingest / 连接失败等设备侧输入同口径：DiagnosticText 清洗限长后落库，
        // 授权运维仍可按分类摘要定位，但原始噪声不进存储与页面。
        command.setResult(com.sw.ck.common.trace.DiagnosticText.sanitize(result, 2000));
        commandMapper.updateById(command);
        log.info("设备命令结果已回写: id={}, status={}", commandId, status);
        return command;
    }

    @Override
    public List<IotDeviceCommand> listCommands(String productId, String deviceName) {
        return commandMapper.selectList(
                Wrappers.<IotDeviceCommand>lambdaQuery()
                        .eq(IotDeviceCommand::getProductId, productId)
                        .eq(IotDeviceCommand::getDeviceName, deviceName)
                        .orderByDesc(IotDeviceCommand::getCreateTime));
    }

    @Override
    public List<IotDeviceCommand> findByApprovalBizId(Long tenantId, String approvalBizId) {
        if (approvalBizId == null || approvalBizId.isBlank()) {
            return List.of();
        }
        // 跨模块回查按显式租户边界执行（挂起线程租户，避免登录态缺失/错位）
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            return commandMapper.selectList(
                    Wrappers.<IotDeviceCommand>lambdaQuery()
                            .eq(IotDeviceCommand::getTenantId, tenantId)
                            .eq(IotDeviceCommand::getApprovalBizId, approvalBizId)
                            .orderByAsc(IotDeviceCommand::getId));
        }
    }

    @Override
    public IotDeviceCommand getCommand(Long commandId) {
        return commandMapper.selectById(commandId);
    }

    @Override
    public void validatePublishedFunction(Long tenantId, String deviceKey, String commandType, String commandKey) {
        com.sw.ck.iot.api.IotDeviceFacade.DeviceTarget target = resolveDeviceTargetByKey(tenantId, deviceKey);
        if (target == null) {
            throw new BaseException(404, "设备目标已失效或跨租户: deviceKey=" + deviceKey);
        }
        // 沿设备行 product_ref_id → 产品 → 已发布物模型的真实权威链解析，不按冻结值放行
        IotDevice device;
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            device = baseMapper.selectOne(Wrappers.<IotDevice>lambdaQuery()
                    .eq(IotDevice::getDeviceKey, deviceKey)
                    .eq(IotDevice::getTenantId, tenantId)
                    .eq(IotDevice::getDeleted, 0)
                    .last("LIMIT 1"));
        }
        if (device == null || device.getProductRefId() == null) {
            throw new BaseException(409, "设备未绑定产品或产品权威缺失，功能校验失败: deviceKey=" + deviceKey);
        }
        IotProduct product = productMapper.selectById(device.getProductRefId());
        if (product == null || !tenantId.equals(product.getTenantId())
                || product.getPublishedModelId() == null) {
            throw new BaseException(409, "设备产品未发布物模型，无法校验功能: productId=" + target.productId());
        }
        IotThingModel model = thingModelMapper.selectById(product.getPublishedModelId());
        if (model == null || !"PUBLISHED".equals(model.getStatus())
                || !tenantId.equals(model.getTenantId())) {
            throw new BaseException(409, "已发布物模型不存在或未处于发布态");
        }
        String effectiveType = commandType == null || commandType.isBlank() ? "PROPERTY" : commandType;
        String arrayKey = switch (effectiveType) {
            case "PROPERTY" -> "properties";
            case "ACTION" -> "actions";
            default -> throw new BaseException(400, "未知功能类型: " + effectiveType);
        };
        if (commandKey == null || commandKey.isBlank()) {
            throw new BaseException(400, "功能键不能为空");
        }
        JSONObject content;
        try {
            content = JSON.parseObject(model.getContentJson());
        } catch (Exception e) {
            throw new BaseException(409, "物模型内容不可解析，功能校验失败");
        }
        JSONArray arr = content == null ? null : content.getJSONArray(arrayKey);
        boolean declared = arr != null && arr.stream().anyMatch(p -> commandKey.equals(
                ((JSONObject) p).getString("id")));
        if (!declared) {
            throw new BaseException(404, "功能未在已发布物模型中声明: " + arrayKey + "." + commandKey);
        }
        log.info("功能权威校验通过: tenantId={}, deviceKey={}, {}.{}, modelVersion={}",
                tenantId, deviceKey, arrayKey, commandKey, model.getModelVersion());
    }

    /**
     * 获取当前租户 ID（从 SecurityContext 中提取）。
     */
    private Long getCurrentTenantId() {
        LoginUser current = LoginUserHolder.get();
        return current == null ? null : current.getTenantId();
    }
}

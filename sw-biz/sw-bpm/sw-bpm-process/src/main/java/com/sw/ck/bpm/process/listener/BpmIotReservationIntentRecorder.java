package com.sw.ck.bpm.process.listener;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.mapper.BpmInstanceMapper;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.iot.api.IotCommandReservationFacade;
import com.sw.ck.iot.api.IotDeviceFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 流程成功结束后的一次性 IoT 预约意图登记器（P63 §4.1）。
 * <p>
 * 仅 {@code PROCESS_APPROVED}（成功终态）触发；驳回/撤回/废弃零预约副作用。
 * 读当前定义的 {@code iot_device_action_json}：{@code deliveryMode=RESERVATION} 时，
 * 在审批同一事务内按幂等身份（租户, 实例）创建预约意图——意图持久化失败即审批整体回滚
 * （fail closed），不出现已报告完成而无持久预约意图。预约时刻/设备/参数在创建时从
 * 已批准表单数据冻结；显式时区（默认 Asia/Shanghai）解释绝对时刻，不依赖服务器时区。
 * IMMEDIATE（缺省）路径零改动。
 * </p>
 */
@Slf4j
@Component
public class BpmIotReservationIntentRecorder {

    private final BpmInstanceMapper instanceMapper;
    private final BpmProcessDefMapper processDefMapper;
    private final BpmRuntimeFacade bpmRuntimeFacade;
    private final ObjectProvider<IotCommandReservationFacade> reservationFacade;
    private final ObjectProvider<IotDeviceFacade> deviceFacade;

    public BpmIotReservationIntentRecorder(BpmInstanceMapper instanceMapper,
                                           BpmProcessDefMapper processDefMapper,
                                           BpmRuntimeFacade bpmRuntimeFacade,
                                           ObjectProvider<IotCommandReservationFacade> reservationFacade,
                                           ObjectProvider<IotDeviceFacade> deviceFacade) {
        this.instanceMapper = instanceMapper;
        this.processDefMapper = processDefMapper;
        this.bpmRuntimeFacade = bpmRuntimeFacade;
        this.reservationFacade = reservationFacade;
        this.deviceFacade = deviceFacade;
    }

    @EventListener
    public void onProcessApproved(BpmNotifyEvent event) {
        if (event.getTrigger() != BpmNotifyTrigger.PROCESS_APPROVED) {
            return; // 仅成功终态创建预约；驳回/撤回/废弃零副作用
        }
        String processInstanceId = event.getBizId();
        BpmInstance instance = instanceMapper.selectOne(new LambdaQueryWrapper<BpmInstance>()
                .eq(BpmInstance::getProcessInstanceId, processInstanceId)
                .eq(BpmInstance::getDeleted, 0)
                .last("LIMIT 1"));
        if (instance == null) {
            return; // 防御：无实例上下文不造预约
        }
        BpmProcessDef def = processDefMapper.selectOne(new LambdaQueryWrapper<BpmProcessDef>()
                .eq(BpmProcessDef::getProcessKey, instance.getProcessDefKey())
                .eq(BpmProcessDef::getDeleted, 0)
                .last("LIMIT 1"));
        if (def == null || def.getIotDeviceActionJson() == null || def.getIotDeviceActionJson().isBlank()) {
            return; // 未配置设备动作：零预约
        }
        JSONObject config;
        try {
            config = JSON.parseObject(def.getIotDeviceActionJson());
        } catch (Exception e) {
            throw new BaseException(500, "设备动作配置非法 JSON: " + instance.getProcessDefKey());
        }
        if (!Boolean.TRUE.equals(config.getBoolean("enabled"))
                || !"RESERVATION".equalsIgnoreCase(config.getString("deliveryMode"))) {
            return; // 立即下发（缺省）或未启用：既有路径，不产生预约
        }
        JSONObject reservation = config.getJSONObject("reservation");
        if (reservation == null) {
            throw failClosed(processInstanceId, "RESERVATION 配置缺少 reservation 块");
        }
        IotCommandReservationFacade facade = reservationFacade.getIfAvailable();
        if (facade == null) {
            throw failClosed(processInstanceId, "IoT 预约门面未装配，预约意图无法持久化");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("预约意图登记发生在无事务上下文: processInstanceId={}", processInstanceId);
        }

        Map<String, Object> variables = bpmRuntimeFacade.getProcessVariables(processInstanceId)
                .orElseThrow(() -> failClosed(processInstanceId, "流程变量不可读，无法冻结预约要素"));
        Map<String, Object> formData = variables.get("formData") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();

        // —— 设备目标冻结 ——
        IotDeviceFacade.DeviceTarget target = resolveDeviceTarget(config, formData, variables,
                event.getTenantId(), processInstanceId);

        // —— 预约时刻冻结（显式时区；歧义/不存在时间在提交侧已拒绝，此处防御再验） ——
        String timezoneId = reservation.getString("timezoneId") == null
                || reservation.getString("timezoneId").isBlank()
                ? "Asia/Shanghai" : reservation.getString("timezoneId");
        ZoneId zone;
        try {
            zone = ZoneId.of(timezoneId);
        } catch (Exception e) {
            throw failClosed(processInstanceId, "预约时区非法: " + timezoneId);
        }
        Object dueRaw = formData.get(reservation.getString("dueField"));
        if (dueRaw == null || String.valueOf(dueRaw).isBlank()) {
            throw failClosed(processInstanceId, "表单预约时间字段为空: "
                    + reservation.getString("dueField"));
        }
        ZonedDateTime due;
        try {
            LocalDateTime local = LocalDateTime.parse(String.valueOf(dueRaw).trim()
                    .replace(' ', 'T'));
            java.util.List<java.time.ZoneOffset> validOffsets = zone.getRules().getValidOffsets(local);
            if (validOffsets.isEmpty()) {
                // 时钟前拨产生的不存在时间：拒绝并给出可纠正提示
                throw failClosed(processInstanceId, "预约时间在时区 " + timezoneId + " 不存在（夏令时间隙），请调整后重新配置");
            }
            if (validOffsets.size() > 1) {
                // 时钟回拨产生的歧义时间：拒绝而非暗选其一
                throw failClosed(processInstanceId, "预约时间在时区 " + timezoneId + " 存在歧义，请调整后重新配置");
            }
            due = local.atZone(zone);
        } catch (java.time.format.DateTimeParseException e) {
            throw failClosed(processInstanceId, "预约时间格式非法（应为 YYYY-MM-DD HH:mm[:ss]）: "
                    + reservation.getString("dueField"));
        }
        int lateWindowSeconds = reservation.getIntValue("lateWindowSeconds") > 0
                ? reservation.getIntValue("lateWindowSeconds") : 60;
        if (lateWindowSeconds > 3600) {
            throw failClosed(processInstanceId, "允许迟到窗口超出上限（1—3600 秒）");
        }

        // —— 参数冻结 ——
        String paramField = config.getString("paramField");
        Object param = paramField == null ? null : formData.get(paramField);
        String payload = param == null ? "{}" : JSON.toJSONString(param);

        String commandKey = config.getString("commandKey") == null
                ? "iot_action" : config.getString("commandKey");
        Optional<Long> reservationId = facade.createIntent(event.getTenantId(), processInstanceId,
                instance.getProcessDefKey(), instance.getDefVersion(), instance.getFormKey(),
                instance.getBusinessKey(), target.deviceKey(), target.productId(),
                target.deviceName(), commandKey, "PROPERTY", payload,
                LocalDateTime.ofInstant(due.toInstant(), ZoneId.of("UTC")), timezoneId,
                String.valueOf(dueRaw).trim(), lateWindowSeconds);
        log.info("一次性预约意图已冻结: reservationId={}, processInstanceId={}, dueUtc={}, tz={}, window={}s, inTx={}",
                reservationId.orElse(null), processInstanceId,
                LocalDateTime.ofInstant(due.toInstant(), ZoneId.of("UTC")), timezoneId,
                lateWindowSeconds, TransactionSynchronizationManager.isActualTransactionActive());
    }

    private IotDeviceFacade.DeviceTarget resolveDeviceTarget(JSONObject config, Map<String, Object> formData,
                                                             Map<String, Object> variables, Long tenantId,
                                                             String processInstanceId) {
        IotDeviceFacade deviceFacade = this.deviceFacade.getIfAvailable();
        if (deviceFacade == null) {
            throw failClosed(processInstanceId, "IoT 设备门面未装配，无法冻结设备目标");
        }
        String source = config.getString("deviceSource") == null
                ? "FIXED" : config.getString("deviceSource");
        return switch (source) {
            case "FIXED" -> deviceFacade.resolveDeviceTarget(tenantId, config.getLong("deviceId"))
                    .orElseThrow(() -> failClosed(processInstanceId,
                            "设计时固定设备不存在: deviceId=" + config.getLong("deviceId")));
            case "FORM_FIELD" -> {
                Object v = formData.get(config.getString("deviceField"));
                if (v == null || String.valueOf(v).isBlank()) {
                    throw failClosed(processInstanceId,
                            "表单字段缺少设备标识: " + config.getString("deviceField"));
                }
                // 稳定身份=deviceKey；product/name 在到点认领时按 key 权威解析并重核有效性
                yield new IotDeviceFacade.DeviceTarget(String.valueOf(v), null, null);
            }
            case "VARIABLE" -> {
                Object v = variables.get(config.getString("variableName"));
                if (v == null || String.valueOf(v).isBlank()) {
                    throw failClosed(processInstanceId,
                            "流程变量缺少设备标识: " + config.getString("variableName"));
                }
                yield new IotDeviceFacade.DeviceTarget(String.valueOf(v), null, null);
            }
            default -> throw failClosed(processInstanceId, "未知设备来源: " + source);
        };
    }

    private BaseException failClosed(String processInstanceId, String reason) {
        // 预约意图无法持久化/要素无法冻结 = 该次成功完成不得单边提交（方向 §4.1 提交边界）
        return new BaseException(500, "预约意图登记失败（审批整体回滚）: processInstanceId="
                + processInstanceId + ", 原因=" + reason);
    }
}

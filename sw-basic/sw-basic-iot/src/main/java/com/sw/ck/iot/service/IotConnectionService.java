package com.sw.ck.iot.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.trace.DiagnosticText;
import com.sw.ck.iot.config.IotCipherProperties;
import com.sw.ck.iot.dto.IotConnectResultDTO;
import com.sw.ck.iot.dto.IotConnectionDTO;
import com.sw.ck.iot.dto.IotConnectionTestResultDTO;
import com.sw.ck.iot.entity.IotConnection;
import com.sw.ck.iot.entity.IotTopic;
import com.sw.ck.iot.mapper.IotConnectionMapper;
import com.sw.ck.iot.mapper.IotTopicMapper;
import com.sw.ck.iot.mqtt.MqttBrokerManager;

import jakarta.annotation.PostConstruct;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLException;

import lombok.extern.log4j.Log4j2;
import com.sw.ck.iot.entity.IotDirectionEnum;
/**
 * IoT 连接配置服务。
 * <p>
 * 凭证只写不读：保存/轮换加密入库，列表与详情仅返回脱敏占位。
 * 连接测试区分 DNS/网络不可达、认证失败、TLS 失败、协议失败与成功。
 * </p>
 */
@Service
@Log4j2
public class IotConnectionService {

    public static final String TYPE_TENCENT = "TENCENT";
    public static final String TYPE_MQTT = "MQTT";

    /** 凭证脱敏占位（对外统一返回，不暴露密文/明文）。 */
    public static final String PASSWORD_MASK = "******";

    @Autowired
    private IotConnectionMapper connectionMapper;
    @Autowired
    private IotCipherProperties cipherProperties;
    private AesGcmCipher cipher;
    @Autowired
    private MqttBrokerManager mqttBrokerManager;
    @Autowired
    private IotTopicMapper topicMapper;
    @Autowired(required = false)
    private IotAuditService auditService;

    /** 连接测试中的瞬时客户端（不进常驻连接池）。 */
    private final Map<Long, Boolean> testing = new ConcurrentHashMap<>();

    @PostConstruct
    void initCipher() {
        this.cipher = new AesGcmCipher(cipherProperties.getCipherKey());
    }

    public IotConnection getById(Long id) {
        return connectionMapper.selectById(id);
    }

    public List<IotConnection> list() {
        return connectionMapper.selectList(new LambdaQueryWrapper<>());
    }

    /**
     * 新增连接配置（密码加密入库）。
     */
    public IotConnection create(IotConnection conn, String plainPassword) {
        validate(conn);
        Long count = connectionMapper.selectCount(new LambdaQueryWrapper<IotConnection>()
                .eq(IotConnection::getCode, conn.getCode()));
        if (count != null && count > 0) {
            throw new IllegalArgumentException("连接标识已存在: " + conn.getCode());
        }
        if (plainPassword != null && !plainPassword.isBlank()) {
            conn.setPasswordCipher(cipher.encrypt(plainPassword));
        }
        conn.setId(null);
        connectionMapper.insert(conn);
        return conn;
    }

    /**
     * 更新连接配置；密码为空时保留原密文，不回读明文。
     */
    public IotConnection update(Long id, IotConnection patch, String plainPassword) {
        IotConnection existing = connectionMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("连接不存在: id=" + id);
        }
        validate(patch);
        patch.setId(id);
        if (plainPassword != null && !plainPassword.isBlank()) {
            patch.setPasswordCipher(cipher.encrypt(plainPassword));
        } else {
            patch.setPasswordCipher(existing.getPasswordCipher());
        }
        connectionMapper.updateById(patch);
        // 配置变更后断开常驻客户端，下次使用时按新配置重建
        mqttBrokerManager.shutdown(id);
        return connectionMapper.selectById(id);
    }

    /**
     * 凭证轮换（只写）。
     */
    public void rotatePassword(Long id, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("新口令不能为空");
        }
        IotConnection existing = connectionMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("连接不存在: id=" + id);
        }
        IotConnection patch = new IotConnection();
        patch.setId(id);
        patch.setPasswordCipher(cipher.encrypt(newPassword));
        connectionMapper.updateById(patch);
        mqttBrokerManager.shutdown(id);
    }

    /**
     * 启用/停用；停用时断开常驻客户端。
     */
    public void changeEnabled(Long id, boolean enabled) {
        IotConnection patch = new IotConnection();
        patch.setId(id);
        patch.setEnabled(enabled ? 1 : 0);
        connectionMapper.updateById(patch);
        if (!enabled) {
            mqttBrokerManager.shutdown(id);
        } else {
            connect(id);
        }
    }

    /**
     * 建立常驻连接并恢复订阅（幂等；失败抛出分类信息）。
     */
    public IotConnectResultDTO connect(Long id) {
        IotConnection conn = connectionMapper.selectById(id);
        if (conn == null) {
            throw new IllegalArgumentException("连接不存在: id=" + id);
        }
        if (!TYPE_MQTT.equals(conn.getConnType())) {
            throw new IllegalStateException("仅自建 MQTT 连接支持建立常驻连接");
        }
        if (conn.getEnabled() == null || conn.getEnabled() != 1) {
            throw new IllegalStateException("连接已停用");
        }
        conn.setPlainPassword(decryptPassword(conn));
        List<IotTopic> subscriptions =
                topicMapper.selectList(new LambdaQueryWrapper<IotTopic>()
                        .eq(IotTopic::getConnId, id)
                        .eq(IotTopic::getEnabled, 1)
                        .in(IotTopic::getDirection, IotDirectionEnum.UP.getCode(), IotDirectionEnum.BOTH.getCode()));
        try {
            mqttBrokerManager.ensureStarted(conn, subscriptions);
            IotConnection healthPatch = new IotConnection();
            healthPatch.setId(id);
            healthPatch.setHealthStatus("HEALTHY");
            healthPatch.setLastCheckTime(LocalDateTime.now());
            healthPatch.setLastCheckResult("CONNECT: 常驻连接已建立");
            connectionMapper.updateById(healthPatch);
            IotConnectResultDTO result = new IotConnectResultDTO();
            result.setConnected(true);
            result.setSubscriptions(subscriptions.size());
            return result;
        } catch (Exception e) {
            // 按 Paho 结构化 reason code 分类认证失败。
            String category = mqttBrokerManager.isAuthenticationFailure(e)
                    ? "AUTH_FAILED" : "PROTOCOL_FAILED";
            IotConnection healthPatch = new IotConnection();
            healthPatch.setId(id);
            healthPatch.setHealthStatus("UNHEALTHY");
            healthPatch.setLastCheckTime(LocalDateTime.now());
            healthPatch.setLastCheckResult("CONNECT: " + category + " "
                    + DiagnosticText.sanitize(e.getMessage(), 300));
            connectionMapper.updateById(healthPatch);
            throw new IllegalStateException("连接失败（" + category + "）："
                    + DiagnosticText.sanitize(e.getMessage(), 200), e);
        }
    }

    public void delete(Long id) {
        mqttBrokerManager.shutdown(id);
        connectionMapper.deleteById(id);
    }

    /**
     * 连接测试：真实建立一次 MQTT 连接并立即断开，按异常类型分类。
     *
     * @return 分类结果（SUCCESS / DNS_UNREACHABLE / NETWORK_UNREACHABLE / AUTH_FAILED /
     *         TLS_FAILED / PROTOCOL_FAILED / UNSUPPORTED）
     */
    public IotConnectionTestResultDTO testConnection(Long id) {
        IotConnection conn = connectionMapper.selectById(id);
        if (conn == null) {
            throw new IllegalArgumentException("连接不存在: id=" + id);
        }
        String category;
        String detail;
        if (TYPE_TENCENT.equals(conn.getConnType())) {
            category = "UNSUPPORTED";
            detail = "腾讯连接由腾讯 Provider 探测，未配置腾讯账号时标记为未配置/不可联调";
        } else {
            long begin = System.currentTimeMillis();
            conn.setPlainPassword(decryptPassword(conn));
            String[] outcome = mqttBrokerManager.testConnect(conn);
            long cost = System.currentTimeMillis() - begin;
            category = outcome[0];
            detail = outcome[1] + " (cost=" + cost + "ms)";
        }
        IotConnection patch = new IotConnection();
        patch.setId(id);
        patch.setHealthStatus("SUCCESS".equals(category) ? "HEALTHY" : "UNHEALTHY");
        patch.setLastCheckTime(LocalDateTime.now());
        patch.setLastCheckResult(category + ": " + detail);
        connectionMapper.updateById(patch);
        IotConnectionTestResultDTO result = new IotConnectionTestResultDTO();
        result.setCategory(category);
        result.setDetail(detail);
        result.setHealthStatus(patch.getHealthStatus());
        if (auditService != null) {
            auditService.recordAction(conn.getTenantId(), null, "system:iot-connection-test",
                    "CONNECTION_TEST", "CONNECTION", String.valueOf(id), category,
                    UUID.randomUUID().toString(), "healthStatus=" + patch.getHealthStatus());
        }
        return result;
    }

    /**
     * 解密口令（仅服务端内部使用，禁止写入任何响应/日志）。
     */
    public String decryptPassword(IotConnection conn) {
        if (conn.getPasswordCipher() == null || conn.getPasswordCipher().isBlank()) {
            return null;
        }
        return cipher.decrypt(conn.getPasswordCipher());
    }

    /**
     * 对外视图：凭证脱敏。
     */
    public IotConnectionDTO toVO(IotConnection conn) {
        IotConnectionDTO vo = JSON.parseObject(JSON.toJSONString(conn), IotConnectionDTO.class);
        vo.setPasswordMasked(PASSWORD_MASK);
        vo.setHasPassword(conn.getPasswordCipher() != null && !conn.getPasswordCipher().isBlank());
        return vo;
    }

    private void validate(IotConnection conn) {
        if (conn.getCode() == null || conn.getCode().isBlank()) {
            throw new IllegalArgumentException("连接标识不能为空");
        }
        if (conn.getName() == null || conn.getName().isBlank()) {
            throw new IllegalArgumentException("连接名称不能为空");
        }
        if (!TYPE_MQTT.equals(conn.getConnType()) && !TYPE_TENCENT.equals(conn.getConnType())) {
            throw new IllegalArgumentException("连接类型仅支持 TENCENT / MQTT: " + conn.getConnType());
        }
        if (TYPE_MQTT.equals(conn.getConnType())) {
            if (conn.getHost() == null || conn.getHost().isBlank()) {
                throw new IllegalArgumentException("MQTT 主机不能为空");
            }
            if (conn.getPort() == null || conn.getPort() <= 0 || conn.getPort() > 65535) {
                throw new IllegalArgumentException("MQTT 端口非法: " + conn.getPort());
            }
        }
    }

    private static String classify(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof UnknownHostException) {
                return "DNS_UNREACHABLE";
            }
            if (cur instanceof MqttException mqttEx) {
                int code = mqttEx.getReasonCode();
                if (code == MqttException.REASON_CODE_FAILED_AUTHENTICATION
                        || code == MqttException.REASON_CODE_NOT_AUTHORIZED) {
                    return "AUTH_FAILED";
                }
                if (code == MqttException.REASON_CODE_CLIENT_EXCEPTION
                        && cur.getCause() instanceof SSLException) {
                    return "TLS_FAILED";
                }
            }
            cur = cur.getCause();
        }
        // 网络/协议兜底：按异常名分类，避免误报成功
        String name = e.getClass().getSimpleName();
        if (name.contains("SSL") || name.contains("Tls")) {
            return "TLS_FAILED";
        }
        if (name.contains("Timeout") || name.contains("Connect")) {
            return "NETWORK_UNREACHABLE";
        }
        return "PROTOCOL_FAILED";
    }

    /**
     * 供 MqttBrokerManager 回调的异常分类（包内复用）。
     */
    static String classifyError(Throwable e) {
        return classify(e);
    }
}

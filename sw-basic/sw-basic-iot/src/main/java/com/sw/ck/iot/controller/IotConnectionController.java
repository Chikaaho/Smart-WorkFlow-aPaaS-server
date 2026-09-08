package com.sw.ck.iot.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.iot.entity.IotConnection;
import com.sw.ck.iot.service.IotConnectionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

/**
 * 连接配置控制器：新增、编辑、测试、启停、凭证轮换、健康状态。
 * <p>
 * 凭证仅写：任何响应只包含脱敏占位，不返回密文或明文。
 * </p>
 */
@RestController
@PreAuthorize("@ss.hasPermi('iot:connection:manage')")
@RequestMapping("/iot/connections")
public class IotConnectionController {

    private final IotConnectionService connectionService;

    public IotConnectionController(IotConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(connectionService.list().stream().map(connectionService::toVO).toList());
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        IotConnection conn = connectionService.getById(id);
        if (conn == null) {
            return R.fail(404, "连接配置不存在: id=" + id);
        }
        return R.ok(connectionService.toVO(conn));
    }

    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        IotConnection conn = new IotConnection();
        conn.setCode((String) body.get("code"));
        conn.setName((String) body.get("name"));
        conn.setConnType((String) body.get("connType"));
        conn.setEnabled(body.get("enabled") == null ? 1 : toInt(body.get("enabled"), 1));
        conn.setHost((String) body.get("host"));
        conn.setPort(toInt(body.get("port"), null));
        conn.setUseTls(toInt(body.get("useTls"), 0));
        conn.setClientIdPrefix((String) body.get("clientIdPrefix"));
        conn.setUsername((String) body.get("username"));
        conn.setKeepalive(toInt(body.get("keepalive"), 60));
        conn.setCleanSession(toInt(body.get("cleanSession"), 1));
        conn.setReconnectMinSec(toInt(body.get("reconnectMinSec"), 1));
        conn.setReconnectMaxSec(toInt(body.get("reconnectMaxSec"), 60));
        conn.setRegion((String) body.get("region"));
        conn.setEndpoint((String) body.get("endpoint"));
        conn.setSecretRef((String) body.get("secretRef"));
        String password = (String) body.get("password");
        IotConnection saved = connectionService.create(conn, password);
        return R.ok(connectionService.toVO(saved));
    }

    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        IotConnection patch = new IotConnection();
        patch.setName((String) body.get("name"));
        patch.setConnType((String) body.get("connType"));
        patch.setEnabled(toInt(body.get("enabled"), 1));
        patch.setHost((String) body.get("host"));
        patch.setPort(toInt(body.get("port"), null));
        patch.setUseTls(toInt(body.get("useTls"), 0));
        patch.setClientIdPrefix((String) body.get("clientIdPrefix"));
        patch.setUsername((String) body.get("username"));
        patch.setKeepalive(toInt(body.get("keepalive"), 60));
        patch.setCleanSession(toInt(body.get("cleanSession"), 1));
        patch.setReconnectMinSec(toInt(body.get("reconnectMinSec"), 1));
        patch.setReconnectMaxSec(toInt(body.get("reconnectMaxSec"), 60));
        patch.setRegion((String) body.get("region"));
        patch.setEndpoint((String) body.get("endpoint"));
        patch.setSecretRef((String) body.get("secretRef"));
        IotConnection updated = connectionService.update(id, patch, (String) body.get("password"));
        return R.ok(connectionService.toVO(updated));
    }

    /**
     * 连接测试（区分 DNS/网络不可达、认证失败、TLS 失败、协议失败与成功）。
     */
    @PostMapping("/{id}/test")
    public R<Map<String, Object>> test(@PathVariable Long id) {
        return R.ok(connectionService.testConnection(id));
    }

    /**
     * 建立常驻连接并恢复订阅。
     */
    @PostMapping("/{id}/connect")
    public R<Map<String, Object>> connect(@PathVariable Long id) {
        return R.ok(connectionService.connect(id));
    }

    /**
     * 凭证轮换（只写）。
     */
    @PostMapping("/{id}/rotate")
    public R<Void> rotate(@PathVariable Long id, @RequestBody Map<String, String> body) {
        connectionService.rotatePassword(id, body.get("password"));
        return R.ok();
    }

    @PostMapping("/{id}/enabled/{flag}")
    public R<Void> changeEnabled(@PathVariable Long id, @PathVariable boolean flag) {
        connectionService.changeEnabled(id, flag);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        connectionService.delete(id);
        return R.ok();
    }

    private Integer toInt(Object value, Integer fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}

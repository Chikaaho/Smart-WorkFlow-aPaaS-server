package com.sw.ck.iot.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.service.IotDeviceManageService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 设备管理控制器（新增/编辑/发布/禁用/流程接入开关/连接状态）。
 */
@RestController
@PreAuthorize("@ss.hasPermi('iot:device:manage')")
@RequestMapping("/iot/device-manage")
public class IotDeviceManageController {

    private final IotDeviceManageService deviceManageService;

    public IotDeviceManageController(IotDeviceManageService deviceManageService) {
        this.deviceManageService = deviceManageService;
    }

    @PostMapping
    public R<IotDevice> create(@RequestBody IotDevice device) {
        return R.ok(deviceManageService.create(device));
    }

    @PutMapping("/{id}")
    public R<IotDevice> update(@PathVariable Long id, @RequestBody IotDevice patch) {
        return R.ok(deviceManageService.update(id, patch));
    }

    @PostMapping("/{id}/publish")
    public R<IotDevice> publish(@PathVariable Long id) {
        return R.ok(deviceManageService.publish(id));
    }

    @PostMapping("/{id}/status/{status}")
    public R<IotDevice> changeStatus(@PathVariable Long id, @PathVariable String status) {
        return R.ok(deviceManageService.changeStatus(id, status));
    }

    @PostMapping("/{id}/process-access/{flag}")
    public R<IotDevice> changeProcessAccess(@PathVariable Long id, @PathVariable boolean flag) {
        return R.ok(deviceManageService.changeProcessAccess(id, flag));
    }

    @PostMapping("/{id}/refresh-status")
    public R<String> refreshConnectionStatus(@PathVariable Long id) {
        return R.ok(deviceManageService.refreshConnectionStatus(id));
    }
}

package com.sw.ck.iot.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.common.response.R;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.service.IotDeviceManageService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.sw.ck.iot.vo.IotDeviceVO;

/**
 * 设备管理控制器（新增/编辑/发布/禁用/流程接入开关/连接状态）。
 */
@RestController
@PreAuthorize("@ss.hasPermi('iot:device:manage')")
@RequestMapping("/iot/device-manage")
public class IotDeviceManageController {

    @Autowired
    private IotDeviceManageService deviceManageService;

    @PostMapping
    public R<IotDeviceVO> create(@RequestBody IotDevice device) {
        return R.ok(IotDeviceVO.from(deviceManageService.create(device)));
    }

    @PutMapping("/{id}")
    public R<IotDeviceVO> update(@PathVariable Long id, @RequestBody IotDevice patch) {
        return R.ok(IotDeviceVO.from(deviceManageService.update(id, patch)));
    }

    @PostMapping("/{id}/publish")
    public R<IotDeviceVO> publish(@PathVariable Long id) {
        return R.ok(IotDeviceVO.from(deviceManageService.publish(id)));
    }

    @PostMapping("/{id}/status/{status}")
    public R<IotDeviceVO> changeStatus(@PathVariable Long id, @PathVariable String status) {
        return R.ok(IotDeviceVO.from(deviceManageService.changeStatus(id, status)));
    }

    @PostMapping("/{id}/process-access/{flag}")
    public R<IotDeviceVO> changeProcessAccess(@PathVariable Long id, @PathVariable boolean flag) {
        return R.ok(IotDeviceVO.from(deviceManageService.changeProcessAccess(id, flag)));
    }

    @PostMapping("/{id}/refresh-status")
    public R<String> refreshConnectionStatus(@PathVariable Long id) {
        return R.ok(deviceManageService.refreshConnectionStatus(id));
    }
}

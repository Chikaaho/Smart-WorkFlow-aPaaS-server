package com.sw.ck.iot.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.common.response.R;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.service.IotDeviceService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import lombok.extern.log4j.Log4j2;
import com.sw.ck.iot.vo.IotDeviceVO;
import com.sw.ck.iot.vo.IotDeviceCommandVO;
/**
 * IoT 设备控制控制器。
 * <p>
 * 设备身份固定为 {@code productId + deviceName}，所有接口均按此组合定位设备。
 * 提供：设备注册、设备列表/详情、命令下发（延迟生效语义）、
 * 执行结果查询与设备结果回写。
 * </p>
 */
@RestController
@RequestMapping("/iot/devices")
@Log4j2
public class IotDeviceController {

    @Autowired
    private IotDeviceService iotDeviceService;

    /**
     * 注册设备（productId + deviceName 组合唯一）。
     */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('iot:device:manage')")
    public R<IotDeviceVO> register(@RequestBody RegisterDeviceRequest request) {
        IotDevice device = new IotDevice();
        device.setProductId(request.getProductId());
        device.setDeviceName(request.getDeviceName());
        device.setDeviceKey(request.getDeviceKey());
        device.setName(request.getName());
        device.setDeviceType(request.getDeviceType());
        return R.ok(IotDeviceVO.from(iotDeviceService.register(device)));
    }

    /**
     * 设备列表（当前租户）。
     */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('iot:view')")
    public R<List<IotDeviceVO>> list() {
        return R.ok(iotDeviceService.lambdaQuery().list() == null ? List.of() : iotDeviceService.lambdaQuery().list().stream().map(IotDeviceVO::from).toList());
    }

    /**
     * 设备详情（按 productId + deviceName 查询）。
     */
    @GetMapping("/{productId}/{deviceName}")
    @PreAuthorize("@ss.hasPermi('iot:view')")
    public R<IotDeviceVO> detail(@PathVariable String productId,
                               @PathVariable String deviceName) {
        IotDevice device = iotDeviceService.getByProductAndDeviceName(productId, deviceName);
        if (device == null) {
            return R.fail(404, "设备不存在: productId=" + productId + ", deviceName=" + deviceName);
        }
        return R.ok(IotDeviceVO.from(device));
    }

    /**
     * 下发控制命令（延迟生效语义：命令入队，设备离线时等待上线补发）。
     */
    @PostMapping("/{productId}/{deviceName}/commands")
    @PreAuthorize("@ss.hasPermi('iot:device:manage')")
    public R<IotDeviceCommandVO> dispatch(@PathVariable String productId,
                                        @PathVariable String deviceName,
                                        @RequestBody DispatchCommandRequest request) {
        log.info("下发设备命令: productId={}, deviceName={}, commandKey={}, commandType={}",
                productId, deviceName, request.getCommandKey(), request.getCommandType());
        return R.ok(IotDeviceCommandVO.from(iotDeviceService.dispatchCommand(
                productId, deviceName, request.getCommandKey(),
                request.getCommandType(), request.getPayload(), request.getApprovalBizId())));
    }

    /**
     * 设备命令列表（含执行结果）。
     */
    @GetMapping("/{productId}/{deviceName}/commands")
    @PreAuthorize("@ss.hasPermi('iot:view')")
    public R<List<IotDeviceCommandVO>> commands(@PathVariable String productId,
                                              @PathVariable String deviceName) {
        return R.ok(iotDeviceService.listCommands(productId, deviceName) == null ? List.of() : iotDeviceService.listCommands(productId, deviceName).stream().map(IotDeviceCommandVO::from).toList());
    }

    /**
     * 设备回写命令执行结果（真实设备回调链路）。
     */
    @PostMapping("/commands/{commandId}/result")
    @PreAuthorize("@ss.hasPermi('iot:device:manage')")
    public R<IotDeviceCommandVO> reportResult(@PathVariable Long commandId,
                                            @RequestBody CommandResultRequest request) {
        return R.ok(IotDeviceCommandVO.from(iotDeviceService.reportResult(commandId, request.getStatus(), request.getResult())));
    }

    @Data
    public static class RegisterDeviceRequest {
        @NotBlank(message = "productId 不能为空")
        private String productId;
        @NotBlank(message = "deviceName 不能为空")
        private String deviceName;
        private String deviceKey;
        @NotBlank(message = "设备名称不能为空")
        private String name;
        private String deviceType;
    }

    @Data
    public static class DispatchCommandRequest {
        @NotBlank(message = "commandKey 不能为空")
        private String commandKey;
        private String commandType;
        private String payload;
        private String approvalBizId;
    }

    @Data
    public static class CommandResultRequest {
        @NotBlank(message = "status 不能为空")
        private String status;
        private String result;
    }
}

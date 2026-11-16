package com.sw.ck.iot.provider;

import com.sw.ck.iot.config.TencentCloudProperties;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.iotexplorer.v20190423.IotexplorerClient;
import com.tencentcloudapi.iotexplorer.v20190423.models.*;

import lombok.extern.log4j.Log4j2;
/**
 * 腾讯云 IoT Explorer 设备控制提供者。
 * <p>
 * 使用腾讯云 Java SDK 实现设备状态查询、属性下发和行为调用。
 * </p>
 */
@Log4j2
public class TencentCloudProvider implements DeviceControlProvider {

    private final TencentCloudProperties properties;
    private final IotexplorerClient client;

    public TencentCloudProvider(TencentCloudProperties properties) {
        this.properties = properties;

        // 初始化腾讯云 SDK 客户端（对齐 Demo：TencentIotCloudClient.init）
        Credential credential = new Credential(properties.getSecretId(), properties.getSecretKey());
        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint(properties.getEndpoint());
        httpProfile.setReqMethod("POST");
        // 对齐 Demo：不设置 ConnTimeout 和 ReadTimeout，使用 SDK 默认值

        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);

        this.client = new IotexplorerClient(credential, properties.getRegion(), clientProfile);
        log.info("[TencentCloudProvider] 初始化完成，区域：{}", properties.getRegion());
    }

    @Override
    public String queryDeviceStatus(String productId, String deviceName) {
        try {
            DescribeDeviceRequest request = new DescribeDeviceRequest();
            request.setProductId(productId);
            request.setDeviceName(deviceName);

            DescribeDeviceResponse response = client.DescribeDevice(request);
            DeviceInfo device = response.getDevice();

            if (device == null) {
                return "not_active";
            }

            // 腾讯云 Status: 0=离线, 1=在线, 2=未激活
            Long status = device.getStatus();
            if (status == null) {
                return "offline";
            }

            String statusStr = switch (status.intValue()) {
                case 0 -> "offline";
                case 1 -> "online";
                case 2 -> "not_active";
                default -> "offline";
            };

            log.debug("腾讯云查询设备状态: productId={}, deviceName={}, status={}", productId, deviceName, statusStr);
            return statusStr;
        } catch (TencentCloudSDKException e) {
            log.error("腾讯云查询设备状态失败: productId={}, deviceName={}, error={}",
                    productId, deviceName, e.getMessage());
            return "error";
        }
    }

    @Override
    public DeviceControlResult controlDeviceData(String productId, String deviceName, String propertyJson) {
        try {
            ControlDeviceDataRequest request = new ControlDeviceDataRequest();
            request.setProductId(productId);
            request.setDeviceName(deviceName);
            request.setData(propertyJson);

            ControlDeviceDataResponse response = client.ControlDeviceData(request);

            String requestId = response.getRequestId();
            log.info("腾讯云属性下发成功: productId={}, deviceName={}, requestId={}",
                    productId, deviceName, requestId);
            return DeviceControlResult.success(requestId);
        } catch (TencentCloudSDKException e) {
            log.error("腾讯云属性下发失败: productId={}, deviceName={}, error={}",
                    productId, deviceName, e.getMessage());
            return DeviceControlResult.failure("腾讯云 API 调用失败: " + e.getMessage());
        }
    }

    @Override
    public DeviceControlResult callDeviceActionSync(String productId, String deviceName,
                                                    String actionId, String inputJson) {
        try {
            CallDeviceActionSyncRequest request = new CallDeviceActionSyncRequest();
            request.setProductId(productId);
            request.setDeviceName(deviceName);
            request.setActionId(actionId);
            request.setInputParams(inputJson);

            CallDeviceActionSyncResponse response = client.CallDeviceActionSync(request);

            String requestId = response.getRequestId();
            String clientToken = response.getClientToken();
            String outputParams = response.getOutputParams();
            String status = response.getStatus();

            log.info("腾讯云行为调用成功: productId={}, deviceName={}, actionId={}, requestId={}, status={}",
                    productId, deviceName, actionId, requestId, status);
            return DeviceControlResult.success(requestId, clientToken, outputParams);
        } catch (TencentCloudSDKException e) {
            log.error("腾讯云行为调用失败: productId={}, deviceName={}, actionId={}, error={}",
                    productId, deviceName, actionId, e.getMessage());
            return DeviceControlResult.failure("腾讯云 API 调用失败: " + e.getMessage());
        }
    }
}

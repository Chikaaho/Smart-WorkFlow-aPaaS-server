package com.sw.ck.iot.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotProduct;
import com.sw.ck.iot.entity.IotThingModel;
import com.sw.ck.iot.service.IotDeviceManageService;
import com.sw.ck.iot.service.IotProductService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

/**
 * 产品、物模型与设备管理控制器（P21 平台化）。
 */
@RestController
@PreAuthorize("@ss.hasPermi('iot:product:manage')")
@RequestMapping("/iot/products")
public class IotProductController {

    private final IotProductService productService;
    private final IotDeviceManageService deviceManageService;

    public IotProductController(IotProductService productService,
                                IotDeviceManageService deviceManageService) {
        this.productService = productService;
        this.deviceManageService = deviceManageService;
    }

    // ---------------- 产品 ----------------

    @GetMapping
    public R<List<IotProduct>> list() {
        return R.ok(productService.list());
    }

    @GetMapping("/{id}")
    public R<IotProduct> detail(@PathVariable Long id) {
        return R.ok(productService.getById(id));
    }

    @PostMapping
    public R<IotProduct> create(@RequestBody ProductRequest request) {
        return R.ok(productService.create(request.toProduct(), request.getModel()));
    }

    @PutMapping("/{id}")
    public R<IotProduct> update(@PathVariable Long id, @RequestBody ProductRequest request) {
        return R.ok(productService.update(id, request.toProduct()));
    }

    // ---------------- 物模型 ----------------

    @GetMapping("/{id}/models")
    public R<List<IotThingModel>> modelVersions(@PathVariable Long id) {
        return R.ok(productService.modelVersions(id));
    }

    @PostMapping("/{id}/models")
    public R<IotThingModel> saveModelDraft(@PathVariable Long id,
                                           @RequestBody ProductRequest request) {
        return R.ok(productService.saveModelDraft(id, request.getModel()));
    }

    @PostMapping("/{id}/models/publish")
    public R<IotThingModel> publishModel(@PathVariable Long id) {
        return R.ok(productService.publishModel(id));
    }

    // ---------------- 设备 ----------------

    @GetMapping("/devices/eligible")
    public R<List<IotDevice>> processEligibleDevices() {
        return R.ok(deviceManageService.listProcessEligible());
    }

    // ---------------- 请求体 ----------------

    public static class ProductRequest {
        private String code;
        private String name;
        private Long connId;
        private String connType;
        private String description;
        private com.alibaba.fastjson2.JSONObject model;

        public IotProduct toProduct() {
            IotProduct product = new IotProduct();
            product.setCode(code);
            product.setName(name);
            product.setConnId(connId);
            product.setConnType(connType == null ? "MQTT" : connType);
            product.setDescription(description);
            return product;
        }

        public com.alibaba.fastjson2.JSONObject getModel() {
            return model;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public void setName(String name) {
            this.name = name;
        }

        public void setConnId(Long connId) {
            this.connId = connId;
        }

        public void setConnType(String connType) {
            this.connType = connType;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public void setModel(com.alibaba.fastjson2.JSONObject model) {
            this.model = model;
        }
    }
}

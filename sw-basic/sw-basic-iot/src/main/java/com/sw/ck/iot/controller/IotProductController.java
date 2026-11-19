package com.sw.ck.iot.controller;

import com.alibaba.fastjson2.JSONObject;
import com.sw.ck.common.response.R;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotProduct;
import com.sw.ck.iot.entity.IotThingModel;
import com.sw.ck.iot.service.IotConnectionService;
import com.sw.ck.iot.service.IotDeviceManageService;
import com.sw.ck.iot.service.IotProductService;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.sw.ck.iot.vo.IotDeviceVO;
import com.sw.ck.iot.vo.IotThingModelVO;
import com.sw.ck.iot.vo.IotProductVO;

/**
 * 产品、物模型与设备管理控制器（P21 平台化）。
 */
@RestController
@PreAuthorize("@ss.hasPermi('iot:product:manage')")
@RequestMapping("/iot/products")
public class IotProductController {

    @Autowired
    private IotProductService productService;
    @Autowired
    private IotDeviceManageService deviceManageService;

    // ---------------- 产品 ----------------

    @GetMapping
    public R<List<IotProductVO>> list() {
        return R.ok(productService.list() == null ? List.of() : productService.list().stream().map(IotProductVO::from).toList());
    }

    @GetMapping("/{id}")
    public R<IotProductVO> detail(@PathVariable Long id) {
        return R.ok(IotProductVO.from(productService.getById(id)));
    }

    @PostMapping
    public R<IotProductVO> create(@RequestBody ProductRequest request) {
        return R.ok(IotProductVO.from(productService.create(request.toProduct(), request.getModel())));
    }

    @PutMapping("/{id}")
    public R<IotProductVO> update(@PathVariable Long id, @RequestBody ProductRequest request) {
        return R.ok(IotProductVO.from(productService.update(id, request.toProduct())));
    }

    // ---------------- 物模型 ----------------

    @GetMapping("/{id}/models")
    public R<List<IotThingModelVO>> modelVersions(@PathVariable Long id) {
        return R.ok(productService.modelVersions(id) == null ? List.of() : productService.modelVersions(id).stream().map(IotThingModelVO::from).toList());
    }

    @PostMapping("/{id}/models")
    public R<IotThingModelVO> saveModelDraft(@PathVariable Long id,
                                           @RequestBody ProductRequest request) {
        return R.ok(IotThingModelVO.from(productService.saveModelDraft(id, request.getModel())));
    }

    @PostMapping("/{id}/models/publish")
    public R<IotThingModelVO> publishModel(@PathVariable Long id) {
        return R.ok(IotThingModelVO.from(productService.publishModel(id)));
    }

    // ---------------- 设备 ----------------

    @GetMapping("/devices/eligible")
    public R<List<IotDeviceVO>> processEligibleDevices() {
        return R.ok(deviceManageService.listProcessEligible() == null ? List.of() : deviceManageService.listProcessEligible().stream().map(IotDeviceVO::from).toList());
    }

    // ---------------- 请求体 ----------------

    public static class ProductRequest {
        private String code;
        private String name;
        private Long connId;
        private String connType;
        private String description;
        private JSONObject model;

        public IotProduct toProduct() {
            IotProduct product = new IotProduct();
            product.setCode(code);
            product.setName(name);
            product.setConnId(connId);
            product.setConnType(connType == null ? IotConnectionService.TYPE_MQTT : connType);
            product.setDescription(description);
            return product;
        }

        public JSONObject getModel() {
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

        public void setModel(JSONObject model) {
            this.model = model;
        }
    }
}

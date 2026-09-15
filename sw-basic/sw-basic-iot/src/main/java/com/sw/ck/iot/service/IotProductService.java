package com.sw.ck.iot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotProduct;
import com.sw.ck.iot.entity.IotThingModel;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.mapper.IotProductMapper;
import com.sw.ck.iot.mapper.IotThingModelMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 产品与物模型服务：草稿 → 校验 → 发布；设备与流程引用已发布版本。
 */
@Service
public class IotProductService {

    private final IotProductMapper productMapper;
    private final IotThingModelMapper thingModelMapper;
    private final IotDeviceMapper deviceMapper;

    public IotProductService(IotProductMapper productMapper,
                             IotThingModelMapper thingModelMapper,
                             IotDeviceMapper deviceMapper) {
        this.productMapper = productMapper;
        this.thingModelMapper = thingModelMapper;
        this.deviceMapper = deviceMapper;
    }

    public List<IotProduct> list() {
        return productMapper.selectList(new LambdaQueryWrapper<>());
    }

    public IotProduct getById(Long id) {
        return productMapper.selectById(id);
    }

    /**
     * 新建产品（同时创建物模型 v1 草稿）。
     */
    public IotProduct create(IotProduct product, JSONObject modelContent) {
        Long count = productMapper.selectCount(new LambdaQueryWrapper<IotProduct>()
                .eq(IotProduct::getCode, product.getCode()));
        if (count != null && count > 0) {
            throw new IllegalArgumentException("产品编码已存在: " + product.getCode());
        }
        product.setId(null);
        product.setModelStatus("DRAFT");
        product.setPublishedModelId(null);
        productMapper.insert(product);
        if (modelContent != null) {
            saveModelDraft(product.getId(), 1, modelContent);
        }
        return product;
    }

    public IotProduct update(Long id, IotProduct patch) {
        patch.setId(id);
        patch.setModelStatus(null);
        patch.setPublishedModelId(null);
        productMapper.updateById(patch);
        return productMapper.selectById(id);
    }

    public List<IotThingModel> modelVersions(Long productId) {
        return thingModelMapper.selectList(new LambdaQueryWrapper<IotThingModel>()
                .eq(IotThingModel::getProductId, productId)
                .orderByDesc(IotThingModel::getModelVersion));
    }

    /**
     * 保存当前草稿物模型内容（结构校验：属性/事件/行为三类能力）。
     */
    public IotThingModel saveModelDraft(Long productId, JSONObject content) {
        IotProduct product = require(productId);
        List<IotThingModel> versions = modelVersions(productId);
        int nextVersion = versions.isEmpty() ? 1 : versions.get(0).getModelVersion() + 1;
        validateModel(content);
        return saveModelDraft(productId, nextVersion, content);
    }

    /**
     * 发布当前草稿物模型；影响流程引用时生成新版本而非静默改历史。
     */
    public IotThingModel publishModel(Long productId) {
        IotProduct product = require(productId);
        List<IotThingModel> versions = modelVersions(productId);
        if (versions.isEmpty()) {
            throw new IllegalStateException("产品没有物模型版本");
        }
        IotThingModel draft = versions.get(0);
        if (!"DRAFT".equals(draft.getStatus())) {
            throw new IllegalStateException("当前版本已发布: v" + draft.getModelVersion());
        }
        validateModel(JSON.parseObject(draft.getContentJson()));
        IotThingModel patch = new IotThingModel();
        patch.setId(draft.getId());
        patch.setStatus("PUBLISHED");
        patch.setPublishTime(LocalDateTime.now());
        thingModelMapper.updateById(patch);

        IotProduct productPatch = new IotProduct();
        productPatch.setId(productId);
        productPatch.setModelStatus("PUBLISHED");
        productPatch.setPublishedModelId(draft.getId());
        productMapper.updateById(productPatch);
        return thingModelMapper.selectById(draft.getId());
    }

    /**
     * 流程可选设备列表（已发布 + 流程接入开启 + 连接启用 + 有权限过滤由调用方补充）。
     */
    public List<IotDevice> listProcessEligibleDevices() {
        return deviceMapper.selectList(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getManageStatus, "PUBLISHED")
                .eq(IotDevice::getProcessAccessEnabled, 1));
    }

    private IotThingModel saveModelDraft(Long productId, int version, JSONObject content) {
        validateModel(content);
        IotThingModel model = new IotThingModel();
        model.setProductId(productId);
        model.setModelVersion(version);
        model.setStatus("DRAFT");
        model.setContentJson(content.toJSONString());
        thingModelMapper.insert(model);
        return model;
    }

    /**
     * 物模型结构校验：properties/events/actions 三类，行为带输入/输出。
     */
    void validateModel(JSONObject content) {
        if (content == null) {
            throw new IllegalArgumentException("物模型内容不能为空");
        }
        JSONArray properties = content.getJSONArray("properties");
        JSONArray events = content.getJSONArray("events");
        JSONArray actions = content.getJSONArray("actions");
        if (properties == null || properties.isEmpty()) {
            throw new IllegalArgumentException("物模型至少需要一个属性");
        }
        for (int i = 0; i < properties.size(); i++) {
            JSONObject p = properties.getJSONObject(i);
            requireId(p, "properties[" + i + "]");
            if (p.getString("dataType") == null) {
                throw new IllegalArgumentException("属性缺少 dataType: " + p.getString("id"));
            }
        }
        for (int i = 0; events == null || i < events.size(); i++) {
            JSONObject e = events.getJSONObject(i);
            requireId(e, "events[" + i + "]");
        }
        for (int i = 0; actions == null || i < actions.size(); i++) {
            JSONObject a = actions.getJSONObject(i);
            requireId(a, "actions[" + i + "]");
            if (a.getJSONObject("input") == null) {
                throw new IllegalArgumentException("行为缺少输入定义: " + a.getString("id"));
            }
            if (a.getJSONObject("output") == null) {
                throw new IllegalArgumentException("行为缺少输出定义: " + a.getString("id"));
            }
        }
    }

    private void requireId(JSONObject item, String location) {
        if (item == null || item.getString("id") == null || item.getString("id").isBlank()) {
            throw new IllegalArgumentException(location + " 缺少稳定标识 id");
        }
    }

    private IotProduct require(Long id) {
        IotProduct product = productMapper.selectById(id);
        if (product == null) {
            throw new IllegalArgumentException("产品不存在: id=" + id);
        }
        return product;
    }
}

package com.sw.ck.iot.api;

import java.util.List;

/**
 * IoT 规则表单契约校验器（iot 定义接口，bpm-process 提供实现）。
 * <p>
 * 依赖方向：bpm-process → iot-api / form-api 合法；iot 不依赖 biz 层。
 * </p>
 */
public interface IotFormContractChecker {

    /**
     * 按已发布流程模板绑定表单校验字段映射契约。
     *
     * @param processTemplateKey 流程模板 key
     * @param formMappingJson    表单字段映射 JSON（[{field,source,required}]）
     * @return 错误列表；空列表 = 通过
     */
    List<String> checkMapping(String processTemplateKey, String formMappingJson);
}

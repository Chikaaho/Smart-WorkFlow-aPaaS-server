package com.sw.ck.iot.api;

import java.util.List;
import java.util.Optional;

/**
 * IoT 规则表单契约校验器（iot 定义接口，bpm-process 提供实现）。
 * <p>
 * 依赖方向：bpm-process → sw-basic-iot-api / form-api 合法；IoT 实现模块不依赖 biz 层。
 * 接口位于契约模块，实现由 BPM 提供、由 Bootstrap 装配，不形成 Maven 循环依赖。
 * </p>
 *
 * <h3>present / empty 两层语义</h3>
 * <ul>
 *   <li><b>{@code Optional.of(空列表)}</b>：校验合法执行且无错误（通过）。
 *       合法零错误是"有值的结果"，不得用 {@code Optional.empty()} 代替。</li>
 *   <li><b>{@code Optional.of(非空列表)}</b>：校验合法执行并发现错误。</li>
 *   <li><b>{@code Optional.empty()}</b>：无法裁决（如流程模板 key 空白等适用条件不存在），
 *       调用方必须按 fail closed 处理，不得当作通过。</li>
 * </ul>
 */
public interface IotFormContractChecker {

    /**
     * 按已发布流程模板绑定表单校验字段映射契约。
     *
     * @param processTemplateKey 流程模板 key
     * @param formMappingJson    表单字段映射 JSON（[{field,source,required}]）
     * @return 错误列表（可为空列表 = 通过）；无法裁决时为 {@code Optional.empty()}
     */
    Optional<List<String>> checkMapping(String processTemplateKey, String formMappingJson);
}

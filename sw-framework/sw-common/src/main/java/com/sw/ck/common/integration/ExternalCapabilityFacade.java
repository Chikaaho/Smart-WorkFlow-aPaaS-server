package com.sw.ck.common.integration;

import java.util.List;

/**
 * 受控外部能力门面（跨模块最小接缝）。
 * <p>
 * 表单模块只依赖本接口，不直接读取外部工具表，也不接触白名单 URL。
 * 实现必须按调用方传入的租户解析能力，且只返回启用状态的目标；
 * 未登记、跨租户、停用或未分类的能力一律不可见。
 * </p>
 */
public interface ExternalCapabilityFacade {

    /** 返回指定租户中启用且已分类的外部能力（不含 URL/方法）。 */
    List<ExternalCapability> listEnabled(Long tenantId);

    /** 按稳定标识解析指定租户中启用且已分类的能力；不存在返回 null。 */
    ExternalCapability findEnabled(Long tenantId, String capabilityId);

    /** 调用已解析能力，返回原始响应体；实现负责超时、方法与响应体积上限。 */
    String invoke(ExternalCapability capability, String inputJson);
}

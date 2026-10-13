package com.sw.ck.system.api.delegate;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * P64 阶段Ⅱ（A07）岗位委托门面 —— 后台「源岗位→受托岗位」通用关系的运行期解析入口。
 * <p>
 * 配置维护（增删改查/启停）归 system-biz 控制器；BPM 等消费方只经本门面解析。
 * 解析顺序（主方向 §3.4）：源岗位 → 有效委托关系 → 受托岗位 → 实际办理人；
 * 精确部门范围优先于显式组织默认范围；最多 4 跳；未命中关系时使用源岗位有效人员，
 * 命中但目标失效/空缺/超跳/循环时进入可诊断异常，不自动回退。
 * </p>
 */
public interface PositionDelegateFacade {

    /**
     * 解析岗位实际办理人（委托感知）。
     *
     * @param tenantId        租户 ID
     * @param sourcePostCodes 源岗位编码集合（按来源顺序，多岗位结果并集去重）
     * @param deptId          业务部门上下文（可空；精确部门委托仅对匹配部门生效）
     * @return present = 解析结果（含实际人员与逐跳审计链）；
     *         empty = 岗位编码/租户上下文缺失，查询未执行
     * @throws com.sw.ck.common.exception.BaseException 委托链超过 4 跳、循环或受托岗位缺有效人员
     *         （{@code DELEGATE_RESOLVE_FAILED} 语义；调用方按可诊断失败处置，不自动回退）
     */
    Optional<ResolvedPostActors> resolvePostActors(Long tenantId,
                                                   Collection<String> sourcePostCodes,
                                                   Long deptId);

    /** 一次委托跳（审计：源岗位 → 受托岗位，范围与生效部门）。 */
    record DelegateHop(String sourcePostCode, String targetPostCode,
                       String scopeType, Long deptId) implements Serializable {
    }

    /** 解析结果：实际办理人（并集去重）与命中委托跳链（来源可追溯）。 */
    record ResolvedPostActors(List<Long> userIds, List<DelegateHop> hops) implements Serializable {
    }
}

package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.entity.BpmResourcePolicy;

import java.util.List;

/**
 * 资源策略服务（P62 资源保障）：版本化策略的创建/启用/停用/停新受理。
 * <p>
 * 启用是唯一的「开启新受理」动作，前置启用检查（方向合同）：保留份额自洽、
 * 每租户上限不越过全局上限、异步节点必需消费者（Flowable 异步执行器、批量命令
 * 处理器）可用、预算与连接池明显不相容拒绝——任一不满足明确拒绝并留审计，
 * 失败原因不泄露秘密。启用只影响新受理；在途对象按受理冻结的策略版本结算。
 * </p>
 */
public interface ResourcePolicyService {

    /** 创建新策略版本（DRAFT，默认关闭）。 */
    BpmResourcePolicy create(BpmResourcePolicy policy);

    /** 启用检查 + 启用（同租户旧 ACTIVE 版本自动退役；只影响新受理）。 */
    BpmResourcePolicy enable(Long id, String operatorRemark);

    /** 停用：不再做资源会计与准入裁决（新受理回到无策略行为）。 */
    BpmResourcePolicy disable(Long id);

    /** 停新受理开关：TRUE 时新受理明确拒绝，已有工作按原合同结算。 */
    BpmResourcePolicy stopAcceptance(Long id, boolean stop);

    BpmResourcePolicy getById(Long id);

    /** 全部版本（运维列表，按版本倒序）。 */
    List<BpmResourcePolicy> listAll();

    /** 启用检查（不落库；供启用前预检与运行画像回读复用）。 */
    List<String> checkEnablement(BpmResourcePolicy policy);
}

package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 生产轻流程判定（P62 资源保障）。
 * <p>
 * 受理时按绑定流程定义的已发布图判定：图含 TXN_ACTION 节点即生产轻流程
 * （与 {@code LightProcessGraphValidator} 同一判定口径），FLOW_START 占用完成点冻结为
 * TARGET_ACTION_DONE（目标动作完成才释放）；普通流程冻结为 FLOW_STARTED（启动即释放）。
 * 以已发布定义的 graph_json 实时判定，不缓存——受理冻结字段必须与实际图一致，
 * 不接受发布/改版后的陈旧判定。
 * </p>
 */
@Component
public class LightProcessClassifier {

    private static final Logger log = LoggerFactory.getLogger(LightProcessClassifier.class);
    private static final String TXN_ACTION_MARKER = "\"TXN_ACTION\"";
    private static final String STATUS_PUBLISHED = "PUBLISHED";

    private final BpmProcessDefMapper processDefMapper;

    public LightProcessClassifier(BpmProcessDefMapper processDefMapper) {
        this.processDefMapper = processDefMapper;
    }

    /**
     * 判定绑定流程是否生产轻流程。
     *
     * @return true=轻流程（完成点 TARGET_ACTION_DONE）；false=普通流程；
     *         定义不存在/未发布时按普通流程处理并留警告（占用释放偏保守方向由
     *         引擎事实对账兜底，不因判定缺失拒绝合法受理）
     */
    public boolean isLightProcess(Long tenantId, String processDefKey) {
        if (processDefKey == null || processDefKey.isBlank()) {
            return false;
        }
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            BpmProcessDef def = processDefMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<BpmProcessDef>lambdaQuery()
                            .eq(BpmProcessDef::getTenantId, tenantId)
                            .eq(BpmProcessDef::getProcessKey, processDefKey)
                            .eq(BpmProcessDef::getStatus, STATUS_PUBLISHED)
                            .orderByDesc(BpmProcessDef::getDefVersion)
                            .last("LIMIT 1"));
            if (def == null || def.getGraphJson() == null) {
                log.warn("轻流程判定缺少已发布定义: tenant={}, processDefKey={}", tenantId, processDefKey);
                return false;
            }
            return def.getGraphJson().contains(TXN_ACTION_MARKER);
        }
    }
}

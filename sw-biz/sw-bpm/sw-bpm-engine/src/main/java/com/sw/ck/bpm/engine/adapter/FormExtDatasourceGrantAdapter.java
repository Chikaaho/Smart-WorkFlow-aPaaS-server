package com.sw.ck.bpm.engine.adapter;

import com.sw.ck.bpm.engine.service.ExternalDatasourceGrantService;
import com.sw.ck.form.api.port.ExtDatasourceGrantPort;

import java.util.Set;

/**
 * form 模块 {@link ExtDatasourceGrantPort} 的 BPM 适配实现（F020）。
 * <p>
 * 授权策略（数据源管理员旁路、按用户/角色授权匹配）统一收敛在
 * {@link ExternalDatasourceGrantService}，form 侧只消费布尔判定与可引用集合；
 * 登录上下文由服务侧从 {@code LoginUserHolder} 读取，未登录一律拒绝。
 * </p>
 */
public class FormExtDatasourceGrantAdapter implements ExtDatasourceGrantPort {

    private final ExternalDatasourceGrantService grantService;

    public FormExtDatasourceGrantAdapter(ExternalDatasourceGrantService grantService) {
        this.grantService = grantService;
    }

    @Override
    public boolean isReferenceAllowed(Long datasourceId) {
        return grantService.isReferenceAllowed(datasourceId);
    }

    @Override
    public Set<Long> referenceAllowedDatasourceIds() {
        return grantService.referenceAllowedDatasourceIds();
    }
}

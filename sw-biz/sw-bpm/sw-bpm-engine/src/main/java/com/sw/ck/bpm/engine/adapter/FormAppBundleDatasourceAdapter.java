package com.sw.ck.bpm.engine.adapter;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.engine.entity.ExternalDatasource;
import com.sw.ck.bpm.engine.mapper.ExternalDatasourceMapper;
import com.sw.ck.form.api.bundle.FormAppBundleDatasourcePort;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link FormAppBundleDatasourcePort} 实现（0.2.2 F2，D022-13）：
 * 应用导入时外部数据源按名称匹配当前租户，只返回 id（连接信息与密码不参与导入包）。
 * 租户条件由全局 TenantLine 拦截器注入；多个同名取 id 最小。
 */
@Component
public class FormAppBundleDatasourceAdapter implements FormAppBundleDatasourcePort {

    private final ExternalDatasourceMapper datasourceMapper;

    public FormAppBundleDatasourceAdapter(ExternalDatasourceMapper datasourceMapper) {
        this.datasourceMapper = datasourceMapper;
    }

    @Override
    public Optional<Long> findDatasourceIdByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return datasourceMapper.selectList(Wrappers.<ExternalDatasource>lambdaQuery()
                        .eq(ExternalDatasource::getName, name.trim())
                        .orderByAsc(ExternalDatasource::getId))
                .stream()
                .findFirst()
                .map(ExternalDatasource::getId);
    }

    @Override
    public Optional<String> findDatasourceNameById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return datasourceMapper.selectList(Wrappers.<ExternalDatasource>lambdaQuery()
                        .eq(ExternalDatasource::getId, id)
                        .orderByAsc(ExternalDatasource::getId))
                .stream()
                .findFirst()
                .map(ExternalDatasource::getName);
    }
}

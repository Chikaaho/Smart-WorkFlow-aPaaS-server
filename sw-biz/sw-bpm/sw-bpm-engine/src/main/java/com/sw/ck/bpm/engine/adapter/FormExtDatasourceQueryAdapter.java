package com.sw.ck.bpm.engine.adapter;

import com.sw.ck.bpm.api.dto.SqlExecutionResult;
import com.sw.ck.bpm.engine.datasource.ExternalDatasourceUnavailableException;
import com.sw.ck.bpm.engine.executor.SqlExecutor;
import com.sw.ck.form.api.dto.ExtQueryResult;
import com.sw.ck.form.api.port.ExtDatasourceQueryPort;

import java.util.Optional;

/**
 * form 模块 {@link ExtDatasourceQueryPort} 的 BPM 适配实现（I2）。
 * <p>
 * 复用唯一的受控执行引擎 {@link SqlExecutor}（独立连接池、SELECT-only 校验、
 * maxRows/queryTimeout/只读连接、执行审计），不建立第二套执行权威；
 * 仅做 {@link SqlExecutionResult} → form 侧 {@link ExtQueryResult} 的形状映射。
 * </p>
 * <p>
 * 目标缺失与请求非法分开表达：数据源未登记/已停用（{@link ExternalDatasourceUnavailableException}）
 * → {@code Optional.empty()}，查询未执行；SQL 非法与执行失败/行数超限继续抛原异常。
 * </p>
 */
public class FormExtDatasourceQueryAdapter implements ExtDatasourceQueryPort {

    private final SqlExecutor sqlExecutor;

    public FormExtDatasourceQueryAdapter(SqlExecutor sqlExecutor) {
        this.sqlExecutor = sqlExecutor;
    }

    @Override
    public Optional<ExtQueryResult> executeQuery(Long datasourceId, String sql, Long operatorId, String operatorName) {
        SqlExecutionResult result;
        try {
            result = sqlExecutor.execute(datasourceId, sql, operatorId, operatorName);
        } catch (ExternalDatasourceUnavailableException e) {
            // 数据源不存在/已停用：目标缺失，未执行查询
            return Optional.empty();
        }
        return Optional.of(ExtQueryResult.builder()
                .columns(result.getColumns())
                .rows(result.getRows())
                .rowCount(result.getRowCount())
                .build());
    }
}

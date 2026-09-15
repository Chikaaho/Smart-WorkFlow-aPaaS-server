package com.sw.ck.form.api.port;

import com.sw.ck.form.api.dto.ExtQueryResult;

/**
 * 受控外部数据源查询端口（I2，方向 §4.3）。
 * <p>
 * form 模块持有查询契约注册表（{@code sw_form_ext_query}），但执行统一经本端口
 * 委托给具备独立连接池、SELECT-only 校验、行数/超时限制与完整审计的外部数据源
 * 执行引擎（bpm-engine 适配实现），不维护第二套执行权威。
 * </p>
 * <p>
 * 实现方契约：仅允许单条只读 SELECT；强制 maxRows/queryTimeout/只读连接；
 * 密码/连接串不出现在结果、日志或异常中；执行留审计。
 * </p>
 */
public interface ExtDatasourceQueryPort {

    /**
     * 执行一次受控只读查询。
     *
     * @param datasourceId 外部数据源 ID（管理员登记、启用状态）
     * @param sql          服务端注册表中的查询 SQL（绝不来自客户端）
     * @param operatorId   操作人 ID（审计）
     * @param operatorName 操作人用户名（审计）
     * @return 查询结果
     * @throws IllegalArgumentException 数据源不存在/停用、SQL 非法时
     * @throws RuntimeException         执行失败时
     */
    ExtQueryResult executeQuery(Long datasourceId, String sql, Long operatorId, String operatorName);
}

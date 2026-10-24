package com.sw.ck.form.api.port;

import java.util.Set;

/**
 * 受控外部数据源引用授权端口（F020）。
 * <p>
 * direction-020：设计人员仅能引用已授权的稳定数据源/查询标识。form 模块持有
 * 查询契约注册表并执行引用校验，授权策略（数据源管理员旁路、按用户/角色授权匹配）
 * 由具备数据源登记表与权限上下文的 bpm-engine 适配实现，form 侧不复制第二套授权权威。
 * </p>
 * <p>
 * 授权语义：仅约束<b>设计期引用</b>（契约登记、发布绑定校验、可引用清单过滤）；
 * 已发布表单的运行期查询不回查授权（运行准入由既有 form:data:query 等权限承担），
 * 撤权不中断已发布表单，只阻止新的引用。
 * </p>
 */
public interface ExtDatasourceGrantPort {

    /**
     * 当前登录主体是否允许引用指定外部数据源。
     *
     * @param datasourceId 数据源 ID（登记表主键）
     * @return true = 允许引用；false = 未授权、数据源不存在或已删除（不区分两种拒绝原因）
     */
    boolean isReferenceAllowed(Long datasourceId);

    /**
     * 当前登录主体可引用的数据源 ID 集合，供可引用契约清单过滤。
     * 集合可能为空（无任何授权）；管理员旁路策略由实现方决定。
     *
     * @return 允许引用的数据源 ID 集合（非 null）
     */
    Set<Long> referenceAllowedDatasourceIds();
}

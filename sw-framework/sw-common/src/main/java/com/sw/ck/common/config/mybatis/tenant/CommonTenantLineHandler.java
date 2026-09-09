package com.sw.ck.common.config.mybatis.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.sw.ck.common.constant.CommonConstants;
import com.sw.ck.common.security.LoginContextProvider;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;

public class CommonTenantLineHandler implements TenantLineHandler {

    private static final Logger log = LoggerFactory.getLogger(CommonTenantLineHandler.class);

    private final TenantProperties tenantProperties;
    private final LoginContextProvider loginContextProvider;

    public CommonTenantLineHandler(TenantProperties tenantProperties, LoginContextProvider loginContextProvider) {
        this.tenantProperties = tenantProperties;
        this.loginContextProvider = loginContextProvider;
    }

    @Override
    public Expression getTenantId() {
        Long tenantId = loginContextProvider.getTenantId();
        long value = tenantId != null ? tenantId : Long.parseLong(CommonConstants.SUPER_TENANT_ID);
        return new LongValue(value);
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    /**
     * 租户隔离仅作用于主库（master），扩展数据源的表不追加 tenant_id 条件。
     * <p>
     * {@link DynamicDataSourceContextHolder#peek()} 返回当前线程绑定的数据源 key；
     * 无 {@code @DS} 注解时栈为空、兜底为 null，此时按主库逻辑处理。
     */
    @Override
    public boolean ignoreTable(String tableName) {
        // 认证身份装载期（登录前无租户上下文）挂起租户行过滤；业务读写不得使用
        if (TenantLineSuspension.isSuspended()) {
            return true;
        }
        String currentDs = DynamicDataSourceContextHolder.peek();
        if (currentDs != null && !"master".equals(currentDs)) {
            log.debug("TenantLineHandler: skip tenant filter on non-master DS '{}', table '{}'", currentDs, tableName);
            return true;
        }
        return tenantProperties.getIgnoreTables().stream().anyMatch(tableName::equalsIgnoreCase);
    }
}

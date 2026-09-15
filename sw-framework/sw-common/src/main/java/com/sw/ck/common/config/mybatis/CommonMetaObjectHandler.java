package com.sw.ck.common.config.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.sw.ck.common.constant.CommonConstants;
import com.sw.ck.common.security.LoginContextProvider;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;
import java.util.function.Consumer;

public class CommonMetaObjectHandler implements MetaObjectHandler {

    private final LoginContextProvider loginContextProvider;

    /**
     * 表单模块 ID 自动填充钩子，由 form 模块通过 {@link #setFormIdFiller} 注册。
     * <p>
     * 默认 no-op，避免 sw-common 对 form 模块的编译期依赖。
     * </p>
     */
    private Consumer<MetaObject> formIdFiller = meta -> {};

    public CommonMetaObjectHandler(LoginContextProvider loginContextProvider) {
        this.loginContextProvider = loginContextProvider;
    }

    /**
     * 设置表单 ID 自动填充器（由 form 模块的 AutoConfiguration 调用）。
     */
    public void setFormIdFiller(Consumer<MetaObject> formIdFiller) {
        this.formIdFiller = formIdFiller;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        strictInsertFill(metaObject, "createTime", LocalDateTime::now, LocalDateTime.class);
        strictInsertFill(metaObject, "updateTime", LocalDateTime::now, LocalDateTime.class);
        strictInsertFill(metaObject, "createBy", this::currentUserId, Long.class);
        strictInsertFill(metaObject, "updateBy", this::currentUserId, Long.class);
        strictInsertFill(metaObject, "tenantId", this::currentTenantId, Long.class);
        strictInsertFill(metaObject, "deleted", () -> 0, Integer.class);
        strictInsertFill(metaObject, "version", () -> 0L, Long.class);
        // 表单模块 ID 自动填充（仅对 FormBaseEntity 生效）
        formIdFiller.accept(metaObject);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateTime", LocalDateTime::now, LocalDateTime.class);
        strictUpdateFill(metaObject, "updateBy", this::currentUserId, Long.class);
    }

    /**
     * 取不到登录态（系统初始化、定时任务等无登录上下文场景）时，降级为系统操作人 {@link CommonConstants#SYSTEM_OPERATOR_ID}。
     */
    private Long currentUserId() {
        Long userId = loginContextProvider.getUserId();
        return userId != null ? userId : CommonConstants.SYSTEM_OPERATOR_ID;
    }

    /**
     * 业务写入租户归属取当前登录态；无登录态时保持 null 交由显式写入方决定
     * （如迁移 seed、系统初始化行自带 tenant_id 默认值 0），不再静默降级超级租户。
     */
    private Long currentTenantId() {
        return loginContextProvider.getTenantId();
    }
}

package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.api.port.FormRecordAccessPort;
import com.sw.ck.form.dynamic.DynamicTableSql;
import com.sw.ck.form.entity.FormAppEntity;
import com.sw.ck.form.entity.FormAppUserEntity;
import com.sw.ck.form.mapper.FormAppMapper;
import com.sw.ck.form.mapper.FormAppUserMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 应用范围访问闸门：表单登记应用归属后，应用可见范围与停用状态对用户侧
 * “新访问/新发起”生效（列表、提交、发起共用同一判定）；记录级读取保留合法历史访问。
 * <p>
 * 判定口径（与方向 §3 一致）：
 * <ul>
 *   <li>表单未绑定应用 → {@link AppAccess#UNRESTRICTED}，不受本闸门约束；</li>
 *   <li>应用不可见（非本租户、不存在、指定集合未命中/空集）→ {@link AppAccess#NOT_VISIBLE}，
 *       按不存在处理，不下发对象名称或数量；</li>
 *   <li>应用可见但已停用 → {@link AppAccess#DISABLED}，阻止新增入口但保留数据和历史；</li>
 *   <li>应用可见且启用 → {@link AppAccess#VISIBLE_ENABLED}。</li>
 * </ul>
 * 记录级读取（详情/更新）不视为新访问：应用不可见/停用时，本人提交的记录
 * （{@code create_by} = 当前用户）或与引用该记录的流程实例存在参与关系
 * （{@link FormRecordAccessPort}）时保留原合法访问，两者都不满足才拒绝。
 * 无登录态（系统上下文）fail-closed 为 {@code NOT_VISIBLE}；所有判定查询失败按不可访问处理。
 * </p>
 */
@Service
public class FormAppAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(FormAppAccessGuard.class);

    /** 应用访问判定结果。 */
    public enum AppAccess {
        /** 表单未绑定应用：本闸门不生效。 */
        UNRESTRICTED,
        /** 应用可见且启用。 */
        VISIBLE_ENABLED,
        /** 应用不可见（按不存在处理）。 */
        NOT_VISIBLE,
        /** 应用可见但已停用。 */
        DISABLED
    }

    private static final String SCOPE_ALL = "ALL";
    private static final String STATUS_ENABLED = "ENABLED";

    private final FormAppMapper appMapper;
    private final FormAppUserMapper appUserMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 流程参与关系 SPI（可选注入；依据模块装配，未注入时记录级回退只剩本人提交通道）。 */
    private FormRecordAccessPort formRecordAccessPort;

    @Autowired(required = false)
    public void setFormRecordAccessPort(FormRecordAccessPort formRecordAccessPort) {
        this.formRecordAccessPort = formRecordAccessPort;
    }

    public FormAppAccessGuard(FormAppMapper appMapper, FormAppUserMapper appUserMapper,
                              JdbcTemplate jdbcTemplate) {
        this.appMapper = appMapper;
        this.appUserMapper = appUserMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 判定表单当前应用归属对当前登录用户的可访问性。 */
    public AppAccess evaluate(String appId) {
        if (appId == null || appId.isBlank()) {
            return AppAccess.UNRESTRICTED;
        }
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null || loginUser.getUserId() == null) {
            // 无登录态无法判定可见范围：fail-closed
            return AppAccess.NOT_VISIBLE;
        }
        FormAppEntity app = appMapper.selectById(appId);
        if (app == null || !isScopeHit(app, loginUser.getUserId())) {
            return AppAccess.NOT_VISIBLE;
        }
        return STATUS_ENABLED.equals(app.getStatus()) ? AppAccess.VISIBLE_ENABLED : AppAccess.DISABLED;
    }

    /**
     * 新访问/新发起前置校验：应用不可见按表单不存在拒绝，停用按应用停用拒绝。
     *
     * @param formKey 表单业务标识（仅用于错误消息，不暴露应用名称）
     * @param appId   表单应用归属（可空 = 未绑定，直接通过）
     */
    public void requireNewBusiness(String formKey, String appId) {
        AppAccess access = evaluate(appId);
        if (access == AppAccess.NOT_VISIBLE) {
            throw new BaseException(FormErrorCode.APP_NOT_FOUND, "表单 '" + formKey + "' 不存在");
        }
        if (access == AppAccess.DISABLED) {
            throw new BaseException(FormErrorCode.APP_DISABLED,
                    "表单 '" + formKey + "' 所属应用已停用，不能新发起或新增数据");
        }
    }

    /**
     * 记录级访问前置校验（详情/更新）：应用不可见/停用时，仅当当前用户与记录存在
     * 合法历史关系（本人提交，或引用该记录的流程实例参与关系）才放行。
     *
     * @param formKey   表单业务标识
     * @param appId     表单应用归属（可空 = 未绑定）
     * @param tableName 表单物理表名（已通过调用方白名单校验）
     * @param recordId  记录 ID
     */
    public void requireRecordAccess(String formKey, String appId, String tableName, String recordId) {
        AppAccess access = evaluate(appId);
        if (!requiresRecordRelationFallback(access)) {
            return;
        }
        if (hasLegalRecordRelation(formKey, tableName, recordId)) {
            log.info("App-scope record relation fallback granted: formKey={}, recordId={}, access={}",
                    formKey, recordId, access);
            return;
        }
        if (access == AppAccess.DISABLED) {
            throw new BaseException(FormErrorCode.APP_DISABLED,
                    "表单 '" + formKey + "' 所属应用已停用，不能新访问");
        }
        throw new BaseException(FormErrorCode.APP_NOT_FOUND, "表单 '" + formKey + "' 不存在");
    }

    /** 非新访问（记录级读取/更新）是否必须落到“本人提交/流程参与”合法关系回退通道。 */
    public boolean requiresRecordRelationFallback(AppAccess access) {
        return access == AppAccess.NOT_VISIBLE || access == AppAccess.DISABLED;
    }

    /**
     * 合法历史访问判定（仅该记录行）：
     * 本人提交（{@code create_by} = 当前用户）或流程参与关系；查询失败 fail-closed。
     */
    private boolean hasLegalRecordRelation(String formKey, String tableName, String recordId) {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null || loginUser.getUserId() == null || loginUser.getTenantId() == null
                || recordId == null || recordId.isBlank() || tableName == null) {
            return false;
        }
        Long tenantId = loginUser.getTenantId();
        Long userId = loginUser.getUserId();
        try {
            String sql = "SELECT 1 AS present FROM " + DynamicTableSql.quote(tableName)
                    + " WHERE \"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ? AND \"create_by\" = ? LIMIT 1";
            List<Map<String, Object>> rows = DynamicTableSql.query(jdbcTemplate, tableName, sql,
                    recordId, tenantId, userId);
            if (rows != null && !rows.isEmpty()) {
                return true;
            }
        } catch (Exception e) {
            log.warn("App-scope record relation check failed: formKey={}, recordId={}", formKey, recordId, e);
            return false;
        }
        return formRecordAccessPort != null
                && formRecordAccessPort.isProcessParticipant(tenantId, userId, formKey, recordId).orElse(false);
    }

    private boolean isScopeHit(FormAppEntity app, Long userId) {
        if (SCOPE_ALL.equals(app.getVisibleScopeType())) {
            return true;
        }
        // SPECIFIED：集合空 = 无人可见
        List<FormAppUserEntity> scopeUsers = appUserMapper.selectList(
                Wrappers.<FormAppUserEntity>lambdaQuery().eq(FormAppUserEntity::getAppId, app.getId()));
        return scopeUsers.stream().anyMatch(member -> userId.equals(member.getUserId()));
    }
}

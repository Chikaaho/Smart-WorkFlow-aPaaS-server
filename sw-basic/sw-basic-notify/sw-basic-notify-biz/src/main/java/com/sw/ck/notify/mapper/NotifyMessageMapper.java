package com.sw.ck.notify.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.notify.entity.NotifyMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 站内信通知 Mapper。
 */
@Mapper
public interface NotifyMessageMapper extends BaseMapperX<NotifyMessage> {

    /**
     * 按幂等键取一条已落库消息。
     * <p>
     * 这里显式限定 {@code LIMIT 1}，避免在重试窗口中由通用 wrapper 的
     * {@code selectOne()} 将并发下的重复行升级为未定义的异常；唯一约束仍由
     * 数据库负责，调用方只把首条已持久化结果作为重放响应。
     * </p>
     */
    @Select("SELECT id, create_time, create_by, update_time, update_by, deleted, tenant_id, version, "
            + "recipient_id, title, content, biz_type, biz_id, is_read, channel, delivery_status, "
            + "external_message_id, failure_reason, idempotency_key "
            + "FROM sw_notify_message "
            + "WHERE idempotency_key = #{idempotencyKey} AND deleted = 0 "
            + "ORDER BY id ASC LIMIT 1")
    NotifyMessage selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /**
     * 按部门ID列表查询当前租户内有效用户ID（排除停用用户）。
     * <p>
     * 手写 tenant_id 条件，使用 @InterceptorIgnore 跳过租户拦截器
     * （本方法查询 sys_user 表，非 sw_notify_message 表）。
     * </p>
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>"
            + "SELECT DISTINCT u.id FROM sys_user u "
            + "WHERE u.deleted = 0 AND u.status = 0 "
            + "AND u.tenant_id = #{tenantId} "
            + "AND u.dept_id IN "
            + "<foreach collection='deptIds' item='deptId' open='(' separator=',' close=')'>"
            + "#{deptId}"
            + "</foreach>"
            + "</script>")
    List<Long> selectActiveUserIdsByDeptIds(@Param("deptIds") List<Long> deptIds,
                                           @Param("tenantId") Long tenantId);

    /**
     * 校验部门对象属于当前租户、未删除且启用（status=0）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>"
            + "SELECT id FROM sys_dept "
            + "WHERE deleted = 0 AND status = 0 "
            + "AND tenant_id = #{tenantId} AND id IN "
            + "<foreach collection='deptIds' item='deptId' open='(' separator=',' close=')'>"
            + "#{deptId}"
            + "</foreach>"
            + "</script>")
    List<Long> selectValidDeptIds(@Param("deptIds") List<Long> deptIds,
                                  @Param("tenantId") Long tenantId);

    /**
     * 按角色code列表查询当前租户内有效用户ID（排除停用用户）。
     * <p>
     * 手写 tenant_id 条件，使用 @InterceptorIgnore 跳过租户拦截器。
     * </p>
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>"
            + "SELECT DISTINCT ur.user_id FROM sys_user_role ur "
            + "INNER JOIN sys_user u ON u.id = ur.user_id AND u.deleted = 0 AND u.status = 0 "
            + "INNER JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0 AND r.status = 1 "
            + "WHERE ur.deleted = 0 AND ur.tenant_id = #{tenantId} "
            + "AND u.tenant_id = #{tenantId} AND r.tenant_id = #{tenantId} "
            + "AND r.code IN "
            + "<foreach collection='roleCodes' item='code' open='(' separator=',' close=')'>"
            + "#{code}"
            + "</foreach>"
            + "</script>")
    List<Long> selectActiveUserIdsByRoleCodes(@Param("roleCodes") List<String> roleCodes,
                                              @Param("tenantId") Long tenantId);

    /**
     * 校验角色对象属于当前租户、未删除且启用（status=1）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>"
            + "SELECT code FROM sys_role "
            + "WHERE deleted = 0 AND status = 1 "
            + "AND tenant_id = #{tenantId} AND code IN "
            + "<foreach collection='roleCodes' item='code' open='(' separator=',' close=')'>"
            + "#{code}"
            + "</foreach>"
            + "</script>")
    List<String> selectValidRoleCodes(@Param("roleCodes") List<String> roleCodes,
                                      @Param("tenantId") Long tenantId);

    /**
     * 按用户ID列表查询当前租户内的有效用户ID（排除停用/已删除/跨租户用户）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>"
            + "SELECT id FROM sys_user "
            + "WHERE deleted = 0 AND status = 0 "
            + "AND tenant_id = #{tenantId} "
            + "AND id IN "
            + "<foreach collection='userIds' item='userId' open='(' separator=',' close=')'>"
            + "#{userId}"
            + "</foreach>"
            + "</script>")
    List<Long> selectValidUserIds(@Param("userIds") List<Long> userIds,
                                 @Param("tenantId") Long tenantId);
}

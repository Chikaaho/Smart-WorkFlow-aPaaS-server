package com.sw.ck.system.api.user;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 用户查询 Facade 接口。
 * <p>
 * 定义于 {@code -api} 模块，由 {@code -biz} 模块实现。
 * 其它模块（如 workflow 的流程审批人候选）需要查询用户时，<strong>仅可</strong>依赖本接口，
 * 禁止直接访问 {@code sys_user} 表或对应的 Mapper。
 * </p>
 */
public interface UserQueryFacade {

    /**
     * 按关键字模糊搜索正常状态用户（匹配 username 或 real_name）。
     *
     * @param keyword 关键字（空白视为列出全部）
     * @param limit   最大返回条数（正数；&lt;=0 时按 1 处理）
     * @return 用户候选选项列表（已按 id 升序，仅含正常状态用户，不含敏感字段）
     */
    List<UserOptionDTO> searchActiveUsers(String keyword, int limit);

    /**
     * 按用户 ID 批量查询展示名（用于审批人/发起人等场景的可读身份回显）。
     *
     * @param ids 用户 ID 集合（空集合返回空 Map）
     * @return id → 展示名（优先 real_name，其次 username）；查不到的 ID 不在结果中
     */
    Map<Long, String> getUserDisplayNames(Collection<Long> ids);

    /** 查询当前租户内启用用户，供流程节点运行期重新校验固定用户配置。 */
    List<Long> findActiveUserIds(Collection<Long> ids);

    /** 显式租户上下文版本，供引擎异步/无登录线程安全解析。 */
    List<Long> findActiveUserIds(Collection<Long> ids, Long tenantId);

    /** 查询当前租户内启用角色的启用成员，供流程节点运行期解析 ROLE 策略。 */
    List<Long> findActiveUserIdsByRoleCodes(Collection<String> roleCodes);

    /** 显式租户上下文版本，供引擎异步/无登录线程安全解析。 */
    List<Long> findActiveUserIdsByRoleCodes(Collection<String> roleCodes, Long tenantId);
}

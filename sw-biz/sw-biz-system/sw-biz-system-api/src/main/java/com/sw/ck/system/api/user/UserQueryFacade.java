package com.sw.ck.system.api.user;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户查询 Facade 接口。
 * <p>
 * 定义于 {@code -api} 模块，由 {@code -biz} 模块实现。
 * 其它模块（如 workflow 的流程审批人候选）需要查询用户时，<strong>仅可</strong>依赖本接口，
 * 禁止直接访问 {@code sys_user} 表或对应的 Mapper。
 * </p>
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：empty 表达查询上下文缺失
 * （查询对象或租户上下文不存在），合法零匹配以 present 的空集合/空 Map 表达，
 * 两者不可互换；调用方不得用 empty 恢复旧的"空集合即失败"语义。
 * </p>
 */
public interface UserQueryFacade {

    /**
     * 按关键字模糊搜索正常状态用户（匹配 username 或 real_name）。
     *
     * @param keyword 关键字（空白视为列出全部）
     * @param limit   最大返回条数（正数；&lt;=0 时按 1 处理）
     * @return present = 用户候选选项列表（已按 id 升序，仅含正常状态用户，不含敏感字段；
     *         空集合表示关键字合法但零匹配）；
     *         empty = 缺少查询上下文（如当前无可用租户范围）时由实现显式表达
     */
    Optional<List<UserOptionDTO>> searchActiveUsers(String keyword, int limit);

    /**
     * 按用户 ID 批量查询展示名（用于审批人/发起人等场景的可读身份回显）。
     *
     * @param ids 用户 ID 集合
     * @return present = id → 展示名（优先 real_name，其次 username）；查不到的 ID 不在结果中，
     *         命中零条时为空 Map（合法零匹配）；
     *         empty = {@code ids} 为 {@code null}，缺少查询对象
     */
    Optional<Map<Long, String>> getUserDisplayNames(Collection<Long> ids);

    /**
     * 查询当前租户内启用用户，供流程节点运行期重新校验固定用户配置。
     *
     * @return present = 命中用户 ID 列表（空列表为合法零匹配）；empty = 查询对象缺失
     *         或当前无登录租户上下文，无法确定租户范围
     */
    Optional<List<Long>> findActiveUserIds(Collection<Long> ids);

    /**
     * 显式租户上下文版本，供引擎异步/无登录线程安全解析。
     *
     * @return present = 命中用户 ID 列表；empty = 查询对象或租户上下文缺失
     */
    Optional<List<Long>> findActiveUserIds(Collection<Long> ids, Long tenantId);

    /**
     * 查询当前租户内启用角色的启用成员，供流程节点运行期解析 ROLE 策略。
     *
     * @return present = 命中用户 ID 列表；empty = 角色集合缺失或当前无登录租户上下文
     */
    Optional<List<Long>> findActiveUserIdsByRoleCodes(Collection<String> roleCodes);

    /**
     * 显式租户上下文版本，供引擎异步/无登录线程安全解析。
     *
     * @return present = 命中用户 ID 列表；empty = 角色集合或租户上下文缺失
     */
    Optional<List<Long>> findActiveUserIdsByRoleCodes(Collection<String> roleCodes, Long tenantId);

    /**
     * 查询指定部门集合的有效负责人（部门正常状态、负责人用户启用且同租户），
     * 供流程节点运行期解析 DEPT_LEADER 策略。
     *
     * @return present = 命中用户 ID 列表；empty = 部门集合或租户上下文缺失
     */
    Optional<List<Long>> findActiveUserIdsByDeptLeaders(Collection<Long> deptIds, Long tenantId);

    /**
     * 按岗位编码查询任职用户（岗位启用、任职行有效、用户启用，同租户），
     * 供流程节点运行期解析 POST 策略。
     *
     * @return present = 命中用户 ID 列表；empty = 岗位编码集合或租户上下文缺失
     */
    Optional<List<Long>> findActiveUserIdsByPostCodes(Collection<String> postCodes, Long tenantId);

    /**
     * 查询指定部门内担任指定岗位的有效用户（部门正常、岗位启用、任职行有效，
     * 同租户），供流程节点运行期解析 DEPT_POST 组合策略。
     *
     * @return present = 命中用户 ID 列表；empty = 部门/岗位/租户上下文缺失
     */
    Optional<List<Long>> findActiveUserIdsByDeptAndPost(Long deptId, String postCode, Long tenantId);
}

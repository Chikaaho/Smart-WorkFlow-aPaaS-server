package com.sw.ck.system.api.dept;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 部门查询 Facade 接口。
 * <p>
 * 定义于 {@code -api} 模块，由 {@code -biz} 模块实现。
 * 其它模块（如 form 的人员/部门选择字段）需要查询部门时<strong>仅可</strong>依赖本接口，
 * 禁止直接访问 {@code sys_dept} 表或对应 Mapper。
 * </p>
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：empty 表达查询上下文缺失，
 * 合法零匹配以 present 的空集合表达，两者不可互换。
 * </p>
 */
public interface DeptQueryFacade {

    /**
     * 按部门 ID 批量查询当前租户内正常状态部门。
     *
     * @param ids 部门 ID 集合
     * @return present = 命中的部门 ID 列表（已升序语义由调用方自行排序使用；空集合表示
     *         查询已执行且零匹配，如全部 ID 都不存在或已停用）；
     *         empty = {@code ids} 为 {@code null}，缺少查询对象，查询未执行
     */
    Optional<List<Long>> findActiveDeptIds(Collection<Long> ids);

    /**
     * BF20261013-021：列出指定租户内正常状态部门的目录行（部门ID/上级部门/有效负责人）。
     * 供流程脚本组织函数（获取部门负责人/获取分管领导/获取上级部门）一次性快照；
     * 负责人须为同租户启用用户，未设置或无效登记为 null（部门行仍返回）。
     *
     * @param tenantId 租户 ID
     * @return present = 目录行（空集合 = 该租户无正常状态部门，合法零匹配）；
     *         empty = {@code tenantId} 为 {@code null}，缺少查询上下文，查询未执行
     */
    Optional<List<DeptDirectoryRow>> listDeptDirectory(Long tenantId);
}

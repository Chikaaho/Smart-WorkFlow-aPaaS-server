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
}

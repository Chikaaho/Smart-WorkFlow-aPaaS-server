package com.sw.ck.system.api.dept;

import java.util.Collection;
import java.util.List;

/**
 * 部门查询 Facade 接口。
 * <p>
 * 定义于 {@code -api} 模块，由 {@code -biz} 模块实现。
 * 其它模块（如 form 的人员/部门选择字段）需要查询部门时<strong>仅可</strong>依赖本接口，
 * 禁止直接访问 {@code sys_dept} 表或对应 Mapper。
 * </p>
 */
public interface DeptQueryFacade {

    /**
     * 按关键字模糊搜索正常状态部门（匹配 dept_name）。
     *
     * @param keyword 关键字（空白视为列出全部）
     * @param limit   最大返回条数（正数；&lt;=0 时按 1 处理）
     * @return 部门候选列表（已按 id 升序，仅含正常状态部门）
     */
    List<DeptOptionDTO> searchActiveDepts(String keyword, int limit);

    /**
     * 按部门 ID 批量查询展示名。
     *
     * @param ids 部门 ID 集合（空集合返回空 Map）
     * @return id → 部门名；查不到的 ID 不在结果中
     */
    List<Long> findActiveDeptIds(Collection<Long> ids);
}

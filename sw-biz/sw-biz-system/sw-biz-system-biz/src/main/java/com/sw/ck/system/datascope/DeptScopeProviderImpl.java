package com.sw.ck.system.datascope;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.mapper.SysDeptMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 部门树查询实现：DEPT_AND_CHILD 数据范围的下级部门展开。
 * <p>
 * 决策：Java 递归 + 单表查询，不引入 ancestors 列——避免存量数据回填迁移；
 * 部门规模小，一次性加载全量部门（受租户拦截器自动限定当前租户）在内存中建树遍历足够。
 * </p>
 * <p>
 * 该实现注册为普通 {@code @Service} Bean，自动覆盖
 * {@code MybatisPlusConfig#noopDeptScopeProvider}（其 @ConditionalOnMissingBean 兜底）。
 * </p>
 * <p>
 * {@code @Lazy} 注入 {@link SysDeptMapper}：本 Bean 处于
 * sqlSessionFactory → MybatisPlusInterceptor → DataPermissionInterceptor → DeptScopeProvider
 * 的拦截器链依赖图中，若在 sqlSessionFactory 创建期间急切初始化 Mapper 会触发
 * BeanCurrentlyInCreationException 循环依赖；懒加载代理将 Mapper 的初始化推迟到
 * sqlSessionFactory 就绪之后（首次实际查询时）。
 * </p>
 */
@Service
public class DeptScopeProviderImpl implements DeptScopeProvider {

    @Autowired
    @Lazy
    private SysDeptMapper sysDeptMapper;

    @Override
    public List<Long> listChildDeptIds(Long deptId) {
        if (deptId == null) {
            return Collections.emptyList();
        }
        // 单次查询全量部门，构建 parentId -> 直接子部门 的映射后做层级遍历
        Map<Long, List<Long>> childrenByParent = sysDeptMapper.selectList(null).stream()
                .filter(dept -> dept.getId() != null && dept.getParentId() != null)
                .collect(Collectors.groupingBy(
                        SysDept::getParentId,
                        Collectors.mapping(SysDept::getId, Collectors.toList())));

        if (!childrenByParent.containsKey(deptId)) {
            // 部门不存在或没有任何子部门
            return Collections.emptyList();
        }

        List<Long> result = new ArrayList<>();
        Deque<Long> stack = new ArrayDeque<>(childrenByParent.getOrDefault(deptId, Collections.emptyList()));
        // 环保护：parent_id 成环（含自引用）时保证遍历终止
        Set<Long> visited = new HashSet<>();
        visited.add(deptId);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            result.add(current);
            List<Long> children = childrenByParent.get(current);
            if (children != null) {
                stack.addAll(children);
            }
        }
        return result;
    }
}

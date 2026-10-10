package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.system.api.delegate.PositionDelegateFacade;
import com.sw.ck.system.entity.SysPost;
import com.sw.ck.system.entity.SysPostDelegate;
import com.sw.ck.system.mapper.SysPostDelegateMapper;
import com.sw.ck.system.mapper.SysPostMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * P64 阶段Ⅱ（A07）岗位委托门面实现。
 * <p>
 * 解析顺序（主方向 §3.4）：源岗位 → 有效委托关系 → 受托岗位 → 实际办理人。
 * 精确部门范围（DEPT，须与业务部门一致）优先于显式组织默认范围（ORG）；
 * 逐跳检查关系有效性与范围，最多 4 跳；未命中关系使用源岗位有效人员；
 * 命中但目标失效/空缺/超跳/循环时抛可诊断异常（不自动回退到源岗位）。
 * 多源岗位结果按用户 ID 并集去重，跳链按源岗位分组保留审计。
 * </p>
 */
@Slf4j
@Service
public class PositionDelegateFacadeImpl implements PositionDelegateFacade {

    /** 与配置服务一致的委托最大跳数。 */
    private static final int MAX_HOPS = 4;

    private static final int POST_STATUS_ENABLED = 1;

    private final SysPostDelegateMapper delegateMapper;
    private final SysPostMapper sysPostMapper;
    private final SysUserMapper sysUserMapper;

    public PositionDelegateFacadeImpl(SysPostDelegateMapper delegateMapper,
                                      SysPostMapper sysPostMapper,
                                      SysUserMapper sysUserMapper) {
        this.delegateMapper = delegateMapper;
        this.sysPostMapper = sysPostMapper;
        this.sysUserMapper = sysUserMapper;
    }

    @Override
    public Optional<ResolvedPostActors> resolvePostActors(Long tenantId,
                                                          Collection<String> sourcePostCodes,
                                                          Long deptId) {
        if (sourcePostCodes == null || tenantId == null) {
            // 岗位编码集合或租户上下文缺失，查询未执行
            return Optional.empty();
        }
        List<String> codes = sourcePostCodes.stream()
                .filter(code -> code != null && !code.isBlank()).distinct().toList();
        if (codes.isEmpty()) {
            return Optional.of(new ResolvedPostActors(List.of(), List.of()));
        }
        Map<String, SysPost> postsByCode = loadEnabledPosts(tenantId, codes);
        Set<Long> userIds = new LinkedHashSet<>();
        List<DelegateHop> hops = new ArrayList<>();
        for (String code : codes) {
            SysPost sourcePost = postsByCode.get(code);
            if (sourcePost == null) {
                // 源岗位不存在/停用：本岗位解析为空，交由调用方空缺处置（不越权扩大）
                continue;
            }
            Chain chain = walkChain(tenantId, sourcePost, deptId, postsByCode);
            hops.addAll(chain.hops());
            List<Long> holders = resolveHolders(tenantId, chain, deptId);
            if (holders.isEmpty() && !chain.hops().isEmpty()) {
                // 命中委托但终点空缺：异常处置、可诊断，不自动回退到源岗位（主方向 §3.4）
                throw new IllegalStateException(
                        "岗位委托解析空缺：受托岗位 " + chain.finalPostCode() + " 无有效任职人员"
                                + "（已命中 " + chain.hops().size() + " 跳委托），"
                                + "请检查委托配置或岗位任职，不自动回退");
            }
            // 未命中委托的空缺沿 P63 原义贡献空集合，交由调用方空缺处置
            userIds.addAll(holders);
        }
        return Optional.of(new ResolvedPostActors(List.copyOf(userIds), List.copyOf(hops)));
    }

    // ==================== 内部 ====================

    private Map<String, SysPost> loadEnabledPosts(Long tenantId, List<String> codes) {
        Map<String, SysPost> result = new LinkedHashMap<>();
        for (String code : codes) {
            SysPost post = sysPostMapper.selectOne(new LambdaQueryWrapper<SysPost>()
                    .eq(SysPost::getCode, code)
                    .eq(SysPost::getTenantId, tenantId)
                    .eq(SysPost::getStatus, POST_STATUS_ENABLED)
                    .last("limit 1"));
            if (post != null) {
                result.put(code, post);
            }
        }
        return result;
    }

    /**
     * 沿有效委托关系逐跳前进（DEPT 精确匹配优先，无精确命中走 ORG 默认）。
     * 循环/超跳为运行期纵深防御（配置期已拒绝）。
     */
    private Chain walkChain(Long tenantId, SysPost sourcePost, Long deptId,
                            Map<String, SysPost> postsByCode) {
        List<DelegateHop> hops = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(sourcePost.getId());
        SysPost cursor = sourcePost;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            SysPostDelegate rule = findEnabledRule(tenantId, cursor.getId(), deptId);
            if (rule == null) {
                return new Chain(hops, codeOf(postsByCode, cursor));
            }
            SysPost target = sysPostMapper.selectById(rule.getTargetPostId());
            if (target == null || target.getStatus() == null
                    || target.getStatus() != POST_STATUS_ENABLED
                    || !tenantId.equals(target.getTenantId())) {
                // 受托岗位不存在/停用/跨租户：关系失效，异常处置不回退（跨租户映射在配置期拒绝）
                throw new IllegalStateException(
                        "岗位委托关系失效：岗位 " + codeOf(postsByCode, cursor) + " 的受托岗位不存在、已停用或越权，"
                                + "请检查委托配置，不自动回退");
            }
            hops.add(new DelegateHop(codeOf(postsByCode, cursor), codeOf(postsByCode, target),
                    rule.getScopeType(), rule.getDeptId()));
            if (!visited.add(target.getId())) {
                throw new IllegalStateException(
                        "岗位委托链循环：岗位 " + codeOf(postsByCode, target)
                                + " 重复出现，请修正委托配置");
            }
            cursor = target;
        }
        throw new IllegalStateException(
                "岗位委托链超过最大 " + MAX_HOPS + " 跳，解析终止，请修正委托配置");
    }

    /** 精确部门范围优先于组织默认范围；每源岗位/范围最多一条有效关系。 */
    private SysPostDelegate findEnabledRule(Long tenantId, Long sourcePostId, Long deptId) {
        if (deptId != null) {
            SysPostDelegate deptRule = delegateMapper.selectOne(
                    new LambdaQueryWrapper<SysPostDelegate>()
                            .eq(SysPostDelegate::getTenantId, tenantId)
                            .eq(SysPostDelegate::getSourcePostId, sourcePostId)
                            .eq(SysPostDelegate::getScopeType, SysPostDelegate.SCOPE_DEPT)
                            .eq(SysPostDelegate::getDeptId, deptId)
                            .eq(SysPostDelegate::getStatus, SysPostDelegate.STATUS_ENABLED)
                            .last("limit 1"));
            if (deptRule != null) {
                return deptRule;
            }
        }
        return delegateMapper.selectOne(new LambdaQueryWrapper<SysPostDelegate>()
                .eq(SysPostDelegate::getTenantId, tenantId)
                .eq(SysPostDelegate::getSourcePostId, sourcePostId)
                .eq(SysPostDelegate::getScopeType, SysPostDelegate.SCOPE_ORG)
                .eq(SysPostDelegate::getStatus, SysPostDelegate.STATUS_ENABLED)
                .last("limit 1"));
    }

    /** 终点岗位人员：精确部门委托（链上末跳为 DEPT）按部门内任职解析；否则组织范围任职。 */
    private List<Long> resolveHolders(Long tenantId, Chain chain, Long deptId) {
        String finalPostCode = chain.finalPostCode();
        boolean deptScoped = !chain.hops().isEmpty()
                && SysPostDelegate.SCOPE_DEPT.equals(chain.hops().get(chain.hops().size() - 1).scopeType());
        if (deptScoped && deptId != null) {
            return sysUserMapper.selectActiveUserIdsByDeptAndPost(deptId, finalPostCode, tenantId);
        }
        return sysUserMapper.selectActiveUserIdsByPostCodes(List.of(finalPostCode), tenantId);
    }

    private String codeOf(Map<String, SysPost> postsByCode, SysPost post) {
        return postsByCode.entrySet().stream()
                .filter(entry -> entry.getValue().getId().equals(post.getId()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseGet(post::getCode);
    }

    /** 逐跳链结果：命中跳 + 终点岗位编码。 */
    private record Chain(List<DelegateHop> hops, String finalPostCode) {
    }
}

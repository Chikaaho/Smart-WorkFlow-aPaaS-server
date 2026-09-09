package com.sw.ck.bpm.engine.resolver;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverContext;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverResolver;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverType;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 固定审批人解析器。
 * <p>
 * 读取 {@link NodeApproverContext#getApproverValue()} 作为 userId 列表，
 * v1 取首个设为 assignee。
 * approverValue 预期为 {@code List<Integer>} 或 {@code List<String>} 或兼容格式。
 * </p>
 *
 * <h3>分发 Key</h3>
 * 注册为 {@link NodeApproverType#DESIGNATED}。
 */
@Component("designatedApproverResolver")
public class DesignatedApproverResolver implements NodeApproverResolver {

    private static final Logger log = LoggerFactory.getLogger(DesignatedApproverResolver.class);

    /** 可选：经组织权威过滤停用/跨租户用户（I1 动态选人只消费服务端认可数据）。 */
    private final UserQueryFacade userQueryFacade;

    /** 兼容既有单测直接构造。 */
    public DesignatedApproverResolver() {
        this(null);
    }

    public DesignatedApproverResolver(UserQueryFacade userQueryFacade) {
        this.userQueryFacade = userQueryFacade;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> resolve(NodeApproverContext context) {
        Object value = context.getApproverValue();
        if (value == null) {
            log.error("DESIGNATED approver value is null: nodeKey={}", context.getNodeKey());
            throw new BaseException(BpmErrorCode.APPROVER_RESOLVE_EMPTY.getCode(),
                    "固定审批人配置值为空");
        }

        List<String> userIds;
        if (value instanceof List<?> rawList) {
            userIds = rawList.stream()
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .toList();
        } else {
            // 单个 userId
            userIds = List.of(value.toString());
        }

        if (userIds.isEmpty()) {
            log.error("DESIGNATED approver value resolved to empty list: nodeKey={}", context.getNodeKey());
            throw new BaseException(BpmErrorCode.APPROVER_RESOLVE_EMPTY);
        }

        userIds = filterActiveUsers(userIds, context.getTenantId());
        if (userIds.isEmpty()) {
            log.error("DESIGNATED approvers all invalid/disabled: nodeKey={}, tenantId={}",
                    context.getNodeKey(), context.getTenantId());
            throw new BaseException(BpmErrorCode.APPROVER_RESOLVE_EMPTY);
        }

        log.debug("DesignatedApproverResolver resolved {} approvers: nodeKey={}",
                userIds.size(), context.getNodeKey());
        return userIds;
    }

    /** 经 UserQueryFacade 过滤非本租户/停用/已删除用户；Facade 缺失时保持原列表（兼容单测）。 */
    private List<String> filterActiveUsers(List<String> userIds, Long tenantId) {
        if (userQueryFacade == null || tenantId == null) {
            return userIds;
        }
        List<Long> ids = userIds.stream()
                .filter(id -> id != null && id.matches("\\d+"))
                .map(Long::valueOf)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return userIds;
        }
        List<Long> active = userQueryFacade.findActiveUserIds(ids, tenantId);
        return userIds.stream().filter(id -> {
            try {
                return active.contains(Long.valueOf(id));
            } catch (NumberFormatException e) {
                return false;
            }
        }).toList();
    }
}

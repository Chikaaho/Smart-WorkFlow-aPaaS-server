package com.sw.ck.bpm.process.nodefunc;

import com.sw.ck.bpm.api.nodefunc.NodeFunctionContext;
import com.sw.ck.bpm.api.nodefunc.ParticipantFunction;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 内建参与人函数：tenantAdmins —— 解析当前租户 superadmin 角色的有效用户。
 * 输出受 {@code NodeFunctionService} 白名单校验；空结果按注册失败策略处置。
 */
@Component("func_tenant_admins")
public class TenantAdminParticipantFunction implements ParticipantFunction {

    private final UserQueryFacade userQueryFacade;

    public TenantAdminParticipantFunction(UserQueryFacade userQueryFacade) {
        this.userQueryFacade = userQueryFacade;
    }

    @Override
    public Optional<List<String>> resolveParticipants(NodeFunctionContext context) {
        // 契约恒 present：查询上下文缺失（empty）只表示无法确定租户范围，
        // 该事实由 NodeFunctionService 的失败策略裁决，此处保持原有“空列表”结论。
        List<Long> users = userQueryFacade.findActiveUserIdsByRoleCodes(
                        List.of("superadmin"), context.getTenantId())
                .orElse(List.of());
        return Optional.of(users.stream().map(String::valueOf).limit(100).toList());
    }
}

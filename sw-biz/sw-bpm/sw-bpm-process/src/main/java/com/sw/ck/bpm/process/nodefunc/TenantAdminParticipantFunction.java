package com.sw.ck.bpm.process.nodefunc;

import com.sw.ck.bpm.api.nodefunc.NodeFunctionContext;
import com.sw.ck.bpm.api.nodefunc.ParticipantFunction;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.springframework.stereotype.Component;

import java.util.List;

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
    public List<String> resolveParticipants(NodeFunctionContext context) {
        List<Long> users = userQueryFacade.findActiveUserIdsByRoleCodes(
                List.of("superadmin"), context.getTenantId());
        return users == null ? List.of()
                : users.stream().map(String::valueOf).limit(100).toList();
    }
}

package com.sw.ck.system.security;

import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysAuditLog;
import com.sw.ck.system.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理操作审计过滤器（R3/R5）：只记改动类操作、中文动作/模块/业务对象、
 * 下载类二进制响应不为失败。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("管理操作审计过滤器")
class AuditOperationFilterTest {

    @Mock
    private ObjectProvider<AuditLogService> provider;
    @Mock
    private AuditLogService auditLogService;

    private AuditOperationFilter filter;

    @BeforeEach
    void setUp() {
        when(provider.getObject()).thenReturn(auditLogService);
        filter = new AuditOperationFilter(provider);
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setTenantId(0L);
        user.setUsername("admin");
        LoginUserHolder.set(user);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    @DisplayName("分页/查询类 POST 不记录")
    void queryPostIsNotRecorded() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/system/user/page", "{}");
        execute(request, 200, "application/json", "{\"code\":0,\"msg\":\"success\"}");
        verify(auditLogService, never()).record(any());
    }

    @Test
    @DisplayName("导入记录为中文动作/模块/对象")
    void importRecordedAsChinese() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/system/user/import", "{}");
        execute(request, 200, "application/json", "{\"code\":0,\"msg\":\"success\"}");

        SysAuditLog entry = captured();
        assertThat(entry.getAction()).isEqualTo("导入");
        assertThat(entry.getModule()).isEqualTo("用户管理");
        assertThat(entry.getTarget()).isEqualTo("用户批量导入");
        assertThat(entry.getResult()).isEqualTo(SysAuditLog.RESULT_SUCCESS);
    }

    @Test
    @DisplayName("授权动作与路径 id 组成可读对象")
    void roleAssignmentTargetUsesObjectId() throws Exception {
        MockHttpServletRequest request = jsonRequest("PUT", "/system/user/12/roles", "{}");
        execute(request, 200, "application/json", "{\"code\":0,\"msg\":\"success\"}");

        SysAuditLog entry = captured();
        assertThat(entry.getAction()).isEqualTo("授权");
        assertThat(entry.getTarget()).isEqualTo("用户 12");
    }

    @Test
    @DisplayName("新增用户取请求体名称作为对象")
    void createTargetUsesBodyName() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/system/user",
                "{\"username\":\"zhangsan\",\"realName\":\"张三\"}");
        execute(request, 200, "application/json", "{\"code\":0,\"msg\":\"success\"}");

        SysAuditLog entry = captured();
        assertThat(entry.getAction()).isEqualTo("新增");
        assertThat(entry.getTarget()).isEqualTo("用户 zhangsan");
    }

    @Test
    @DisplayName("UUID 主键（表单定义）对象回退写 id，不只剩类型")
    void uuidPathTargetFallsBackToId() throws Exception {
        String uuid = "27b5ab65-6bcc-442d-b433-fa7adff61bc0";
        MockHttpServletRequest request = jsonRequest("POST", "/form/def/" + uuid + "/config", "{}");
        execute(request, 200, "application/json", "{\"code\":0,\"msg\":\"success\"}");

        SysAuditLog entry = captured();
        assertThat(entry.getAction()).isEqualTo("修改");
        assertThat(entry.getModule()).isEqualTo("表单定义");
        assertThat(entry.getTarget()).isEqualTo("表单定义 " + uuid);
    }

    @Test
    @DisplayName("二进制下载响应（xlsx）记成功，不误判为业务失败")
    void binaryDownloadIsSuccess() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/system/user/export", "{}");
        execute(request, 200, "application/octet-stream", "PK\u0003\u0004binary");

        SysAuditLog entry = captured();
        assertThat(entry.getResult()).isEqualTo(SysAuditLog.RESULT_SUCCESS);
        assertThat(entry.getAction()).isEqualTo("导出");
        assertThat(entry.getTarget()).isEqualTo("用户导出");
    }

    @Test
    @DisplayName("失败原因取服务端可读文案，不再写「业务码 非0」")
    void failureReasonUsesServerMessage() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/system/user/import", "{}");
        execute(request, 200, "application/json",
                "{\"code\":400,\"msg\":\"请先选择目标租户\"}");

        SysAuditLog entry = captured();
        assertThat(entry.getResult()).isEqualTo(SysAuditLog.RESULT_FAIL);
        assertThat(entry.getFailReason()).isEqualTo("请先选择目标租户");
    }

    private SysAuditLog captured() {
        ArgumentCaptor<SysAuditLog> captor = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(auditLogService).record(captor.capture());
        return captor.getValue();
    }

    private MockHttpServletRequest jsonRequest(String method, String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private void execute(MockHttpServletRequest request, int status, String contentType,
                         String body) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCharacterEncoding("UTF-8");
        FilterChain chain = (req, res) -> {
            jakarta.servlet.http.HttpServletResponse httpResponse =
                    (jakarta.servlet.http.HttpServletResponse) res;
            // 模拟 MVC 读取请求体，触发 ContentCachingRequestWrapper 缓存
            req.getInputStream().readAllBytes();
            httpResponse.setStatus(status);
            httpResponse.setContentType(contentType);
            if (contentType != null && contentType.contains("json")) {
                httpResponse.getWriter().write(body);
            } else {
                httpResponse.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
        };
        filter.doFilter(request, response, chain);
    }
}

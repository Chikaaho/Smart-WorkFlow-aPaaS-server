package com.sw.ck.form.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 文件关联判定的类型、字段权限与拒绝边界；记录读取投影由既有查询集成测试覆盖。 */
class FormAttachmentAccessTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FormDefService definitions = mock(FormDefService.class);
    private final FormConfigMapper configs = mock(FormConfigMapper.class);
    private final FormDataQueryService service = spy(new FormDataQueryService(definitions,
            mock(FormDefMapper.class), configs, mock(JdbcTemplate.class), mapper));
    private final FormConfigEntity config = new FormConfigEntity();

    @BeforeEach
    void setup() {
        LoginUser actor = new LoginUser();
        actor.setUserId(2L);
        actor.setTenantId(9L);
        LoginUserHolder.set(actor);
        FormDefDTO definition = new FormDefDTO();
        definition.setId("form-id");
        when(definitions.getFormDefByKey("form")).thenReturn(definition);
        when(configs.selectList(any())).thenReturn(List.of(config));
        service.setFieldPermissionService(new FieldPermissionService(mapper));
        definition("{}");
    }

    @AfterEach
    void cleanup() {
        LoginUserHolder.clear();
    }

    private void definition(String permissions) {
        config.setDefinition("""
                {"fields":[
                  {"name":"document","type":"ATTACHMENT"},
                  {"name":"photo","type":"IMAGE"},
                  {"name":"note","type":"TEXT"},
                  {"name":"lookup","type":"DATASOURCE"},
                  {"name":"lines","type":"TABLE","subFields":[
                    {"name":"proof","type":"ATTACHMENT"}]}],
                 "fieldPermissions":%s}
                """.formatted(permissions));
    }

    private void record(Map<String, Object> values) {
        doReturn(values).when(service).getRecordDetail("form", "record");
    }

    @Test
    void acceptsOnlyAnExactVisibleAttachmentReference() {
        record(Map.of("document", "[{\"storageKey\":\"file-a\",\"name\":\"plan.pdf\"}]"));
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "file-a")).isTrue();
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "file")).isFalse();
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "file-b")).isFalse();
        assertThat(service.canCurrentUserAccessAttachment("form", "record", null)).isFalse();
        assertThat(service.canCurrentUserAccessAttachment("form", "record", " ")).isFalse();
    }

    @Test
    void acceptsLegacyStringKeysWithoutAllowingPartialMatches() {
        record(Map.of("document", "[\"legacy/path/file.pdf\"]"));
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "legacy/path/file.pdf")).isTrue();
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "file.pdf")).isFalse();
        record(Map.of("lines", List.of(Map.of("proof", List.of("sub-key")))));
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "sub-key")).isTrue();
    }

    @Test
    void acceptsAnImageReferenceDecodedAsAnArray() {
        record(Map.of("photo", List.of(Map.of("storageKey", "photo-key"))));
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "photo-key")).isTrue();
    }

    @Test
    void textOrDatasourceContentCannotClaimAnUnrelatedFile() {
        record(Map.of("note", "[{\"storageKey\":\"file-a\"}]",
                "lookup", Map.of("storageKey", "file-a")));
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "file-a")).isFalse();
    }

    @Test
    void rejectsHiddenAttachmentEvenIfAnUpstreamRowContainsItsValue() {
        definition("{\"document\":{\"view\":[\"user:999\"]}}");
        record(Map.of("document", List.of(Map.of("storageKey", "private-key"))));
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "private-key")).isFalse();
    }

    @Test
    void acceptsVisibleSubtableAttachmentButDeniesHiddenChildOrParent() {
        record(Map.of("lines", List.of(Map.of("proof", "[{\"storageKey\":\"sub-key\"}]"))));
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "sub-key")).isTrue();
        definition("{\"lines.proof\":{\"view\":[\"user:999\"]}}");
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "sub-key")).isFalse();
        definition("{\"lines\":{\"view\":[\"user:999\"]}}");
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "sub-key")).isFalse();
    }

    @Test
    void missingOrMalformedAttachmentIsNotAReference() {
        record(Map.of("document", "[invalid-json"));
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "file-a")).isFalse();
        record(Map.of("document", Map.of("storageKey", "file-a")));
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "file-a")).isFalse();
        record(Map.of());
        assertThat(service.canCurrentUserAccessAttachment("form", "record", "file-a")).isFalse();
    }

    @Test
    void recordReadDenialIsNotConvertedIntoFileAccess() {
        BaseException denied = new BaseException(403, "denied");
        doThrow(denied).when(service).getRecordDetail("form", "record");
        assertThatThrownBy(() -> service.canCurrentUserAccessAttachment("form", "record", "file-a"))
                .isSameAs(denied);
    }
}

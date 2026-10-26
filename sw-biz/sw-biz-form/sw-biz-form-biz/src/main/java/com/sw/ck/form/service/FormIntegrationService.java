package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.integration.ExternalCapability;
import com.sw.ck.common.integration.ExternalCapabilityFacade;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dto.FormIntegrationCandidateDTO;
import com.sw.ck.form.dto.FormIntegrationInvokeReq;
import com.sw.ck.form.dto.FormIntegrationInvokeResult;
import com.sw.ck.form.entity.FormIntegrationInvocationEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.mapper.FormIntegrationInvocationMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表单运行期受控集成服务。
 * <p>
 * 授权取交集：当前登录主体/租户 ∩ 绑定发布版本快照中的表单允许清单 ∩ 能力所属租户/启用状态
 * ∩ 当前主体对表单/记录的实际操作范围。浏览器提交的允许清单、角色名或 URL 都不作为授权依据。
 * </p>
 * <p>
 * 阶段边界：{@code QUERY} 能力可在加载/提交前/提交后调用；{@code WRITE} 能力只允许在
 * {@code AFTER_SUBMIT} 阶段调用，且必须携带稳定提交标识，以账本保证重试不重复外部副作用。
 * 未分类能力一律不可调用。只读历史/预览不经过本服务（宿主不执行脚本），服务端仍按身份复核。
 * </p>
 */
@Service
public class FormIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(FormIntegrationService.class);

    private static final Pattern CAPABILITY_ID = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    private static final Set<String> PHASES = Set.of("BEFORE_SUBMIT", "AFTER_SUBMIT");
    private static final String SCOPE_QUERY = "QUERY";
    private static final String SCOPE_WRITE = "WRITE";
    private static final int MAX_INPUT_BYTES = 64 * 1024;
    private static final int MAX_RESULT_CHARS = 256 * 1024;
    private static final int MAX_REQUEST_KEY_LENGTH = 128;
    private static final int MAX_ERROR_LENGTH = 500;
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";

    private final FormDefService formDefService;
    private final FormDataQueryService formDataQueryService;
    private final ObjectProvider<ExternalCapabilityFacade> capabilityFacade;
    private final FormIntegrationInvocationMapper invocationMapper;
    private final FormIdGenerator idGenerator;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FormIntegrationService(FormDefService formDefService,
                                  FormDataQueryService formDataQueryService,
                                  ObjectProvider<ExternalCapabilityFacade> capabilityFacade,
                                  FormIntegrationInvocationMapper invocationMapper,
                                  FormIdGenerator idGenerator,
                                  JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper) {
        this.formDefService = formDefService;
        this.formDataQueryService = formDataQueryService;
        this.capabilityFacade = capabilityFacade;
        this.invocationMapper = invocationMapper;
        this.idGenerator = idGenerator;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 当前租户内可供表单设计者选择的已分类启用能力（不含 URL/方法）。 */
    public List<FormIntegrationCandidateDTO> listCandidates() {
        LoginUser user = requireLogin();
        ExternalCapabilityFacade facade = capabilityFacade.getIfAvailable();
        if (facade == null) return List.of();
        List<FormIntegrationCandidateDTO> result = new ArrayList<>();
        for (ExternalCapability capability : facade.listEnabled(user.getTenantId())) {
            if (capability.effectScope() == null) continue;
            result.add(new FormIntegrationCandidateDTO(capability.name(), capability.description(),
                    capability.effectScope(), capability.timeoutSeconds()));
        }
        return result;
    }

    /** 执行一次受控集成调用。 */
    public FormIntegrationInvokeResult invoke(FormIntegrationInvokeReq req) {
        LoginUser user = requireLogin();
        String formKey = requireText(req.getFormKey(), 128, "formKey");
        String capabilityId = requireCapabilityId(req.getCapabilityId());
        String phase = normalizePhase(req.getPhase());

        if (!formDefService.isCurrentUserVisible(formKey)) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        FormDefDTO def = formDefService.getFormDefByKey(formKey);
        if (def == null || def.getId() == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        Integer formVersion = req.getFormVersion();
        if (formVersion == null) {
            formVersion = def.getFormVersion();
        }
        if (formVersion == null || formVersion <= 0) {
            throw new BaseException(FormErrorCode.INTEGRATION_NOT_CONFIGURED);
        }

        String definition = formDefService.getSnapshotDefinitionByKey(formKey, formVersion);
        if (definition == null) {
            throw new BaseException(FormErrorCode.INTEGRATION_NOT_CONFIGURED);
        }
        assertAllowedBySnapshot(definition, capabilityId);

        ExternalCapabilityFacade facade = capabilityFacade.getIfAvailable();
        if (facade == null) {
            throw new BaseException(FormErrorCode.INTEGRATION_UNAVAILABLE);
        }
        ExternalCapability capability = facade.findEnabled(user.getTenantId(), capabilityId);
        if (capability == null) {
            throw new BaseException(FormErrorCode.INTEGRATION_UNAVAILABLE);
        }
        String scope = capability.effectScope() == null
                ? ""
                : capability.effectScope().trim().toUpperCase(Locale.ROOT);
        if (!SCOPE_QUERY.equals(scope) && !SCOPE_WRITE.equals(scope)) {
            throw new BaseException(FormErrorCode.INTEGRATION_UNAVAILABLE);
        }

        String recordId = trimToNull(req.getRecordId());
        if (SCOPE_WRITE.equals(scope)) {
            if (!"AFTER_SUBMIT".equals(phase)) {
                throw new BaseException(FormErrorCode.INTEGRATION_PHASE_DENIED,
                        "写副作用能力只允许在提交后阶段调用");
            }
            if (recordId == null) {
                throw new BaseException(FormErrorCode.INTEGRATION_RECORD_DENIED,
                        "提交后写副作用调用必须绑定记录");
            }
            assertSubmitter(def.getId(), recordId, user);
        }
        if (recordId != null) {
            // 既有记录调用沿用数据查看权限，越权/不存在由查询服务拒绝。
            formDataQueryService.getRecordDetail(formKey, recordId);
        }

        String inputJson = serializeInput(req.getInput());
        if (SCOPE_QUERY.equals(scope)) {
            try {
                FormIntegrationInvokeResult result = execute(facade, capability, inputJson, false);
                log.info("Form integration query: formKey={}, version={}, capability={}, phase={}, user={}, tenant={}",
                        formKey, formVersion, capabilityId, phase, user.getUserId(), user.getTenantId());
                return result;
            } catch (RuntimeException failure) {
                log.warn("Form integration query failed: formKey={}, version={}, capability={}, phase={}, user={}, tenant={}, error={}",
                        formKey, formVersion, capabilityId, phase, user.getUserId(),
                        user.getTenantId(), errorMessage(failure));
                throw failure;
            }
        }

        String requestKey = requireText(req.getRequestKey(), MAX_REQUEST_KEY_LENGTH, "requestKey");
        return executeWrite(facade, capability, formKey, formVersion, capabilityId, phase,
                requestKey, user, inputJson);
    }

    private FormIntegrationInvokeResult executeWrite(ExternalCapabilityFacade facade,
                                                     ExternalCapability capability,
                                                     String formKey,
                                                     Integer formVersion,
                                                     String capabilityId,
                                                     String phase,
                                                     String requestKey,
                                                     LoginUser user,
                                                     String inputJson) {
        String requestHash = sha256(inputJson);
        FormIntegrationInvocationEntity existing = findInvocation(formKey, formVersion, capabilityId,
                phase, requestKey, user);
        if (existing != null) {
            if (existing.getRequestHash() != null
                    && !existing.getRequestHash().equals(requestHash)) {
                throw new BaseException(FormErrorCode.INTEGRATION_IDEMPOTENCY_CONFLICT);
            }
            if (STATUS_SUCCEEDED.equals(existing.getStatus())) {
                return new FormIntegrationInvokeResult(capabilityId, capability.effectScope(), true,
                        parseResult(existing.getResultJson()));
            }
            if (STATUS_PROCESSING.equals(existing.getStatus())) {
                throw new BaseException(FormErrorCode.INTEGRATION_IN_PROGRESS);
            }
            existing.setStatus(STATUS_PROCESSING);
            existing.setResultJson(null);
            existing.setErrorMessage(null);
            existing.setLatencyMs(null);
            existing.setRequestHash(requestHash);
            invocationMapper.updateById(existing);
        } else {
            FormIntegrationInvocationEntity created = new FormIntegrationInvocationEntity();
            created.setId(idGenerator.generate());
            created.setFormKey(formKey);
            created.setFormVersion(formVersion);
            created.setCapabilityId(capabilityId);
            created.setPhase(phase);
            created.setRequestKey(requestKey);
            created.setRequestHash(requestHash);
            created.setSubjectId(user.getUserId());
            created.setStatus(STATUS_PROCESSING);
            try {
                invocationMapper.insert(created);
            } catch (DuplicateKeyException race) {
                FormIntegrationInvocationEntity raced = findInvocation(formKey, formVersion,
                        capabilityId, phase, requestKey, user);
                if (raced != null && raced.getRequestHash() != null
                        && !raced.getRequestHash().equals(requestHash)) {
                    throw new BaseException(FormErrorCode.INTEGRATION_IDEMPOTENCY_CONFLICT);
                }
                if (raced != null && STATUS_SUCCEEDED.equals(raced.getStatus())) {
                    return new FormIntegrationInvokeResult(capabilityId, capability.effectScope(),
                            true, parseResult(raced.getResultJson()));
                }
                throw new BaseException(FormErrorCode.INTEGRATION_IN_PROGRESS);
            }
            existing = created;
        }

        long started = System.currentTimeMillis();
        try {
            FormIntegrationInvokeResult result = execute(facade, capability, inputJson, false);
            existing.setStatus(STATUS_SUCCEEDED);
            existing.setResultJson(serializeResult(result.getResult()));
            existing.setLatencyMs(System.currentTimeMillis() - started);
            invocationMapper.updateById(existing);
            log.info("Form integration write: formKey={}, version={}, capability={}, phase={}, requestKey={}, user={}, tenant={}, status=OK",
                    formKey, formVersion, capabilityId, phase, requestKey, user.getUserId(),
                    user.getTenantId());
            return result;
        } catch (RuntimeException failure) {
            existing.setStatus(STATUS_FAILED);
            existing.setErrorMessage(truncate(errorMessage(failure), MAX_ERROR_LENGTH));
            existing.setLatencyMs(System.currentTimeMillis() - started);
            invocationMapper.updateById(existing);
            log.warn("Form integration write failed: formKey={}, version={}, capability={}, requestKey={}, user={}, tenant={}, error={}",
                    formKey, formVersion, capabilityId, requestKey, user.getUserId(),
                    user.getTenantId(), errorMessage(failure));
            throw failure;
        }
    }

    private FormIntegrationInvokeResult execute(ExternalCapabilityFacade facade,
                                                ExternalCapability capability,
                                                String inputJson,
                                                boolean replayed) {
        long started = System.currentTimeMillis();
        String body;
        try {
            body = facade.invoke(capability, inputJson);
        } catch (BaseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BaseException(FormErrorCode.INTEGRATION_CALL_FAILED, safeMessage(e));
        }
        String normalized = body == null ? "" : body;
        if (normalized.length() > MAX_RESULT_CHARS) {
            throw new BaseException(FormErrorCode.INTEGRATION_RESULT_TOO_LARGE);
        }
        log.debug("Form integration capability call: capability={}, latencyMs={}, resultChars={}",
                capability.name(), System.currentTimeMillis() - started, normalized.length());
        return new FormIntegrationInvokeResult(capability.name(), capability.effectScope(),
                replayed, parseResult(normalized));
    }

    private void assertAllowedBySnapshot(String definition, String capabilityId) {
        try {
            JsonNode root = objectMapper.readTree(definition);
            JsonNode allowed = root == null ? null : root.get("allowedIntegrations");
            if (allowed == null || !allowed.isArray()) {
                throw new BaseException(FormErrorCode.INTEGRATION_NOT_ALLOWED);
            }
            for (JsonNode entry : allowed) {
                if (entry != null && entry.isTextual()
                        && capabilityId.equals(entry.asText().trim())) {
                    return;
                }
            }
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.INTEGRATION_NOT_CONFIGURED);
        }
        throw new BaseException(FormErrorCode.INTEGRATION_NOT_ALLOWED);
    }

    private void assertSubmitter(String formId, String recordId, LoginUser user) {
        List<Long> submitUsers = jdbcTemplate.queryForList(
                "SELECT submit_user_id FROM sw_form_trace "
                        + "WHERE tenant_id = ? AND form_id = ? AND record_id = ? AND deleted = 0 "
                        + "ORDER BY submit_time DESC LIMIT 1",
                Long.class, user.getTenantId(), formId, recordId);
        if (submitUsers.isEmpty() || !user.getUserId().equals(submitUsers.get(0))) {
            throw new BaseException(FormErrorCode.INTEGRATION_RECORD_DENIED);
        }
    }

    private FormIntegrationInvocationEntity findInvocation(String formKey,
                                                           Integer formVersion,
                                                           String capabilityId,
                                                           String phase,
                                                           String requestKey,
                                                           LoginUser user) {
        List<FormIntegrationInvocationEntity> rows = invocationMapper.selectList(
                Wrappers.<FormIntegrationInvocationEntity>lambdaQuery()
                        .eq(FormIntegrationInvocationEntity::getTenantId, user.getTenantId())
                        .eq(FormIntegrationInvocationEntity::getFormKey, formKey)
                        .eq(FormIntegrationInvocationEntity::getFormVersion, formVersion)
                        .eq(FormIntegrationInvocationEntity::getCapabilityId, capabilityId)
                        .eq(FormIntegrationInvocationEntity::getPhase, phase)
                        .eq(FormIntegrationInvocationEntity::getRequestKey, requestKey)
                        .eq(FormIntegrationInvocationEntity::getSubjectId, user.getUserId())
                        .orderByDesc(FormIntegrationInvocationEntity::getId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String serializeInput(java.util.Map<String, Object> input) {
        try {
            String json = objectMapper.writeValueAsString(input == null ? java.util.Map.of() : input);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
                throw new BaseException(FormErrorCode.INTEGRATION_REQUEST_INVALID,
                        "集成调用输入超过 64KB 上限");
            }
            return json;
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.INTEGRATION_REQUEST_INVALID);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String serializeResult(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.INTEGRATION_RESULT_TOO_LARGE);
        }
    }

    private Object parseResult(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    private LoginUser requireLogin() {
        LoginUser user = LoginUserHolder.get();
        if (user == null || user.getUserId() == null || user.getTenantId() == null) {
            throw new BaseException(FormErrorCode.INTEGRATION_RECORD_DENIED,
                    "当前登录身份或租户不可用");
        }
        return user;
    }

    private String requireCapabilityId(String raw) {
        String value = trimToNull(raw);
        if (value == null || !CAPABILITY_ID.matcher(value).matches()) {
            throw new BaseException(FormErrorCode.INTEGRATION_REQUEST_INVALID,
                    "集成能力标识不合法");
        }
        return value;
    }

    private String requireText(String raw, int maxLength, String field) {
        String value = trimToNull(raw);
        if (value == null || value.length() > maxLength) {
            throw new BaseException(FormErrorCode.INTEGRATION_REQUEST_INVALID,
                    field + " 不能为空或过长");
        }
        return value;
    }

    private String normalizePhase(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BaseException(FormErrorCode.INTEGRATION_REQUEST_INVALID,
                    "调用阶段不能为空");
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (!PHASES.contains(upper)) {
            throw new BaseException(FormErrorCode.INTEGRATION_REQUEST_INVALID,
                    "调用阶段仅支持 BEFORE_SUBMIT/AFTER_SUBMIT");
        }
        return upper;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return FormErrorCode.INTEGRATION_CALL_FAILED.getMessage();
        }
        return message.length() > MAX_ERROR_LENGTH
                ? message.substring(0, MAX_ERROR_LENGTH)
                : message;
    }

    private String errorMessage(RuntimeException e) {
        return e instanceof BaseException && e.getMessage() != null
                ? e.getMessage()
                : safeMessage(e);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

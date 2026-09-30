package com.sw.ck.form.txn.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.service.FormFieldValidator;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.txn.entity.TxnActionEntity;
import com.sw.ck.form.txn.entity.TxnActionVersionEntity;
import com.sw.ck.form.txn.mapper.TxnActionMapper;
import com.sw.ck.form.txn.mapper.TxnActionVersionMapper;
import com.sw.ck.form.txn.model.TxnActionConfig;
import com.sw.ck.form.txn.model.TxnActionSaveRequest;
import com.sw.ck.form.txn.model.TxnActionView;
import com.sw.ck.form.txn.model.TxnPublishError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 事务动作管理：草稿/发布校验/版本冻结/启停。
 * <p>发布校验产出结构化错误（字段路径 + 可读原因），非法配置被拒绝；合法配置发布后冻结为不可变版本。</p>
 */
@Service
public class TxnActionService {

    private static final Logger log = LoggerFactory.getLogger(TxnActionService.class);

    public static final String TYPE_RESERVE = "RESERVE";
    public static final String TYPE_CONFIRM = "CONFIRM";
    public static final String TYPE_RELEASE = "RELEASE";
    public static final String TYPE_ADJUST = "ADJUST";
    private static final Set<String> ACTION_TYPES = Set.of(TYPE_RESERVE, TYPE_CONFIRM, TYPE_RELEASE, TYPE_ADJUST);

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DISABLED = "DISABLED";

    /** 动作标识白名单（字母开头，字母/数字/下划线，≤100）。 */
    private static final Pattern ACTION_KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,99}$");

    /** 预占有效期上限：30 天。 */
    private static final long MAX_EXPIRES_IN_SECONDS = 30L * 24 * 3600;

    private final TxnActionMapper actionMapper;
    private final TxnActionVersionMapper versionMapper;
    private final TxnFormBinding binding;
    private final FormIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    public TxnActionService(TxnActionMapper actionMapper,
                            TxnActionVersionMapper versionMapper,
                            TxnFormBinding binding,
                            FormIdGenerator idGenerator,
                            ObjectMapper objectMapper) {
        this.actionMapper = actionMapper;
        this.versionMapper = versionMapper;
        this.binding = binding;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    // ==================== 查询 ====================

    public List<TxnActionView> listByForm(String formId) {
        List<TxnActionEntity> list = actionMapper.selectList(
                Wrappers.<TxnActionEntity>lambdaQuery()
                        .eq(TxnActionEntity::getFormId, formId)
                        .orderByAsc(TxnActionEntity::getActionKey));
        List<TxnActionView> views = new ArrayList<>();
        for (TxnActionEntity e : list) {
            views.add(toView(e));
        }
        return views;
    }

    public TxnActionView get(String id) {
        return toView(requireAction(id));
    }

    public TxnActionEntity requireAction(String id) {
        TxnActionEntity action = id == null ? null : actionMapper.selectById(id);
        if (action == null) {
            throw new BaseException(FormErrorCode.ACTION_NOT_FOUND, "事务动作不存在");
        }
        return action;
    }

    // ==================== 草稿 ====================

    @Transactional(rollbackFor = Exception.class)
    public TxnActionView create(String formId, TxnActionSaveRequest req) {
        FormDefEntity form = binding.requirePublishedForm(formId);
        validateDraftBasics(req, true);
        Long dup = actionMapper.selectCount(Wrappers.<TxnActionEntity>lambdaQuery()
                .eq(TxnActionEntity::getFormId, form.getId())
                .eq(TxnActionEntity::getActionKey, req.actionKey().trim()));
        if (dup != null && dup > 0) {
            throw new BaseException(FormErrorCode.FORM_KEY_DUPLICATE, "该表单下动作标识已存在：" + req.actionKey());
        }
        TxnActionEntity entity = new TxnActionEntity();
        entity.setId(idGenerator.generate());
        entity.setFormId(form.getId());
        entity.setActionKey(req.actionKey().trim());
        entity.setName(req.name().trim());
        entity.setActionType(req.actionType().trim().toUpperCase());
        entity.setStatus(STATUS_DRAFT);
        entity.setDescription(req.description());
        entity.setConfigJson(writeJson(req.config()));
        actionMapper.insert(entity);
        return toView(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    public TxnActionView update(String id, TxnActionSaveRequest req) {
        TxnActionEntity entity = requireAction(id);
        validateDraftBasics(req, false);
        if (req.actionKey() != null && !req.actionKey().trim().equals(entity.getActionKey())) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "动作标识创建后不可修改");
        }
        if (req.actionType() != null && !req.actionType().trim().equalsIgnoreCase(entity.getActionType())
                && !STATUS_DRAFT.equals(entity.getStatus())) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "已发布动作的类型不可修改");
        }
        if (req.name() != null && !req.name().isBlank()) {
            entity.setName(req.name().trim());
        }
        if (req.actionType() != null && !req.actionType().isBlank() && STATUS_DRAFT.equals(entity.getStatus())) {
            entity.setActionType(req.actionType().trim().toUpperCase());
        }
        if (req.description() != null) {
            entity.setDescription(req.description());
        }
        if (req.config() != null) {
            entity.setConfigJson(writeJson(req.config()));
        }
        actionMapper.updateById(entity);
        return toView(entity);
    }

    // ==================== 发布校验 ====================

    /** 返回全部结构化错误；空列表=通过。 */
    public List<TxnPublishError> validate(String id) {
        TxnActionEntity action = requireAction(id);
        FormDefEntity form = binding.requirePublishedForm(action.getFormId());
        TxnActionConfig cfg = readConfig(action.getConfigJson());
        List<TxnPublishError> errors = validateConfig(form, action.getActionType(), cfg);
        if (action.getName() == null || action.getName().isBlank()) {
            errors.add(TxnPublishError.of(FormErrorCode.ACTION_CONFIG_INVALID, "name", "动作名称不能为空"));
        }
        return errors;
    }

    @Transactional(rollbackFor = Exception.class)
    public TxnActionView publish(String id) {
        TxnActionEntity action = requireAction(id);
        FormDefEntity form = binding.requirePublishedForm(action.getFormId());
        TxnActionConfig cfg = readConfig(action.getConfigJson());
        List<TxnPublishError> errors = validateConfig(form, action.getActionType(), cfg);
        if (action.getName() == null || action.getName().isBlank()) {
            errors.add(TxnPublishError.of(FormErrorCode.ACTION_CONFIG_INVALID, "name", "动作名称不能为空"));
        }
        if (!errors.isEmpty()) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID,
                    "动作配置未通过发布校验（共 " + errors.size() + " 项）："
                            + errors.get(0).field() + " " + errors.get(0).message());
        }

        int nextVersion = (action.getCurrentVersion() == null ? 0 : action.getCurrentVersion()) + 1;
        TxnActionVersionEntity version = new TxnActionVersionEntity();
        version.setId(idGenerator.generate());
        version.setActionId(action.getId());
        version.setVersionNo(nextVersion);
        version.setFormVersion(form.getFormVersion() == null ? 1 : form.getFormVersion());
        version.setConfigJson(action.getConfigJson());
        version.setPublishedBy(currentUserId());
        version.setPublishedAt(LocalDateTime.now());
        versionMapper.insert(version);

        action.setStatus(STATUS_PUBLISHED);
        action.setCurrentVersion(nextVersion);
        actionMapper.updateById(action);
        log.info("事务动作发布：actionId={}, actionKey={}, version={}", action.getId(), action.getActionKey(), nextVersion);
        return toView(action);
    }

    @Transactional(rollbackFor = Exception.class)
    public TxnActionView disable(String id) {
        TxnActionEntity action = requireAction(id);
        if (!STATUS_PUBLISHED.equals(action.getStatus())) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "仅已发布动作可停用");
        }
        action.setStatus(STATUS_DISABLED);
        actionMapper.updateById(action);
        return toView(action);
    }

    @Transactional(rollbackFor = Exception.class)
    public TxnActionView enable(String id) {
        TxnActionEntity action = requireAction(id);
        if (!STATUS_DISABLED.equals(action.getStatus())) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "仅已停用动作可启用");
        }
        action.setStatus(STATUS_PUBLISHED);
        actionMapper.updateById(action);
        return toView(action);
    }

    /** 当前已发布版本配置（调用时版本固定语义的唯一配置来源）。 */
    public TxnActionVersionEntity requireCurrentVersion(TxnActionEntity action) {
        if (STATUS_DISABLED.equals(action.getStatus())) {
            throw new BaseException(FormErrorCode.ACTION_DISABLED, "事务动作已停用，不能发起新调用");
        }
        if (!STATUS_PUBLISHED.equals(action.getStatus()) || action.getCurrentVersion() == null) {
            throw new BaseException(FormErrorCode.ACTION_NOT_FOUND, "事务动作未发布，不能调用");
        }
        TxnActionVersionEntity version = versionMapper.selectOne(
                Wrappers.<TxnActionVersionEntity>lambdaQuery()
                        .eq(TxnActionVersionEntity::getActionId, action.getId())
                        .eq(TxnActionVersionEntity::getVersionNo, action.getCurrentVersion()));
        if (version == null) {
            throw new BaseException(FormErrorCode.ACTION_NOT_FOUND, "动作版本快照缺失，请重新发布");
        }
        return version;
    }

    // ==================== 内部 ====================

    private void validateDraftBasics(TxnActionSaveRequest req, boolean requireKey) {
        if (req == null) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "请求体缺失");
        }
        if (requireKey || req.actionKey() != null) {
            if (req.actionKey() == null || !ACTION_KEY_PATTERN.matcher(req.actionKey().trim()).matches()) {
                throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID,
                        "动作标识不合法（字母开头，字母/数字/下划线，≤100）");
            }
        }
        if (req.actionType() == null || !ACTION_TYPES.contains(req.actionType().trim().toUpperCase())) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID,
                    "动作类型必须为 RESERVE/CONFIRM/RELEASE/ADJUST");
        }
        if (req.name() == null || req.name().isBlank() || req.name().trim().length() > 200) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "动作名称必填且不超过 200 字符");
        }
    }

    private List<TxnPublishError> validateConfig(FormDefEntity form, String actionType, TxnActionConfig cfg) {
        List<TxnPublishError> errors = new ArrayList<>();
        String type = actionType == null ? "" : actionType.toUpperCase();
        if (!ACTION_TYPES.contains(type)) {
            errors.add(TxnPublishError.of(FormErrorCode.ACTION_CONFIG_INVALID, "actionType", "动作类型不合法"));
            return errors;
        }
        if (cfg == null) {
            errors.add(TxnPublishError.of(FormErrorCode.ACTION_CONFIG_INVALID, "config", "动作配置为空"));
            return errors;
        }
        Map<String, FormFieldValidator.FieldDef> defs = binding.fieldDefs(form.getId());
        if (defs.isEmpty()) {
            errors.add(TxnPublishError.of(FormErrorCode.ACTION_CONFIG_INVALID, "formId", "表单定义为空，无法绑定字段"));
            return errors;
        }

        boolean needReserved = TYPE_RESERVE.equals(type) || TYPE_CONFIRM.equals(type) || TYPE_RELEASE.equals(type);
        String balance = cfg.getBalanceField();
        String reserved = cfg.getReservedField();

        if (balance == null || balance.isBlank()) {
            errors.add(TxnPublishError.of(FormErrorCode.ACTION_FIELD_BINDING_INVALID, "config.balanceField",
                    "余额字段必填"));
        } else {
            checkNumberField(defs, balance, "config.balanceField", errors);
        }
        if (needReserved) {
            if (reserved == null || reserved.isBlank()) {
                errors.add(TxnPublishError.of(FormErrorCode.ACTION_FIELD_BINDING_INVALID, "config.reservedField",
                        "预占字段必填"));
            } else {
                checkNumberField(defs, reserved, "config.reservedField", errors);
            }
        }
        if (balance != null && balance.equals(reserved)) {
            errors.add(TxnPublishError.of(FormErrorCode.ACTION_FIELD_BINDING_INVALID, "config.reservedField",
                    "余额字段与预占字段不能相同"));
        }
        if (TYPE_RESERVE.equals(type)) {
            Long ttl = cfg.getExpiresInSeconds();
            if (ttl == null || ttl <= 0 || ttl > MAX_EXPIRES_IN_SECONDS) {
                errors.add(TxnPublishError.of(FormErrorCode.ACTION_CONFIG_INVALID, "config.expiresInSeconds",
                        "预占有效期必填且范围为 1—2592000 秒（30 天）"));
            }
        }
        Integer scale = cfg.getQuantityScale();
        if (scale != null && (scale < 0 || scale > 6)) {
            errors.add(TxnPublishError.of(FormErrorCode.ACTION_CONFIG_INVALID, "config.quantityScale",
                    "数量精度范围为 0—6 位小数"));
        }
        List<String> keys = cfg.getKeyFields();
        if (keys != null) {
            Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.get(i);
                String path = "config.keyFields[" + i + "]";
                if (k == null || k.isBlank()) {
                    errors.add(TxnPublishError.of(FormErrorCode.ACTION_FIELD_BINDING_INVALID, path, "业务键不能为空"));
                    continue;
                }
                if (!seen.add(k)) {
                    errors.add(TxnPublishError.of(FormErrorCode.ACTION_FIELD_BINDING_INVALID, path, "业务键重复：" + k));
                }
                FormFieldValidator.FieldDef def = defs.get(k);
                if (def == null) {
                    errors.add(TxnPublishError.of(FormErrorCode.ACTION_FIELD_BINDING_INVALID, path,
                            "业务键字段不在表单定义中：" + k));
                } else if ("TABLE".equalsIgnoreCase(def.type())) {
                    errors.add(TxnPublishError.of(FormErrorCode.ACTION_FIELD_BINDING_INVALID, path,
                            "业务键不能为表格类型：" + k));
                }
                if (k != null && (k.equals(balance) || k.equals(reserved))) {
                    errors.add(TxnPublishError.of(FormErrorCode.ACTION_FIELD_BINDING_INVALID, path,
                            "业务键不能与余额/预占字段相同：" + k));
                }
            }
        }
        return errors;
    }

    private void checkNumberField(Map<String, FormFieldValidator.FieldDef> defs, String field,
                                  String path, List<TxnPublishError> errors) {
        FormFieldValidator.FieldDef def = defs.get(field);
        if (def == null) {
            errors.add(TxnPublishError.of(FormErrorCode.ACTION_FIELD_BINDING_INVALID, path,
                    "字段不在表单定义中：" + field));
        } else if (!"NUMBER".equalsIgnoreCase(def.type())) {
            errors.add(TxnPublishError.of(FormErrorCode.ACTION_FIELD_BINDING_INVALID, path,
                    "字段必须为数字类型：" + field));
        }
    }

    public TxnActionConfig readConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(configJson, TxnActionConfig.class);
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "动作配置解析失败：" + e.getMessage());
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "动作配置序列化失败");
        }
    }

    private Long currentUserId() {
        com.sw.ck.security.holder.LoginUser user = com.sw.ck.security.holder.LoginUserHolder.get();
        return user == null ? null : user.getUserId();
    }

    private TxnActionView toView(TxnActionEntity e) {
        return new TxnActionView(e.getId(), e.getFormId(), e.getActionKey(), e.getName(), e.getActionType(),
                e.getStatus(), e.getCurrentVersion(), e.getDescription(), e.getConfigJson(), e.getUpdateTime());
    }
}

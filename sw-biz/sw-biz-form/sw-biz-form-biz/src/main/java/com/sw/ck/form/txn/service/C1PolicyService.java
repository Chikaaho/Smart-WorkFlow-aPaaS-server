package com.sw.ck.form.txn.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableSql;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.service.FormFieldValidator;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.txn.entity.C1PolicyEntity;
import com.sw.ck.form.txn.mapper.C1PolicyMapper;
import com.sw.ck.form.txn.model.C1PolicyModel;
import com.sw.ck.form.txn.model.C1PolicyView;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C1 关键数据保护策略：声明、既有数据校验与写路径断言。
 * <ul>
 *   <li>启用后普通表单写入口（提交/更新/删除）拒绝受保护字段，写入只经受控事务动作；</li>
 *   <li>启用时先校验既有数据满足非负约束，失败拒绝启用（不静默放行）。</li>
 * </ul>
 */
@Service
public class C1PolicyService {

    private static final Logger log = LoggerFactory.getLogger(C1PolicyService.class);

    private final C1PolicyMapper policyMapper;
    private final TxnFormBinding binding;
    private final FormIdGenerator idGenerator;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public C1PolicyService(C1PolicyMapper policyMapper,
                           TxnFormBinding binding,
                           FormIdGenerator idGenerator,
                           JdbcTemplate jdbcTemplate,
                           ObjectMapper objectMapper) {
        this.policyMapper = policyMapper;
        this.binding = binding;
        this.idGenerator = idGenerator;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ==================== 查询 ====================

    public C1PolicyView get(String formId) {
        binding.requirePublishedForm(formId);
        C1PolicyEntity entity = findPolicy(formId);
        if (entity == null) {
            return new C1PolicyView(null, formId, false, null, null, null);
        }
        return toView(entity);
    }

    // ==================== 保存（启用校验） ====================

    @Transactional(rollbackFor = Exception.class)
    public C1PolicyView save(String formId, C1PolicyModel model) {
        FormDefEntity form = binding.requirePublishedForm(formId);
        if (model == null || model.getEnabled() == null) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "需要显式声明 enabled");
        }
        if (model.getUnsupportedKeys() != null && !model.getUnsupportedKeys().isEmpty()) {
            String keys = String.join("、", model.getUnsupportedKeys().keySet());
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID,
                    "C1 策略包含模型不支持的键：" + keys + "（声明未被受理，未启用保护）");
        }
        Map<String, FormFieldValidator.FieldDef> defs = binding.fieldDefs(form.getId());
        boolean enabled = Boolean.TRUE.equals(model.getEnabled());
        C1PolicyModel normalized = new C1PolicyModel();
        normalized.setEnabled(enabled);
        normalized.setProtectedFields(model.getProtectedFields());
        normalized.setBalanceField(blankToNull(model.getBalanceField()));
        normalized.setReservedField(blankToNull(model.getReservedField()));
        normalized.setNonNegativeAvailable(model.getNonNegativeAvailable() == null
                ? Boolean.TRUE : model.getNonNegativeAvailable());

        if (enabled) {
            Set<String> protectedFields = new HashSet<>();
            if (normalized.getProtectedFields() == null || normalized.getProtectedFields().isEmpty()) {
                throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "启用 C1 保护至少声明一个受保护字段");
            }
            for (String f : normalized.getProtectedFields()) {
                if (f == null || f.isBlank()) {
                    throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "受保护字段不能为空");
                }
                FormFieldValidator.FieldDef def = defs.get(f);
                if (def == null) {
                    throw new BaseException(FormErrorCode.ACTION_FIELD_BINDING_INVALID,
                            "受保护字段不在表单定义中：" + f);
                }
                if ("TABLE".equalsIgnoreCase(def.type())) {
                    throw new BaseException(FormErrorCode.ACTION_FIELD_BINDING_INVALID,
                            "受保护字段不能为表格类型：" + f);
                }
                protectedFields.add(f);
            }
            if (normalized.getBalanceField() != null && !protectedFields.contains(normalized.getBalanceField())) {
                throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID,
                        "余额字段必须列入受保护字段：" + normalized.getBalanceField());
            }
            if (normalized.getReservedField() != null && !protectedFields.contains(normalized.getReservedField())) {
                throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID,
                        "预占字段必须列入受保护字段：" + normalized.getReservedField());
            }
            if (Boolean.TRUE.equals(normalized.getNonNegativeAvailable())
                    && normalized.getBalanceField() != null && normalized.getReservedField() != null) {
                validateExistingData(form, defs, normalized);
            }
        }

        C1PolicyEntity entity = findPolicy(formId);
        String json = writeJson(normalized);
        if (entity == null) {
            entity = new C1PolicyEntity();
            entity.setId(idGenerator.generate());
            entity.setFormId(formId);
            entity.setEnabled(enabled ? 1 : 0);
            entity.setPolicyJson(json);
            entity.setAppliedAt(enabled ? LocalDateTime.now() : null);
            policyMapper.insert(entity);
        } else {
            entity.setEnabled(enabled ? 1 : 0);
            entity.setPolicyJson(json);
            entity.setAppliedAt(enabled ? LocalDateTime.now() : null);
            policyMapper.updateById(entity);
        }
        log.info("C1 策略保存：formId={}, enabled={}, protectedFields={}", formId, enabled,
                normalized.getProtectedFields());
        return toView(entity);
    }

    /**
     * 启用前校验既有数据满足 可用=余额-预占 >= 0 且余额/预占均有值；不满足则拒绝启用。
     * <p>空值同样拒绝：NULL 参与不了可用量守卫（比较恒假），启用后该记录无法预占/调整，
     * 受控动作也无法补救（动作自身被同一守卫拒绝），必须在启用闸门显式拦截。</p>
     */
    private void validateExistingData(FormDefEntity form,
                                      Map<String, FormFieldValidator.FieldDef> defs,
                                      C1PolicyModel model) {
        String balanceCol = binding.requireNumberColumn(defs, model.getBalanceField(), "余额");
        String reservedCol = binding.requireNumberColumn(defs, model.getReservedField(), "预占");
        String table = form.getPhysicalTableName();
        Long tenantId = currentTenantId();
        String balance = DynamicTableSql.quote(balanceCol);
        String reserved = DynamicTableSql.quote(reservedCol);
        String sql = "SELECT COUNT(*) FROM " + DynamicTableSql.quote(table)
                + " WHERE " + DynamicTableSql.quote("deleted") + " = 0"
                + " AND " + DynamicTableSql.quote("tenant_id") + " = ?"
                + " AND (" + balance + " IS NULL OR " + reserved + " IS NULL"
                + " OR (" + balance + " - " + reserved + ") < 0)";
        long bad;
        try {
            bad = DynamicTableSql.queryForLong(jdbcTemplate, table, sql, tenantId);
        } catch (BaseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BaseException(FormErrorCode.C1_POLICY_INVALID,
                    "既有数据约束校验失败：" + e.getMessage());
        }
        if (bad > 0) {
            throw new BaseException(FormErrorCode.C1_POLICY_INVALID,
                    "现有 " + bad + " 条记录不满足 可用=余额-预占 >= 0（含空值），不能启用保护");
        }
    }

    // ==================== 写路径断言（供表单写入口钩子调用） ====================

    /** 普通写入口字段保护：命中受保护字段即拒绝；未启用策略时为无操作。 */
    public void assertDirectWriteAllowed(String formId, Collection<String> fieldNames) {
        C1PolicyModel model = enabledPolicy(formId);
        if (model == null || fieldNames == null || fieldNames.isEmpty()) {
            return;
        }
        for (String f : fieldNames) {
            if (model.getProtectedFields() != null && model.getProtectedFields().contains(f)) {
                throw new BaseException(FormErrorCode.C1_WRITE_PROTECTED,
                        "字段 " + f + " 为 C1 受保护数据，只能通过受控事务动作写入");
            }
        }
    }

    /**
     * 整量写入闸门（表单提交 INSERT 与表单更新 UPDATE 共用）。
     * <p>这两条写路径实际覆盖全部用户列——INSERT 显式写全列、UPDATE 按字段定义整量覆盖，
     * 未提交的受保护字段会被写成 NULL；因此只检查「提交的键」会留下省略字段的静默改写路径。
     * 受保护模型必须整体拒绝，受保护字段仅能由受控事务动作写入。</p>
     */
    public void assertBulkWriteAllowed(String formId, Map<String, FormFieldValidator.FieldDef> fieldDefs) {
        C1PolicyModel model = enabledPolicy(formId);
        if (model == null || fieldDefs == null || fieldDefs.isEmpty()) {
            return;
        }
        List<String> writeColumns = new java.util.ArrayList<>();
        for (Map.Entry<String, FormFieldValidator.FieldDef> entry : fieldDefs.entrySet()) {
            String type = entry.getValue().type();
            if ("TABLE".equalsIgnoreCase(type) || "LABEL".equalsIgnoreCase(type)) {
                continue; // 不落主表用户列
            }
            writeColumns.add(entry.getKey());
        }
        assertDirectWriteAllowed(formId, writeColumns);
    }

    /** 受保护模型记录不允许直接删除（只能经受控动作结算或明细清理流程）。 */
    public void assertDeleteAllowed(String formId) {
        C1PolicyModel model = enabledPolicy(formId);
        if (model != null) {
            throw new BaseException(FormErrorCode.C1_WRITE_PROTECTED,
                    "该表单已启用 C1 保护，记录不能直接删除，请通过受控事务动作处理");
        }
    }

    public boolean isEnabled(String formId) {
        return enabledPolicy(formId) != null;
    }

    private C1PolicyModel enabledPolicy(String formId) {
        if (formId == null) {
            return null;
        }
        C1PolicyEntity entity = findPolicy(formId);
        if (entity == null || entity.getEnabled() == null || entity.getEnabled() != 1) {
            return null;
        }
        try {
            return objectMapper.readValue(entity.getPolicyJson(), C1PolicyModel.class);
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.C1_POLICY_INVALID, "C1 策略解析失败：" + e.getMessage());
        }
    }

    private C1PolicyEntity findPolicy(String formId) {
        return policyMapper.selectOne(Wrappers.<C1PolicyEntity>lambdaQuery()
                .eq(C1PolicyEntity::getFormId, formId));
    }

    private Long currentTenantId() {
        LoginUser user = LoginUserHolder.get();
        return user == null ? 0L : user.getTenantId();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "C1 策略序列化失败");
        }
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private C1PolicyView toView(C1PolicyEntity e) {
        return new C1PolicyView(e.getId(), e.getFormId(), e.getEnabled() != null && e.getEnabled() == 1,
                e.getPolicyJson(), e.getAppliedAt(), e.getUpdateTime());
    }
}

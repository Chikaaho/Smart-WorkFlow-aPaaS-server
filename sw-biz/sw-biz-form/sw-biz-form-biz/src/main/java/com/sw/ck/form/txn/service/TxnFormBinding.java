package com.sw.ck.form.txn.service;

import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.dynamic.DynamicTableSql;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.service.FormFieldValidator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事务动作-表单绑定解析：解析已发布表单的字段定义与受控物理列名。
 * <p>所有动态宽表列名一律经 {@link DynamicTableSql#requireColumn} 白名单单出口。</p>
 */
@Component
public class TxnFormBinding {

    /** 表单状态：已发布 */
    public static final String FORM_STATUS_PUBLISHED = "PUBLISHED";

    private final FormDefMapper formDefMapper;
    private final FormFieldValidator formFieldValidator;

    public TxnFormBinding(FormDefMapper formDefMapper, FormFieldValidator formFieldValidator) {
        this.formDefMapper = formDefMapper;
        this.formFieldValidator = formFieldValidator;
    }

    /** 加载表单定义；要求存在且已发布。 */
    public FormDefEntity requirePublishedForm(String formId) {
        FormDefEntity form = formId == null ? null : formDefMapper.selectById(formId);
        if (form == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND, "表单不存在");
        }
        if (!FORM_STATUS_PUBLISHED.equals(form.getStatus())) {
            throw new BaseException(FormErrorCode.FORM_NOT_PUBLISHED, "表单未发布，不能配置事务动作");
        }
        if (form.getPhysicalTableName() == null || form.getPhysicalTableName().isBlank()) {
            throw new BaseException(FormErrorCode.PUBLISH_FAILED, "表单数据表未就绪");
        }
        DynamicTableSql.requireTableName(form.getPhysicalTableName());
        return form;
    }

    /** 主表字段定义（name → FieldDef）。 */
    public Map<String, FormFieldValidator.FieldDef> fieldDefs(String formId) {
        return new LinkedHashMap<>(formFieldValidator.loadAndParseFieldDefs(formId, Map.of()));
    }

    /**
     * 解析 NUMBER 绑定列为受控物理列名；字段不存在或非 NUMBER 抛业务异常。
     */
    public String requireNumberColumn(Map<String, FormFieldValidator.FieldDef> defs,
                                      String logicalName, String label) {
        String col = requireColumn(defs, logicalName, label);
        FormFieldValidator.FieldDef def = defs.get(logicalName);
        if (!FieldType.NUMBER.name().equalsIgnoreCase(def.type())) {
            throw new BaseException(FormErrorCode.ACTION_FIELD_BINDING_INVALID,
                    label + "字段必须为数字类型：" + logicalName);
        }
        return col;
    }

    /** 解析绑定列（不限定类型）；用于业务键字段。 */
    public String requireColumn(Map<String, FormFieldValidator.FieldDef> defs,
                                String logicalName, String label) {
        if (logicalName == null || logicalName.isBlank()) {
            throw new BaseException(FormErrorCode.ACTION_FIELD_BINDING_INVALID, label + "字段未声明");
        }
        FormFieldValidator.FieldDef def = defs.get(logicalName);
        if (def == null) {
            throw new BaseException(FormErrorCode.ACTION_FIELD_BINDING_INVALID,
                    label + "字段不在表单定义中：" + logicalName);
        }
        FieldType type;
        try {
            type = FieldType.valueOf(def.type().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BaseException(FormErrorCode.ACTION_FIELD_BINDING_INVALID,
                    label + "字段类型不受支持：" + logicalName);
        }
        if (type == FieldType.TABLE) {
            throw new BaseException(FormErrorCode.ACTION_FIELD_BINDING_INVALID,
                    label + "字段不能绑定表格类型：" + logicalName);
        }
        return DynamicTableSql.requireColumn(logicalName, type);
    }

    /** 批量解析业务键列；保持声明顺序。 */
    public List<String> resolveKeyColumns(Map<String, FormFieldValidator.FieldDef> defs, List<String> keyFields) {
        List<String> cols = new ArrayList<>();
        if (keyFields == null) {
            return cols;
        }
        for (String k : keyFields) {
            cols.add(requireColumn(defs, k, "业务键"));
        }
        return cols;
    }
}

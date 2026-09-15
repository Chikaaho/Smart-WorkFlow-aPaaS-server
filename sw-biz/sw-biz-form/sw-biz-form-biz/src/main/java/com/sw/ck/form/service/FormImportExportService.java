package com.sw.ck.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.form.api.dto.FormDataQueryRequest;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.ColumnValidation;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 表单数据导入导出服务。
 *
 * <p>提供以下功能：</p>
 * <ul>
 *   <li>模板下载：根据表单定义生成 .xlsx 模板（含模板签名，表单结构变更后旧模板失效）</li>
 *   <li>数据导入：解析 .xlsx 文件并批量导入数据（整批原子失败）</li>
 *   <li>数据导出：按查询条件导出数据为 .xlsx 文件（租户 + 数据权限 + 有界）</li>
 * </ul>
 *
 * <h3>契约要点</h3>
 * <ul>
 *   <li>模板带签名（formKey + formVersion + 字段映射指纹），导入时校验，不匹配即拒绝</li>
 *   <li>导入/导出全链路公式安全：以 = + - @ 开头的业务文本始终按文本处理</li>
 *   <li>REFERENCE：导入存稳定记录 id（校验存在性 + 租户边界），导出解析为业务显示值</li>
 *   <li>TABLE 子表：模板含独立 sheet，导入按主行号聚合为子行列表，导出按记录展开子表 sheet</li>
 *   <li>导入整批原子：任一行失败整批回滚，零落库，同时返回行级错误明细</li>
 * </ul>
 */
@Service
public class FormImportExportService {

    private static final Logger log = LoggerFactory.getLogger(FormImportExportService.class);

    /** 导入行数硬上限（含） */
    public static final int MAX_IMPORT_ROWS = 500;

    /** 导出行数硬上限（含） */
    public static final int MAX_EXPORT_ROWS = 1000;

    /** 导入文件字节硬上限（5MB） */
    public static final long MAX_IMPORT_FILE_BYTES = 5L * 1024 * 1024;

    /** 模板签名自定义属性名 */
    static final String TEMPLATE_SIG_PROPERTY = "SW_FORM_TEMPLATE_SIG";

    /** 子表 sheet 中主行号列名（稳定映射标识） */
    static final String SUB_ROW_NO_KEY = "__row_no";

    /** 主 sheet 名称 */
    static final String MAIN_SHEET_NAME = "模板";

    private static final String ROW_NO_LABEL = "数据行号";

    private final FormDefService formDefService;
    private final FormConfigMapper formConfigMapper;
    private final FormDefMapper formDefMapper;
    private final ObjectMapper objectMapper;
    private final FormSubmitService formSubmitService;
    private final FormFieldValidator formFieldValidator;
    private final FormDataQueryService formDataQueryService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final LoginContextProvider loginContextProvider;
    private final DeptScopeProvider deptScopeProvider;

    public FormImportExportService(FormDefService formDefService,
                                   FormConfigMapper formConfigMapper,
                                   FormDefMapper formDefMapper,
                                   ObjectMapper objectMapper,
                                   FormSubmitService formSubmitService,
                                   FormFieldValidator formFieldValidator,
                                   FormDataQueryService formDataQueryService,
                                   TransactionTemplate transactionTemplate,
                                   JdbcTemplate jdbcTemplate,
                                   LoginContextProvider loginContextProvider,
                                   DeptScopeProvider deptScopeProvider) {
        this.formDefService = formDefService;
        this.formConfigMapper = formConfigMapper;
        this.formDefMapper = formDefMapper;
        this.objectMapper = objectMapper;
        this.formSubmitService = formSubmitService;
        this.formFieldValidator = formFieldValidator;
        this.formDataQueryService = formDataQueryService;
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.loginContextProvider = loginContextProvider;
        this.deptScopeProvider = deptScopeProvider;
    }

    // ==================== 字段元数据 ====================

    /**
     * 字段元数据。
     *
     * @param name         逻辑字段名
     * @param label        显示名
     * @param type         字段类型
     * @param mappingKey   稳定映射标识（物理列名）
     * @param targetFormId REFERENCE 目标表单 formKey（仅 REFERENCE 非 null）
     * @param subFields    TABLE 子字段（仅 TABLE 非 null）
     */
    record FieldMeta(
            String name,
            String label,
            FieldType type,
            String mappingKey,
            String targetFormId,
            List<FieldMeta> subFields
    ) {
        FieldMeta(String name, String label, FieldType type, String mappingKey) {
            this(name, label, type, mappingKey, null, null);
        }
    }

    // ==================== 定义解析 ====================

    /**
     * 解析表单定义中的字段。
     *
     * <p>主表列字段 = 全部 enabled 且非 TABLE 的类型（含 RICH_TEXT，方向语义为纯文本可保持）。
     * TABLE 类型不产生主表列，作为子表 sheet 单独承载。</p>
     */
    private List<FieldMeta> parseFields(String definitionJson) {
        List<FieldMeta> fields = new ArrayList<>();
        List<FieldMeta> tableFields = new ArrayList<>();
        parseFieldsInternal(definitionJson, fields, tableFields);
        return fields;
    }

    private Map<String, List<FieldMeta>> parseTableFields(String definitionJson) {
        List<FieldMeta> fields = new ArrayList<>();
        List<FieldMeta> tableFields = new ArrayList<>();
        parseFieldsInternal(definitionJson, fields, tableFields);
        Map<String, List<FieldMeta>> result = new LinkedHashMap<>();
        for (FieldMeta tf : tableFields) {
            result.put(tf.name(), tf.subFields() != null ? tf.subFields() : List.of());
        }
        return result;
    }

    private void parseFieldsInternal(String definitionJson,
                                     List<FieldMeta> mainFieldsOut,
                                     List<FieldMeta> tableFieldsOut) {
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode fieldsArray = root.get("fields");
            if (fieldsArray == null || !fieldsArray.isArray()) {
                return;
            }

            for (JsonNode fieldNode : fieldsArray) {
                String name = fieldNode.has("name") ? fieldNode.get("name").asText() : null;
                if (name == null || name.isBlank()) continue;

                String typeStr = fieldNode.has("type") ? fieldNode.get("type").asText() : "TEXT";
                String label = fieldNode.has("label") ? fieldNode.get("label").asText() : name;

                FieldType fieldType;
                try {
                    fieldType = FieldType.valueOf(typeStr);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown field type '{}' for field '{}', skipping", typeStr, name);
                    continue;
                }

                if (!fieldType.isEnabled()) continue;

                if (fieldType == FieldType.TABLE) {
                    // TABLE 子表：解析子字段
                    List<FieldMeta> subFields = new ArrayList<>();
                    JsonNode subArray = fieldNode.get("subFields");
                    if (subArray != null && subArray.isArray()) {
                        for (JsonNode sub : subArray) {
                            String subName = sub.has("name") ? sub.get("name").asText() : null;
                            if (subName == null || subName.isBlank()) continue;
                            String subTypeStr = sub.has("type") ? sub.get("type").asText() : "TEXT";
                            String subLabel = sub.has("label") ? sub.get("label").asText() : subName;
                            FieldType subType;
                            try {
                                subType = FieldType.valueOf(subTypeStr);
                            } catch (IllegalArgumentException e) {
                                continue;
                            }
                            if (!subType.isEnabled() || subType == FieldType.TABLE) continue;
                            subFields.add(new FieldMeta(subName, subLabel, subType,
                                    ColumnValidation.physicalColumnName(subName, subType)));
                        }
                    }
                    tableFieldsOut.add(new FieldMeta(name, label, fieldType, name, null, subFields));
                    continue;
                }

                String mappingKey = ColumnValidation.physicalColumnName(name, fieldType);
                String targetFormId = fieldNode.has("targetFormId") ? fieldNode.get("targetFormId").asText() : null;
                mainFieldsOut.add(new FieldMeta(name, label, fieldType, mappingKey, targetFormId, null));
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse form definition JSON", e);
        }
    }

    // ==================== 模板签名 ====================

    /**
     * 计算模板签名：formKey + formVersion + 主字段映射 + 子表字段映射指纹。
     * 表单结构（字段增删改/类型变更）或重新发布（formVersion 递增）都会使签名变化。
     */
    private String buildTemplateSignature(FormDefDTO formDef,
                                          List<FieldMeta> mainFields,
                                          Map<String, List<FieldMeta>> tableFields) {
        StringBuilder sb = new StringBuilder();
        sb.append(formDef.getFormKey()).append('@').append(formDef.getFormVersion());
        for (FieldMeta f : mainFields) {
            sb.append('|').append(f.mappingKey()).append(':').append(f.type());
        }
        tableFields.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    sb.append('|').append('#').append(e.getKey()).append('[');
                    for (FieldMeta sub : e.getValue()) {
                        sb.append(sub.mappingKey()).append(':').append(sub.type()).append(',');
                    }
                    sb.append(']');
                });
        return sb.toString();
    }

    private String readTemplateSignature(Workbook workbook) {
        try {
            return ((XSSFWorkbook) workbook).getProperties().getCustomProperties()
                    .getProperty(TEMPLATE_SIG_PROPERTY).getLpwstr();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 模板生成 ====================

    /**
     * 生成表单模板。
     *
     * <p>主 sheet"模板"：第一行字段显示名，第二行字段映射标识。
     * TABLE 字段各占一个独立 sheet（sheet 名 = 字段名）：
     * 第一列为"数据行号"（对应主 sheet 数据行序号，从 1 起），其余为子字段两行表头。
     * 工作簿自定义属性携带模板签名，结构变更后旧模板导入即被拒绝。</p>
     */
    public byte[] generateTemplate(String formKey) {
        LoginUser loginUser = requireLogin();
        FormContext ctx = loadFormContext(formKey, "生成模板");

        List<FieldMeta> fields = ctx.mainFields();
        if (fields.isEmpty() && ctx.tableFields().isEmpty()) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "表单没有可录入字段");
        }

        String signature = buildTemplateSignature(ctx.formDef(), fields, ctx.tableFields());

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(MAIN_SHEET_NAME);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < fields.size(); i++) {
                headerRow.createCell(i).setCellValue(fields.get(i).label());
            }
            Row mappingRow = sheet.createRow(1);
            for (int i = 0; i < fields.size(); i++) {
                mappingRow.createCell(i).setCellValue(fields.get(i).mappingKey());
            }
            for (int i = 0; i < fields.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            // TABLE 子表 sheet
            for (Map.Entry<String, List<FieldMeta>> entry : ctx.tableFields().entrySet()) {
                Sheet subSheet = workbook.createSheet(entry.getKey());
                Row subHeader = subSheet.createRow(0);
                subHeader.createCell(0).setCellValue(ROW_NO_LABEL);
                for (int i = 0; i < entry.getValue().size(); i++) {
                    subHeader.createCell(i + 1).setCellValue(entry.getValue().get(i).label());
                }
                Row subMapping = subSheet.createRow(1);
                subMapping.createCell(0).setCellValue(SUB_ROW_NO_KEY);
                for (int i = 0; i < entry.getValue().size(); i++) {
                    subMapping.createCell(i + 1).setCellValue(entry.getValue().get(i).mappingKey());
                }
            }

            // 模板签名（结构变更 / 重新发布后旧模板失效的判据）
            ((XSSFWorkbook) workbook).getProperties().getCustomProperties()
                    .addProperty(TEMPLATE_SIG_PROPERTY, signature);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            log.info("Template generated: formKey={}, tenantId={}, signature={}",
                    formKey, loginUser.getTenantId(), signature);
            return outputStream.toByteArray();

        } catch (IOException e) {
            log.error("Failed to generate template for formKey={}", formKey, e);
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "生成模板失败: " + e.getMessage());
        }
    }

    // ==================== 导入 ====================

    /**
     * 导入表单数据。
     *
     * <p>语义：</p>
     * <ul>
     *   <li>模板签名校验：与表单当前结构/版本不符即整体拒绝（旧模板失效），零写入</li>
     *   <li>映射行必须与当前字段完全一致（顺序 + 无多余列），不得静默丢列</li>
     *   <li>采用新增数据语义，不提供更新、覆盖、合并或 upsert</li>
     *   <li>每行经过与单条新增一致的校验；整批原子失败：任一行失败全部回滚</li>
     *   <li>返回行/字段级错误反馈</li>
     * </ul>
     */
    public ImportResult importData(String formKey, InputStream inputStream) {
        requireLogin();
        FormContext ctx = loadFormContext(formKey, "导入数据");
        List<FieldMeta> fields = ctx.mainFields();
        Map<String, List<FieldMeta>> tableFields = ctx.tableFields();
        if (fields.isEmpty() && tableFields.isEmpty()) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "表单没有可录入字段");
        }

        ParsedWorkbook parsed = parseWorkbook(inputStream, ctx);
        List<ParsedRow> rows = parsed.rows();

        if (rows.isEmpty()) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "Excel 文件没有数据行");
        }
        if (rows.size() > MAX_IMPORT_ROWS) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                    "导入行数超限：当前 " + rows.size() + " 行，上限 " + MAX_IMPORT_ROWS + " 行");
        }

        // 整批原子：任一行失败则全部回滚，零落库
        return transactionTemplate.execute(status -> {
            List<String> ids = new ArrayList<>();
            List<RowError> rowErrors = new ArrayList<>();

            for (ParsedRow parsedRow : rows) {
                Map<String, Object> rowData = new LinkedHashMap<>(parsedRow.mainData());
                // TABLE 子行挂到主行数据（submitForm 契约：字段名 → List<Map>）
                for (Map.Entry<String, List<Map<String, Object>>> e : parsedRow.tableData().entrySet()) {
                    rowData.put(e.getKey(), e.getValue());
                }

                try {
                    // REFERENCE 身份校验：稳定 id 存在性 + 租户边界（与落库同事务）
                    for (FieldMeta field : ctx.mainFields()) {
                        if (field.type() == FieldType.REFERENCE) {
                            validateReferenceValue(field, rowData.get(field.name()));
                        }
                    }

                    String recordId = formSubmitService.submitForm(formKey, rowData, null, null, null);
                    ids.add(recordId);
                } catch (BaseException e) {
                    rowErrors.add(new RowError(parsedRow.rowNum(), e.getMessage()));
                } catch (Exception e) {
                    log.error("Failed to import row {} for formKey={}", parsedRow.rowNum(), formKey, e);
                    rowErrors.add(new RowError(parsedRow.rowNum(), "导入失败: " + e.getMessage()));
                }
            }

            if (!rowErrors.isEmpty()) {
                // 任一行失败：标记回滚，整批不落库
                status.setRollbackOnly();
                log.info("Import rolled back for formKey={}: {} row(s) invalid, 0 persisted",
                        formKey, rowErrors.size());
                return new ImportResult(rows.size(), 0, rowErrors.size(), List.of(), rowErrors);
            }

            return new ImportResult(rows.size(), ids.size(), 0, ids, List.of());
        });
    }

    /**
     * REFERENCE 导入值校验：必须为目标表单当前租户下存在且未删除的记录 id。
     * 不存在或跨租户 id 一律拒绝（抛 BaseException，由行级错误收集）。
     */
    private void validateReferenceValue(FieldMeta field, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return; // 空值走必填校验
        }
        String id = String.valueOf(value);
        if (field.targetFormId() == null || field.targetFormId().isBlank()) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                    "关联字段 '" + field.name() + "' 未配置目标表单，无法导入");
        }
        FormDefDTO targetDef = formDefService.getFormDefByKey(field.targetFormId());
        if (targetDef == null || targetDef.getPhysicalTableName() == null
                || targetDef.getPhysicalTableName().isBlank()) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                    "关联字段 '" + field.name() + "' 的目标表单 '" + field.targetFormId() + "' 不存在");
        }
        Long tenantId = requireLogin().getTenantId();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"" + targetDef.getPhysicalTableName()
                        + "\" WHERE \"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?",
                Integer.class, id, tenantId);
        if (count == null || count == 0) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                    "关联字段 '" + field.name() + "' 引用的记录不存在或不具备引用权限: '" + id + "'");
        }
    }

    // ==================== 工作簿解析 ====================

    private record ParsedRow(int rowNum, Map<String, Object> mainData,
                             Map<String, List<Map<String, Object>>> tableData) {}

    private record ParsedWorkbook(List<ParsedRow> rows) {}

    /**
     * 解析工作簿：校验模板签名与映射行，逐行取值。
     * 签名不匹配 → 整体拒绝（旧模板失效）；映射行不一致 → 整体拒绝（防篡改/防静默丢列）。
     */
    private ParsedWorkbook parseWorkbook(InputStream inputStream, FormContext ctx) {
        List<FieldMeta> fields = ctx.mainFields();
        Map<String, List<FieldMeta>> tableFields = ctx.tableFields();
        String expectedSig = buildTemplateSignature(ctx.formDef(), fields, tableFields);

        Workbook workbook;
        try {
            workbook = WorkbookFactory.create(inputStream);
        } catch (Exception e) {
            log.error("Failed to open workbook", e);
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "无法解析文件：不是有效的 .xlsx 工作簿");
        }

        try (workbook) {
            // —— 模板签名校验（旧模板失效 / 非官方模板拒绝） ——
            String actualSig = readTemplateSignature(workbook);
            if (actualSig == null || !actualSig.equals(expectedSig)) {
                throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                        "模板已过期或与表单当前版本不匹配，请重新下载模板");
            }

            // —— 主 sheet 映射行精确比对 ——
            Sheet sheet = workbook.getSheet(MAIN_SHEET_NAME);
            if (sheet == null) {
                throw new BaseException(FormErrorCode.SUBMIT_FAILED, "Excel 文件缺少主表 sheet");
            }
            Row mappingRow = sheet.getRow(1);
            if (mappingRow == null) {
                throw new BaseException(FormErrorCode.SUBMIT_FAILED, "Excel 文件格式错误：缺少字段映射行");
            }
            assertMappingRow(mappingRow, 0, fields, "主表");

            // —— TABLE 子表 sheet 存在性与映射行校验 ——
            for (Map.Entry<String, List<FieldMeta>> entry : tableFields.entrySet()) {
                Sheet subSheet = workbook.getSheet(entry.getKey());
                if (subSheet == null) {
                    throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                            "Excel 文件缺少子表 sheet '" + entry.getKey() + "'");
                }
                Row subMapping = subSheet.getRow(1);
                if (subMapping == null) {
                    throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                            "子表 sheet '" + entry.getKey() + "' 缺少字段映射行");
                }
                if (!SUB_ROW_NO_KEY.equals(getCellValueAsString(subMapping.getCell(0)))) {
                    throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                            "子表 sheet '" + entry.getKey() + "' 首列必须为数据行号");
                }
                assertMappingRow(subMapping, 1, entry.getValue(), entry.getKey());
            }

            // —— 解析主数据行 ——
            Map<Integer, ParsedRow> rowsByIndex = new LinkedHashMap<>();
            for (int i = 2; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                Map<String, Object> rowData = new LinkedHashMap<>();
                boolean hasData = false;
                for (int c = 0; c < fields.size(); c++) {
                    FieldMeta field = fields.get(c);
                    Object value = getCellValue(row.getCell(c), field.type());
                    if (value != null) {
                        rowData.put(field.name(), value);
                        hasData = true;
                    }
                }
                if (hasData) {
                    rowsByIndex.put(i, new ParsedRow(i + 1, rowData, new LinkedHashMap<>()));
                }
            }

            // —— 解析子表行，按主行号聚合 ——
            for (Map.Entry<String, List<FieldMeta>> entry : tableFields.entrySet()) {
                Sheet subSheet = workbook.getSheet(entry.getKey());
                if (subSheet == null) continue;
                List<FieldMeta> subFields = entry.getValue();

                for (int i = 2; i <= subSheet.getLastRowNum(); i++) {
                    Row row = subSheet.getRow(i);
                    if (row == null) continue;

                    Cell rowNoCell = row.getCell(0);
                    Integer mainRowIndex = null;
                    if (rowNoCell != null && rowNoCell.getCellType() == CellType.NUMERIC) {
                        mainRowIndex = (int) rowNoCell.getNumericCellValue() + 1; // 主 sheet Excel 行号
                    }
                    if (mainRowIndex == null || !rowsByIndex.containsKey(mainRowIndex)) {
                        throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                                "子表 sheet '" + entry.getKey() + "' 第 " + (i + 1)
                                        + " 行的数据行号无效（必须对应主表数据行）");
                    }

                    Map<String, Object> subData = new LinkedHashMap<>();
                    for (int c = 0; c < subFields.size(); c++) {
                        FieldMeta sub = subFields.get(c);
                        Object value = getCellValue(row.getCell(c + 1), sub.type());
                        if (value != null) {
                            subData.put(sub.name(), value);
                        }
                    }
                    if (subData.isEmpty()) continue;

                    ParsedRow parsedRow = rowsByIndex.get(mainRowIndex);
                    parsedRow.tableData().computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(subData);
                }
            }

            return new ParsedWorkbook(new ArrayList<>(rowsByIndex.values()));

        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "解析 Excel 文件失败: " + e.getMessage());
        }
    }

    /**
     * 映射行精确比对：从 offset 列起每个映射标识必须与当前字段一致，且不得有多余非空列。
     * 防止映射篡改与静默丢列。
     */
    private void assertMappingRow(Row mappingRow, int offset, List<FieldMeta> fields, String sheetName) {
        for (int i = 0; i < fields.size(); i++) {
            String expected = fields.get(i).mappingKey();
            String actual = getCellValueAsString(mappingRow.getCell(offset + i));
            if (!expected.equals(actual)) {
                throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                        "模板映射不匹配（" + sheetName + " 第 " + (offset + i + 1) + " 列）：期望 '"
                                + expected + "'，实际 '" + actual + "'，请重新下载模板");
            }
        }
        Cell extra = mappingRow.getCell(offset + fields.size());
        if (extra != null && !getCellValueAsString(extra).isBlank()) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                    "模板存在未知列 '" + getCellValueAsString(extra) + "'，与表单当前定义不符，请重新下载模板");
        }
    }

    // ==================== 导出 ====================

    /**
     * 导出表单数据。
     *
     * <p>语义：</p>
     * <ul>
     *   <li>导出对象是指定表单在当前查询条件、租户边界和数据权限（DataScope）下可见的数据</li>
     *   <li>导出列与当前有效表单字段对应；REFERENCE 导出解析为业务显示值</li>
     *   <li>无数据时仍返回结构正确、可打开且只有表头的文件</li>
     *   <li>导出结果钳制在 {@link #MAX_EXPORT_ROWS} 行内</li>
     * </ul>
     */
    public byte[] exportData(String formKey, FormDataQueryRequest queryRequest) {
        requireLogin();
        FormContext ctx = loadFormContext(formKey, "导出数据");
        List<FieldMeta> fields = ctx.mainFields();
        Map<String, List<FieldMeta>> tableFields = ctx.tableFields();
        if (fields.isEmpty() && tableFields.isEmpty()) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "表单没有可导出字段");
        }

        // —— 查询参数与钳制 ——
        if (queryRequest == null) {
            queryRequest = new FormDataQueryRequest();
            queryRequest.setPageNum(1);
            queryRequest.setPageSize(MAX_EXPORT_ROWS);
        } else {
            long requestedSize = queryRequest.getPageSize();
            if (requestedSize <= 0 || requestedSize > MAX_EXPORT_ROWS) {
                queryRequest.setPageSize(MAX_EXPORT_ROWS);
            }
        }

        // —— 租户 + 数据权限 + 筛选条件下的有界查询（单页硬上限 = 导出行数上限） ——
        DataScopeFilter scopeFilter = DataScopeFilter.resolve(loginContextProvider, deptScopeProvider);
        com.sw.ck.common.page.PageResult<Map<String, Object>> queryResult =
                formDataQueryService.queryFormData(formKey, queryRequest, scopeFilter, MAX_EXPORT_ROWS);

        List<Map<String, Object>> records = queryResult.getRecords();
        if (records == null) {
            records = List.of();
        }
        if (records.size() > MAX_EXPORT_ROWS) {
            // 双保险：查询层已分页钳制，此处断言不越界
            records = records.subList(0, MAX_EXPORT_ROWS);
        }

        // —— REFERENCE 显示值解析（id → 目标表单业务显示值） ——
        Map<String, Map<String, String>> refDisplayCache = resolveReferenceDisplays(fields, records);

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("数据");

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < fields.size(); i++) {
                headerRow.createCell(i).setCellValue(fields.get(i).label());
            }

            for (int i = 0; i < records.size(); i++) {
                Row dataRow = sheet.createRow(i + 1);
                Map<String, Object> record = records.get(i);

                for (int j = 0; j < fields.size(); j++) {
                    FieldMeta field = fields.get(j);
                    Cell cell = dataRow.createCell(j);

                    if (field.type() == FieldType.REFERENCE) {
                        String id = asText(record.get(field.mappingKey()));
                        String display = (id == null) ? null
                                : refDisplayCache.getOrDefault(field.name(), Map.of()).get(id);
                        writeTextCell(cell, display != null ? display : id);
                    } else {
                        Object value = record.get(field.mappingKey());
                        setCellValue(cell, value, field.type());
                    }
                }
            }
            for (int i = 0; i < fields.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            // —— TABLE 子表 sheet：按记录展开子行 ——
            appendTableSheets(workbook, ctx, records, tableFields);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            log.info("Export completed: formKey={}, rows={} (cap {}), tableSheets={}",
                    formKey, records.size(), MAX_EXPORT_ROWS, tableFields.keySet());
            return outputStream.toByteArray();

        } catch (IOException e) {
            log.error("Failed to export data for formKey={}", formKey, e);
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "导出数据失败: " + e.getMessage());
        }
    }

    /**
     * 追加 TABLE 子表 sheet：每个 TABLE 字段一个 sheet，
     * 首列"数据行号"（主 sheet 数据行序号，从 1 起），其余为子字段列。
     */
    private void appendTableSheets(Workbook workbook, FormContext ctx,
                                   List<Map<String, Object>> records,
                                   Map<String, List<FieldMeta>> tableFields) {
        if (tableFields.isEmpty()) {
            return;
        }
        Map<String, String> subTableMapping = parseSubTableMapping(ctx.formDefEntity().getSubTableMapping());

        for (Map.Entry<String, List<FieldMeta>> entry : tableFields.entrySet()) {
            String fieldName = entry.getKey();
            List<FieldMeta> subFields = entry.getValue();
            String subTableName = subTableMapping.get(fieldName);
            if (subTableName == null || subTableName.isBlank()) {
                log.warn("No sub-table mapping for TABLE field '{}', export empty sheet", fieldName);
            }

            Sheet subSheet = workbook.createSheet(fieldName);
            Row subHeader = subSheet.createRow(0);
            subHeader.createCell(0).setCellValue(ROW_NO_LABEL);
            for (int i = 0; i < subFields.size(); i++) {
                subHeader.createCell(i + 1).setCellValue(subFields.get(i).label());
            }

            int outRow = 1;
            for (int r = 0; r < records.size(); r++) {
                String recordId = asText(records.get(r).get("id"));
                if (recordId == null || subTableName == null || subTableName.isBlank()) {
                    continue;
                }
                List<Map<String, Object>> subRows = querySubRows(subTableName, subFields, recordId);
                for (Map<String, Object> subRow : subRows) {
                    Row row = subSheet.createRow(outRow);
                    row.createCell(0).setCellValue(r + 1);
                    for (int i = 0; i < subFields.size(); i++) {
                        setCellValue(row.createCell(i + 1),
                                subRow.get(subFields.get(i).mappingKey()), subFields.get(i).type());
                    }
                    outRow++;
                }
            }
            for (int i = 0; i <= subFields.size(); i++) {
                subSheet.autoSizeColumn(i);
            }
        }
    }

    private List<Map<String, Object>> querySubRows(String subTableName, List<FieldMeta> subFields,
                                                   String recordId) {
        Long tenantId = requireLogin().getTenantId();
        List<String> cols = new ArrayList<>(List.of("id"));
        for (FieldMeta sub : subFields) {
            cols.add(sub.mappingKey());
        }
        String columns = cols.stream().map(c -> "\"" + c + "\"").reduce((a, b) -> a + ", " + b).orElse("\"id\"");
        String sql = "SELECT " + columns + " FROM \"" + subTableName
                + "\" WHERE \"parent_record_id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?";
        try {
            return jdbcTemplate.queryForList(sql, recordId, tenantId);
        } catch (Exception e) {
            log.warn("Sub-table query failed for '{}': {}", subTableName, e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 REFERENCE 字段的目标显示值：对每个引用 id 查询目标表单记录的显示文本
     * （目标表单定义中第一个 TEXT 字段值；无则回退 id 本身）。
     * 返回：字段名 → (id → 显示值)。
     */
    private Map<String, Map<String, String>> resolveReferenceDisplays(List<FieldMeta> fields,
                                                                      List<Map<String, Object>> records) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        Long tenantId = requireLogin().getTenantId();

        for (FieldMeta field : fields) {
            if (field.type() != FieldType.REFERENCE) continue;
            if (field.targetFormId() == null || field.targetFormId().isBlank()) continue;

            Set<String> ids = new LinkedHashSet<>();
            for (Map<String, Object> record : records) {
                String id = asText(record.get(field.mappingKey()));
                if (id != null) {
                    ids.add(id);
                }
            }
            if (ids.isEmpty()) continue;

            FormDefDTO targetDef;
            try {
                targetDef = formDefService.getFormDefByKey(field.targetFormId());
            } catch (Exception e) {
                log.warn("Reference target form '{}' not resolvable: {}", field.targetFormId(), e.getMessage());
                continue;
            }
            if (targetDef == null || targetDef.getPhysicalTableName() == null) continue;

            // 目标表单的显示列：第一个 TEXT 字段的物理列
            String displayCol = null;
            try {
                JsonNode root = objectMapper.readTree(loadDefinitionJson(targetDef.getId()));
                JsonNode fieldsArray = root.get("fields");
                if (fieldsArray != null && fieldsArray.isArray()) {
                    for (JsonNode f : fieldsArray) {
                        String t = f.has("type") ? f.get("type").asText() : "TEXT";
                        if ("TEXT".equals(t) && f.has("name")) {
                            displayCol = f.get("name").asText();
                            break;
                        }
                    }
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse target definition for '{}'", field.targetFormId());
            }
            if (displayCol == null) continue;

            Map<String, String> idToDisplay = new LinkedHashMap<>();
            String sql = "SELECT \"id\", \"" + displayCol + "\" FROM \"" + targetDef.getPhysicalTableName()
                    + "\" WHERE \"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?";
            for (String id : ids) {
                try {
                    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, id, tenantId);
                    if (!rows.isEmpty()) {
                        String display = asText(rows.get(0).get(displayCol));
                        if (display != null) {
                            idToDisplay.put(id, display);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Reference display lookup failed: id={}, {}", id, e.getMessage());
                }
            }
            result.put(field.name(), idToDisplay);
        }
        return result;
    }

    // ==================== 单元格读取（公式安全） ====================

    /**
     * 获取单元格值并转为字符串（用于映射行比对）。
     * FORMULA 单元格取缓存显示值，绝不取公式串。
     */
    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        CellType cellType = cell.getCellType();
        if (cellType == CellType.FORMULA) {
            try {
                cellType = cell.getCachedFormulaResultType();
            } catch (Exception e) {
                return "";
            }
        }
        return switch (cellType) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getDateCellValue().toString()
                    : String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    /**
     * 获取单元格值并根据字段类型转换。
     * <p>公式安全：FORMULA 单元格取缓存显示值（文本/数值），绝不取公式串。
     * DATE 单元格数值 → LocalDateTime（与 TIMESTAMP 列兼容）。</p>
     */
    private Object getCellValue(Cell cell, FieldType fieldType) {
        if (cell == null) return null;

        CellType cellType = cell.getCellType();
        if (cellType == CellType.FORMULA) {
            try {
                cellType = cell.getCachedFormulaResultType();
            } catch (Exception e) {
                throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                        "第 " + (cell.getRowIndex() + 1) + " 行存在无法求值的公式单元格");
            }
        }

        return switch (cellType) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? dateToLocalDateTime(cell.getDateCellValue())
                    : cell.getNumericCellValue();
            case BOOLEAN -> cell.getBooleanCellValue() ? 1 : 0;
            default -> null;
        };
    }

    private static LocalDateTime dateToLocalDateTime(java.util.Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    // ==================== 单元格写入（公式安全） ====================

    /**
     * 文本单元格写入：以 = + - @ 开头的业务文本保持文本语义，
     * 显式使用文本格式且绝不经 setCellFormula。
     */
    private void writeTextCell(Cell cell, String value) {
        if (value == null) {
            cell.setCellValue("");
            return;
        }
        if (isFormulaRiskText(value)) {
            CellStyle textStyle = cell.getSheet().getWorkbook().createCellStyle();
            DataFormat format = cell.getSheet().getWorkbook().createDataFormat();
            textStyle.setDataFormat(format.getFormat("@"));
            cell.setCellStyle(textStyle);
        }
        // POI setCellValue(String) 恒为文本单元格，不会成为公式
        cell.setCellValue(value);
    }

    /** 是否为可能被表格软件误判为公式/链接的前缀。 */
    static boolean isFormulaRiskText(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        char c = value.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@';
    }

    /**
     * 设置单元格值。字符串一律走 writeTextCell（公式安全）。
     */
    private void setCellValue(Cell cell, Object value, FieldType fieldType) {
        if (value == null) {
            cell.setCellValue("");
            return;
        }

        switch (fieldType) {
            case NUMBER -> {
                if (value instanceof Number n) {
                    cell.setCellValue(n.doubleValue());
                } else {
                    try {
                        cell.setCellValue(Double.parseDouble(value.toString()));
                    } catch (NumberFormatException e) {
                        writeTextCell(cell, value.toString());
                    }
                }
            }
            case BOOL -> {
                if (value instanceof Number n) {
                    cell.setCellValue(n.intValue() == 1 ? "是" : "否");
                } else if (value instanceof Boolean b) {
                    cell.setCellValue(b ? "是" : "否");
                } else {
                    cell.setCellValue(value.toString());
                }
            }
            case DATE -> {
                // 统一以 ISO 文本导出，避免日期被读成纯数值
                if (value instanceof java.util.Date d) {
                    writeTextCell(cell, d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString());
                } else if (value instanceof LocalDateTime ldt) {
                    writeTextCell(cell, ldt.toString());
                } else {
                    writeTextCell(cell, value.toString());
                }
            }
            default -> writeTextCell(cell, value.toString());
        }
    }

    // ==================== 上下文加载 ====================

    private record FormContext(FormDefDTO formDef,
                               FormDefEntity formDefEntity,
                               List<FieldMeta> mainFields,
                               Map<String, List<FieldMeta>> tableFields) {}

    private LoginUser requireLogin() {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(com.sw.ck.common.exception.CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        return loginUser;
    }

    private FormContext loadFormContext(String formKey, String action) {
        FormDefDTO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND, "表单 '" + formKey + "' 不存在");
        }
        boolean exportExisting = "导出数据".equals(action)
                && "DISABLED".equals(formDef.getStatus());
        if (!"PUBLISHED".equals(formDef.getStatus()) && !exportExisting) {
            throw new BaseException(FormErrorCode.FORM_NOT_PUBLISHED,
                    "表单 '" + formKey + "' 未发布，不能" + action);
        }

        String definitionJson = loadDefinitionJson(formDef.getId());
        if (definitionJson == null || definitionJson.isBlank() || "{}".equals(definitionJson)) {
            throw new BaseException(FormErrorCode.CONFIG_NOT_FOUND, "表单配置为空");
        }

        List<FieldMeta> mainFields = new ArrayList<>();
        List<FieldMeta> tableFields = new ArrayList<>();
        parseFieldsInternal(definitionJson, mainFields, tableFields);
        Map<String, List<FieldMeta>> tableFieldMap = new LinkedHashMap<>();
        for (FieldMeta tf : tableFields) {
            tableFieldMap.put(tf.name(), tf.subFields() != null ? tf.subFields() : List.of());
        }

        FormDefEntity entity = formDefMapper.selectById(formDef.getId());
        return new FormContext(formDef, entity, mainFields, tableFieldMap);
    }

    private String loadDefinitionJson(String formId) {
        List<FormConfigEntity> configs = formConfigMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FormConfigEntity>()
                        .eq(FormConfigEntity::getFormId, formId)
                        .isNull(FormConfigEntity::getParentTable)
        );
        FormConfigEntity config = (configs != null && !configs.isEmpty()) ? configs.get(0) : null;
        return (config != null) ? config.getDefinition() : null;
    }

    private Map<String, String> parseSubTableMapping(String subTableMappingJson) {
        if (subTableMappingJson == null || subTableMappingJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(subTableMappingJson,
                    new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse sub-table mapping JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String asText(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    // ==================== 结果类型 ====================

    /**
     * 导入结果。
     */
    public record ImportResult(
            int totalRows,
            int successCount,
            int errorCount,
            List<String> successIds,
            List<RowError> errors
    ) {}

    /**
     * 行级错误。
     */
    public record RowError(
            int rowNum,
            String message
    ) {}
}

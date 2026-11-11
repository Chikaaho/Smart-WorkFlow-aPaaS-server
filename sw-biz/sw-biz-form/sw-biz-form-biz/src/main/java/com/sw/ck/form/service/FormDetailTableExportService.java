package com.sw.ck.form.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableSql;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import com.sw.ck.system.api.dict.DictFacade;
import com.sw.ck.system.api.dict.DictItemDTO;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 明细表导出 Excel（1.0.0 F2-6）。
 * <p>
 * 只读视图中的每一张明细表（TABLE 字段）单独导出：列头用子字段显示名，
 * 值用显示值（人员、部门、字典/选项、日期、多选顿号分隔），不输出内部 id；
 * 字段级隐藏沿用 {@link FormDataQueryService#getRecordDetail} 的权限投影
 * （被隐藏的子字段在行数据中不存在，导出为空白单元格，与页面一致）；
 * 文本写入带公式注入防护（以 = + - @ 开头的业务文本按文本单元格处理）。
 * 导出全部子表行，不受列表分页影响；文件名包含表单名、明细表名、记录编号。
 * </p>
 */
@Service
public class FormDetailTableExportService {

    private static final Logger log = LoggerFactory.getLogger(FormDetailTableExportService.class);

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final String MULTI_SEPARATOR = "、";
    private static final int MAX_FILENAME_PART = 60;

    private final FormDefService formDefService;
    private final FormDataQueryService formDataQueryService;
    private final UserQueryFacade userQueryFacade;
    private final DeptQueryFacade deptQueryFacade;
    private final DictFacade dictFacade;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FormDetailTableExportService(FormDefService formDefService,
                                        FormDataQueryService formDataQueryService,
                                        UserQueryFacade userQueryFacade,
                                        DeptQueryFacade deptQueryFacade,
                                        DictFacade dictFacade,
                                        JdbcTemplate jdbcTemplate,
                                        ObjectMapper objectMapper) {
        this.formDefService = formDefService;
        this.formDataQueryService = formDataQueryService;
        this.userQueryFacade = userQueryFacade;
        this.deptQueryFacade = deptQueryFacade;
        this.dictFacade = dictFacade;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 导出指定记录的一张明细表。
     *
     * @return 文件内容 + 建议文件名（表单名_明细表名_记录编号.xlsx）
     */
    public ExportFile exportTable(String formKey, String recordId, String fieldName) {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(com.sw.ck.common.exception.CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        FormDefDTO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 不存在");
        }
        String definitionJson = formDefService.getDefinition(formKey);
        TableField table = findTableField(definitionJson, fieldName);
        if (table == null) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID, "该表单不存在明细表 '" + fieldName + "'");
        }

        // 与页面详情同源：登录态 + 记录可见性 + 字段级隐藏 + 数据范围
        Map<String, Object> record = formDataQueryService.getRecordDetail(formKey, recordId);
        List<Map<String, Object>> rows = asRows(record.get(fieldName));

        Map<String, String> dictLabels = loadDictLabels(table.subFields());
        Map<String, Map<String, String>> referenceDisplays = resolveReferenceDisplays(table.subFields(), rows);
        DisplayContext display = new DisplayContext(dictLabels, referenceDisplays);

        byte[] content = writeWorkbook(table, rows, display);
        String filename = buildFilename(formDef.getName(), table.label(), recordId);
        log.info("明细表导出完成: formKey={} recordId={} field={} rows={} bytes={}",
                formKey, recordId, fieldName, rows.size(), content.length);
        return new ExportFile(content, filename);
    }

    // ==================== 定义解析 ====================

    private TableField findTableField(String definitionJson, String fieldName) {
        if (definitionJson == null || definitionJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode fields = root.get("fields");
            if (fields == null || !fields.isArray()) {
                return null;
            }
            for (JsonNode node : fields) {
                String name = node.path("name").asText(null);
                if (!fieldName.equals(name) || !"TABLE".equals(node.path("type").asText())) {
                    continue;
                }
                List<SubField> subFields = new ArrayList<>();
                JsonNode subArray = node.get("subFields");
                if (subArray != null && subArray.isArray()) {
                    for (JsonNode sub : subArray) {
                        String subName = sub.path("name").asText(null);
                        if (subName == null || subName.isBlank()) {
                            continue;
                        }
                        FieldType type;
                        try {
                            type = FieldType.valueOf(sub.path("type").asText("TEXT"));
                        } catch (IllegalArgumentException e) {
                            continue;
                        }
                        // LABEL 不是输入字段、无物理列；子表内不导出
                        if (type == FieldType.LABEL || type == FieldType.TABLE) {
                            continue;
                        }
                        subFields.add(new SubField(
                                subName,
                                sub.path("label").asText(subName),
                                type,
                                DynamicTableSql.requireColumn(subName, type),
                                parseOptions(sub.get("options")),
                                sub.path("source").asText("custom"),
                                sub.path("dictType").asText(null),
                                sub.path("targetFormId").asText(null),
                                sub.path("multiple").asBoolean(false),
                                parseDecimals(sub),
                                "datetime".equals(sub.path("format").asText(""))));
                    }
                }
                String label = node.path("label").asText(fieldName);
                return new TableField(fieldName, label, subFields);
            }
        } catch (IOException e) {
            log.warn("表单定义解析失败: {}", e.getMessage());
        }
        return null;
    }

    /** 字段小数位配置（decimals/precision，未配置返回 null=去掉尾随 0）。 */
    private Integer parseDecimals(JsonNode node) {
        if (node.has("decimals") && node.get("decimals").isNumber()) {
            return Math.max(0, node.get("decimals").asInt());
        }
        if (node.has("precision") && node.get("precision").isNumber()) {
            return Math.max(0, node.get("precision").asInt());
        }
        return null;
    }

    private Map<String, String> parseOptions(JsonNode optionsNode) {
        Map<String, String> options = new LinkedHashMap<>();
        if (optionsNode == null || !optionsNode.isArray()) {
            return options;
        }
        for (JsonNode option : optionsNode) {
            if (option.isTextual()) {
                options.put(option.asText(), option.asText());
                continue;
            }
            String key = option.path("key").asText(option.path("value").asText(""));
            String label = option.path("value").asText(option.path("label").asText(key));
            if (!key.isBlank()) {
                options.put(key, label);
            }
        }
        return options;
    }

    // ==================== 显示值解析 ====================

    private Map<String, String> loadDictLabels(List<SubField> subFields) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (SubField field : subFields) {
            boolean dictLike = field.type() == FieldType.DICT
                    || (field.type() == FieldType.MULTISELECT && "dict".equals(field.source()));
            if (!dictLike || field.dictType() == null || field.dictType().isBlank()) {
                continue;
            }
            Optional<List<DictItemDTO>> items = dictFacade.listByType(field.dictType());
            if (items.isEmpty()) {
                continue;
            }
            for (DictItemDTO item : items.get()) {
                if (item.getCode() != null && item.getLabel() != null) {
                    labels.put(field.name() + "\u0000" + item.getCode(), item.getLabel());
                }
            }
        }
        return labels;
    }

    private Map<String, Map<String, String>> resolveReferenceDisplays(List<SubField> subFields,
                                                                     List<Map<String, Object>> rows) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (SubField field : subFields) {
            if (field.type() != FieldType.REFERENCE
                    || field.targetFormId() == null || field.targetFormId().isBlank()) {
                continue;
            }
            Set<String> ids = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                String id = asText(row.get(field.mappingKey()));
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                continue;
            }
            String displayColumn = resolveDisplayColumn(field.targetFormId());
            if (displayColumn == null) {
                continue;
            }
            String sql = "SELECT \"id\", " + DynamicTableSql.quote(displayColumn)
                    + " FROM " + DynamicTableSql.quote(resolvePhysicalTable(field.targetFormId()))
                    + " WHERE \"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?";
            Map<String, String> idToDisplay = new LinkedHashMap<>();
            Long tenantId = LoginUserHolder.get().getTenantId();
            for (String id : ids) {
                try {
                    List<Map<String, Object>> found = DynamicTableSql.query(jdbcTemplate,
                            resolvePhysicalTable(field.targetFormId()), sql, id, tenantId);
                    if (!found.isEmpty()) {
                        String display = asText(found.get(0).get(displayColumn));
                        if (display != null && !display.isBlank()) {
                            idToDisplay.put(id, display);
                        }
                    }
                } catch (Exception e) {
                    log.warn("明细表引用显示值解析失败: id={} err={}", id, e.getMessage());
                }
            }
            result.put(field.name(), idToDisplay);
        }
        return result;
    }

    private String resolvePhysicalTable(String targetFormKey) {
        FormDefDTO target = formDefService.getFormDefByKey(targetFormKey);
        if (target == null || target.getPhysicalTableName() == null) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST,
                    "引用表单 '" + targetFormKey + "' 不存在");
        }
        return DynamicTableSql.requireTableName(target.getPhysicalTableName());
    }

    private String resolveDisplayColumn(String targetFormKey) {
        FormDefDTO target = formDefService.getFormDefByKey(targetFormKey);
        if (target == null) {
            return null;
        }
        String definition = formDefService.getDefinition(targetFormKey);
        try {
            JsonNode fields = objectMapper.readTree(definition).get("fields");
            if (fields == null || !fields.isArray()) {
                return null;
            }
            for (JsonNode field : fields) {
                if ("TEXT".equals(field.path("type").asText("TEXT")) && field.has("name")) {
                    return DynamicTableSql.requireColumn(field.get("name").asText(), FieldType.TEXT);
                }
            }
        } catch (Exception e) {
            log.warn("引用表单显示列解析失败: {} {}", targetFormKey, e.getMessage());
        }
        return null;
    }

    private String displayValue(SubField field, Object raw, DisplayContext context) {
        if (raw == null) {
            return "";
        }
        return switch (field.type()) {
            case TEXT -> asText(raw);
            case RICH_TEXT -> Jsoup.parse(asText(raw)).text();
            case NUMBER, FORMULA -> numberText(raw);
            case BOOL -> boolText(raw);
            case DATE -> dateText(raw, field.datetimeFormat());
            case TIME -> timeText(raw);
            case DICT -> dictText(field, raw, context);
            case MULTISELECT -> multiText(field, raw, context);
            case USER -> userText(raw);
            case DEPT -> deptText(raw);
            case REFERENCE -> referenceText(field, raw, context);
            case ATTACHMENT, IMAGE -> attachmentText(raw);
            case DATASOURCE -> datasourceText(raw);
            case LABEL -> "";
            default -> asText(raw);
        };
    }

    private String dictText(SubField field, Object raw, DisplayContext context) {
        String code = asText(raw);
        if (code == null || code.isBlank()) {
            return "";
        }
        String label = context.dictLabels().get(field.name() + "\u0000" + code);
        if (label == null) {
            label = field.options().get(code);
        }
        return label != null ? label : code;
    }

    private String multiText(SubField field, Object raw, DisplayContext context) {
        List<String> values = splitValues(raw);
        List<String> labels = new ArrayList<>(values.size());
        for (String value : values) {
            String label = context.dictLabels().get(field.name() + "\u0000" + value);
            if (label == null) {
                label = field.options().get(value);
            }
            labels.add(label != null ? label : value);
        }
        return String.join(MULTI_SEPARATOR, labels);
    }

    private String userText(Object raw) {
        List<String> ids = numericIds(raw);
        if (ids.isEmpty()) {
            return "";
        }
        Optional<Map<Long, String>> names = userQueryFacade.getUserDisplayNames(toLongs(ids));
        Map<Long, String> resolved = names.orElse(Map.of());
        List<String> labels = new ArrayList<>(ids.size());
        for (String id : ids) {
            String label = resolved.get(Long.valueOf(id));
            if (label != null && !label.isBlank()) {
                labels.add(label);
            }
        }
        return String.join(MULTI_SEPARATOR, labels);
    }

    private String deptText(Object raw) {
        List<String> ids = numericIds(raw);
        if (ids.isEmpty()) {
            return "";
        }
        Optional<Map<Long, String>> names = deptQueryFacade.findActiveDeptNames(toLongs(ids));
        Map<Long, String> resolved = names.orElse(Map.of());
        List<String> labels = new ArrayList<>(ids.size());
        for (String id : ids) {
            String label = resolved.get(Long.valueOf(id));
            if (label != null && !label.isBlank()) {
                labels.add(label);
            }
        }
        return String.join(MULTI_SEPARATOR, labels);
    }

    private String referenceText(SubField field, Object raw, DisplayContext context) {
        String id = asText(raw);
        if (id == null || id.isBlank()) {
            return "";
        }
        Map<String, String> mapping = context.referenceDisplays().getOrDefault(field.name(), Map.of());
        String display = mapping.get(id);
        return display != null ? display : "";
    }

    private String attachmentText(Object raw) {
        String text = asText(raw);
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            JsonNode array = objectMapper.readTree(text);
            if (array.isArray()) {
                List<String> names = new ArrayList<>();
                for (JsonNode item : array) {
                    String name = item.path("name").asText(null);
                    if (name != null && !name.isBlank()) {
                        names.add(name);
                    }
                }
                return String.join(MULTI_SEPARATOR, names);
            }
        } catch (Exception e) {
            // 非 JSON：按纯文本输出
        }
        return text;
    }

    private String datasourceText(Object raw) {
        String text = asText(raw);
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            String display = node.path("display").asText(null);
            if (display != null && !display.isBlank()) {
                return display;
            }
        } catch (Exception e) {
            // 非 JSON：按纯文本输出
        }
        return text;
    }

    // ==================== 工作簿写入 ====================

    private byte[] writeWorkbook(TableField table, List<Map<String, Object>> rows,
                                 DisplayContext context) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("明细表");
            Row header = sheet.createRow(0);
            for (int i = 0; i < table.subFields().size(); i++) {
                header.createCell(i).setCellValue(table.subFields().get(i).label());
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, Object> data = rows.get(r);
                for (int c = 0; c < table.subFields().size(); c++) {
                    SubField field = table.subFields().get(c);
                    // 字段级隐藏：投影中不存在的列导出空白（与页面单元格一致）
                    Object raw = data.containsKey(field.mappingKey())
                            ? data.get(field.mappingKey()) : null;
                    Cell cell = row.createCell(c);
                    if (field.type() == FieldType.NUMBER || field.type() == FieldType.FORMULA) {
                        writeNumberCell(cell, raw, field);
                    } else {
                        writeTextCell(cell, displayValue(field, raw, context));
                    }
                }
            }
            for (int i = 0; i < table.subFields().size(); i++) {
                sheet.autoSizeColumn(i);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("明细表导出失败: table={}", table.name(), e);
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "导出明细表时系统未能完成，请稍后重试");
        }
    }

    private void writeNumberCell(Cell cell, Object raw, SubField field) {
        if (raw == null) {
            cell.setCellValue("");
            return;
        }
        if (raw instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            applyNumberFormat(cell, field);
            return;
        }
        String text = numberText(raw);
        try {
            cell.setCellValue(Double.parseDouble(text));
            applyNumberFormat(cell, field);
        } catch (NumberFormatException e) {
            writeTextCell(cell, text);
        }
    }

    /** 数值单元格格式：有小数位配置按配置，否则最多 12 位并去掉尾随 0（与页面显示一致）。 */
    private void applyNumberFormat(Cell cell, SubField field) {
        String format = field.decimals() == null
                ? "0.############"
                : "0." + "0".repeat(Math.max(0, field.decimals()));
        CellStyle style = cell.getSheet().getWorkbook().createCellStyle();
        style.setDataFormat(cell.getSheet().getWorkbook().createDataFormat().getFormat(format));
        cell.setCellStyle(style);
    }

    /** 文本单元格：公式风险前缀（= + - @）显式按文本格式写入，绝不成为公式。 */
    private void writeTextCell(Cell cell, String value) {
        if (value == null) {
            cell.setCellValue("");
            return;
        }
        if (!value.isEmpty()) {
            char first = value.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                CellStyle style = cell.getSheet().getWorkbook().createCellStyle();
                DataFormat format = cell.getSheet().getWorkbook().createDataFormat();
                style.setDataFormat(format.getFormat("@"));
                cell.setCellStyle(style);
            }
        }
        cell.setCellValue(value);
    }

    // ==================== 值转换 ====================

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return value.toString();
    }

    private static String numberText(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return number.toString();
        }
        return asText(value);
    }

    private static String boolText(Object value) {
        String text = asText(value);
        if (text == null) {
            return "";
        }
        return switch (text) {
            case "1", "true", "TRUE", "True" -> "是";
            case "0", "false", "FALSE", "False" -> "否";
            default -> text;
        };
    }

    /** 日期按子字段 format 输出：datetime → yyyy-MM-dd HH:mm，其余（含未配置）→ yyyy-MM-dd（U2）。 */
    private static String dateText(Object value, boolean datetimeFormat) {
        DateTimeFormatter pattern = datetimeFormat ? DATE_TIME : DATE_ONLY;
        if (value instanceof LocalDateTime dateTime) {
            return pattern.format(dateTime);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return pattern.format(timestamp.toLocalDateTime());
        }
        if (value instanceof java.util.Date date) {
            return pattern.format(LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault()));
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        String text = asText(value);
        if (text == null) {
            return "";
        }
        // ISO 文本（带 T/微秒）按字段格式收敛；日期字段截到日，日期时间字段截到分
        String normalized = text.replace('T', ' ');
        int maxLength = datetimeFormat ? 16 : 10;
        if (normalized.length() > maxLength) {
            return normalized.substring(0, maxLength);
        }
        return normalized;
    }

    private static String timeText(Object value) {
        if (value instanceof LocalTime time) {
            return TIME.format(time);
        }
        if (value instanceof java.sql.Time time) {
            return TIME.format(time.toLocalTime());
        }
        String text = asText(value);
        return text == null ? "" : (text.length() > 5 ? text.substring(0, 5) : text);
    }

    private static List<String> splitValues(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw == null) {
            return values;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item).trim());
                }
            }
            return values;
        }
        String text = asText(raw).trim();
        if (text.startsWith("[")) {
            try {
                JsonNode array = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
                if (array.isArray()) {
                    for (JsonNode item : array) {
                        if (!item.asText("").isBlank()) {
                            values.add(item.asText());
                        }
                    }
                    return values;
                }
            } catch (Exception e) {
                // 非 JSON：按逗号分隔回退
            }
        }
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static List<String> numericIds(Object raw) {
        List<String> ids = new ArrayList<>();
        for (String value : splitValues(raw)) {
            String trimmed = value.trim();
            if (trimmed.matches("\\d{1,19}")) {
                ids.add(trimmed);
            }
        }
        return ids;
    }

    private static List<Long> toLongs(List<String> ids) {
        List<Long> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            result.add(Long.valueOf(id));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRows(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> rows = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    rows.add((Map<String, Object>) map);
                }
            }
            return rows;
        }
        return List.of();
    }

    private static String buildFilename(String formName, String tableLabel, String recordId) {
        String base = safePart(formName) + "_" + safePart(tableLabel) + "_" + safePart(recordId);
        return base + ".xlsx";
    }

    private static String safePart(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        if (value.length() > MAX_FILENAME_PART) {
            value = value.substring(0, MAX_FILENAME_PART);
        }
        return value.isBlank() ? "未命名" : value;
    }

    // ==================== 内部类型 ====================

    /** 导出结果：文件内容与建议文件名。 */
    public record ExportFile(byte[] content, String filename) {
    }

    private record SubField(String name, String label, FieldType type, String mappingKey,
                            Map<String, String> options, String source, String dictType,
                            String targetFormId, boolean multiple, Integer decimals,
                            boolean datetimeFormat) {
    }

    private record TableField(String name, String label, List<SubField> subFields) {
    }

    private record DisplayContext(Map<String, String> dictLabels,
                                  Map<String, Map<String, String>> referenceDisplays) {
    }
}

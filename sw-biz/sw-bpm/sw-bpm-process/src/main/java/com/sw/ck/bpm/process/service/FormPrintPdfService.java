package com.sw.ck.bpm.process.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sw.ck.bpm.process.dto.ApprovalHistoryItemDTO;
import com.sw.ck.form.api.print.FormPrintFacade;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import com.sw.ck.system.api.dict.DictFacade;
import com.sw.ck.system.api.dict.DictItemDTO;
import com.sw.ck.system.api.user.UserQueryFacade;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 服务端打印 PDF 生成（1.0.0 F2-3）。
 * <p>
 * 引擎为 PDFBox 3（3.0.4，仓库既有托管依赖，仅用于解析的原依赖延伸为生成）；
 * 中文字体为随产物打包的 Noto Sans SC Regular（OFL 1.1，见 resources/fonts/OFL.txt，
 * 由本机 Windows 自带的 Google Fonts v2.04 可变字体静态实例化 wght=400 得到，
 * 许可证允许再分发），演示服务器无需另装字体。
 * </p>
 * <p>
 * 版式（与浏览器打印一致的 A4 结构）：抬头/标题 → 字段（跳过 TABLE）→ 明细表
 * （分页重复表头、行不跨页截断）→ 审批历史（意见摘要 + 部门）→ 页脚
 * （模板页脚/版本说明 + 页码）。数据只来自 {@link FormPrintFacade} 的权限过滤链（F2-4）。
 * </p>
 */
@Slf4j
@Service
public class FormPrintPdfService {

    /** 打印模板配置在 definition JSON 中的根键（与前端设计器同一键名）。 */
    public static final String PRINT_TEMPLATE_KEY = "printTemplate";

    private static final PDRectangle PAGE_SIZE = PDRectangle.A4;
    private static final float MARGIN = 48f;
    private static final float PAGE_WIDTH = PAGE_SIZE.getWidth() - 2 * MARGIN;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final FormPrintFacade formPrintFacade;
    /** 显示值解析（USER/DEPT/DICT）；单元测试手工构造时可为空（回退原值渲染）。 */
    private final UserQueryFacade userQueryFacade;
    private final DeptQueryFacade deptQueryFacade;
    private final DictFacade dictFacade;

    public FormPrintPdfService(FormPrintFacade formPrintFacade) {
        this(formPrintFacade, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FormPrintPdfService(FormPrintFacade formPrintFacade,
                               UserQueryFacade userQueryFacade,
                               DeptQueryFacade deptQueryFacade,
                               DictFacade dictFacade) {
        this.formPrintFacade = formPrintFacade;
        this.userQueryFacade = userQueryFacade;
        this.deptQueryFacade = deptQueryFacade;
        this.dictFacade = dictFacade;
    }

    /**
     * 生成打印 PDF。
     *
     * @param formKey     表单业务标识
     * @param recordId    记录 UUID
     * @param submittedAt 实例提交时间（可空；用于提交时版本定位）
     * @param history     审批历史（可空=不含审批历史段）
     * @return PDF 字节
     * @throws IllegalStateException 无权读取或数据不可用时（安全结论，不含差异细节）
     */
    public byte[] render(String formKey, String recordId, LocalDateTime submittedAt,
                         List<ApprovalHistoryItemDTO> history) {
        FormPrintFacade.FormPrintData data = formPrintFacade
                .recordDetail(formKey, recordId, submittedAt)
                .orElseThrow(() -> new IllegalStateException("打印数据不可用或无权读取"));
        JSONObject def = parseDefinition(data.printDefinitionJson());
        PrintTemplate template = PrintTemplate.of(def);
        return renderDocument(data, def, template,
                template.includeHistory() ? history : null);
    }

    // ==================== 版式 ====================

    private byte[] renderDocument(FormPrintFacade.FormPrintData data, JSONObject def,
                                  PrintTemplate template,
                                  List<ApprovalHistoryItemDTO> history) {
        try (PDDocument doc = new PDDocument()) {
            // embedSubset=true：只嵌入用到的字形，避免整份 10MB CJK 字体进 PDF
            PDType0Font font = PDType0Font.load(doc, fontStream(), true);
            Canvas canvas = new Canvas(doc, font);

            // 抬头 + 标题
            if (notBlank(template.header())) {
                canvas.paragraph(template.header(), 10, 0.35f);
            }
            canvas.paragraph(notBlank(data.formName()) ? data.formName() : "表单打印", 18, 0.5f);

            // 字段（模板选择或全部可见字段；TABLE 字段进入明细表段）
            List<FieldDef> fields = parseFields(def);
            List<FieldDef> shown = new ArrayList<>();
            for (FieldDef field : fields) {
                if (!"TABLE".equals(field.type)
                        && (template.fields() == null || template.fields().contains(field.name()))) {
                    shown.add(field);
                }
            }
            for (FieldDef field : shown) {
                Object value = data.record().get(field.name());
                canvas.fieldLine(field.label() == null ? field.name() : field.label(),
                        displayValue(field, value));
            }

            // 明细表
            for (FieldDef field : fields) {
                if (!"TABLE".equals(field.type)) {
                    continue;
                }
                if (template.tables() != null && !template.tables().contains(field.name())) {
                    continue;
                }
                Object value = data.record().get(field.name());
                List<Map<String, Object>> rows = asRows(value);
                if (rows == null || rows.isEmpty()) {
                    continue;
                }
                canvas.spacer(6);
                canvas.paragraph(field.label() == null ? field.name() : field.label(), 12, 0.3f);
                List<Column> columns = new ArrayList<>();
                for (FieldDef sub : field.subFields()) {
                    columns.add(new Column(sub.label() == null ? sub.name() : sub.label(),
                            sub.name(), (PAGE_WIDTH - 12) / Math.max(1, field.subFields().size())));
                }
                List<Map<String, Object>> displayRows = new ArrayList<>(rows.size());
                for (Map<String, Object> row : rows) {
                    Map<String, Object> displayRow = new LinkedHashMap<>();
                    for (FieldDef sub : field.subFields()) {
                        displayRow.put(sub.name(), displayValue(sub, row.get(sub.name())));
                    }
                    displayRows.add(displayRow);
                }
                canvas.table(columns, displayRows, false);
            }

            // 审批历史
            if (history != null && !history.isEmpty()) {
                canvas.spacer(8);
                canvas.paragraph("审批历史", 12, 0.3f);
                List<Column> columns = List.of(
                        new Column("节点", "node", PAGE_WIDTH * 0.16f),
                        new Column("办理人", "assignee", PAGE_WIDTH * 0.14f),
                        new Column("部门", "dept", PAGE_WIDTH * 0.16f),
                        new Column("结果", "result", PAGE_WIDTH * 0.12f),
                        new Column("时间", "time", PAGE_WIDTH * 0.18f),
                        new Column("意见", "opinion", PAGE_WIDTH * 0.24f));
                List<Map<String, Object>> rows = new ArrayList<>();
                for (ApprovalHistoryItemDTO item : history) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("node", nullSafe(item.getTaskName()));
                    row.put("assignee", nullSafe(item.getAssigneeName()));
                    row.put("dept", nullSafe(item.getAssigneeDept()));
                    row.put("result", nullSafe(item.getApprovalResult()));
                    row.put("time", item.getEndTime() == null ? "" : TIME_FMT.format(item.getEndTime()));
                    row.put("opinion", opinionSummary(item));
                    rows.add(row);
                }
                canvas.table(columns, rows, true);
            }

            // 页脚（每页）：模板页脚 + 版本说明 + 页码
            String versionNote = data.versionPinned()
                    ? "表单版本 v" + data.printVersion() + "（提交时版本）"
                    : "未定位到提交时版本，按当前版本 v" + data.printVersion() + " 打印";
            canvas.drawFooters(notBlank(template.footer()) ? template.footer() : null, versionNote);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("打印 PDF 生成失败: formName={}", data.formName(), e);
            throw new IllegalStateException("打印 PDF 生成失败，请稍后重试");
        }
    }

    /** 意见摘要：意见字段展示文本拼接（不含完整快照 JSON）；缺省回退普通意见文本。 */
    private String opinionSummary(ApprovalHistoryItemDTO item) {
        if (item.getOpinionFields() == null || item.getOpinionFields().isEmpty()) {
            Object comment = item.getOpinionData() == null ? null
                    : item.getOpinionData().get("comment");
            return comment == null ? "" : String.valueOf(comment);
        }
        List<String> parts = new ArrayList<>();
        for (ApprovalHistoryItemDTO.OpinionFieldView view : item.getOpinionFields()) {
            if (view.getDisplayValue() != null && !view.getDisplayValue().isBlank()) {
                parts.add((view.getLabel() == null ? view.getName() : view.getLabel())
                        + "：" + view.getDisplayValue());
            }
        }
        return String.join("；", parts);
    }

    // ==================== definition 解析 ====================

    private JSONObject parseDefinition(String definitionJson) {
        try {
            return JSON.parseObject(definitionJson == null ? "{}" : definitionJson);
        } catch (Exception e) {
            log.warn("definition 解析失败，按无字段定义处理: {}", e.getMessage());
            return new JSONObject();
        }
    }

    private List<FieldDef> parseFields(JSONObject def) {
        List<FieldDef> fields = new ArrayList<>();
        JSONArray arr = def.getJSONArray("fields");
        if (arr == null) {
            return fields;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject f = arr.getJSONObject(i);
            if (f == null || f.getString("name") == null) {
                continue;
            }
            FieldDef field = fieldDef(f);
            if ("TABLE".equals(field.type()) && f.getJSONArray("subFields") != null) {
                for (int j = 0; j < f.getJSONArray("subFields").size(); j++) {
                    JSONObject sub = f.getJSONArray("subFields").getJSONObject(j);
                    if (sub != null && sub.getString("name") != null) {
                        field.subFields().add(fieldDef(sub));
                    }
                }
            }
            fields.add(field);
        }
        return fields;
    }

    private static FieldDef fieldDef(JSONObject node) {
        String name = node.getString("name");
        Map<String, String> options = new LinkedHashMap<>();
        JSONArray optionArray = node.getJSONArray("options");
        if (optionArray != null) {
            for (int i = 0; i < optionArray.size(); i++) {
                Object item = optionArray.get(i);
                if (item instanceof String text) {
                    options.put(text, text);
                } else if (item instanceof JSONObject option) {
                    String key = option.getString("key");
                    if (key == null) {
                        key = option.getString("value");
                    }
                    if (key != null && !key.isBlank()) {
                        String optionLabel = option.getString("value");
                        options.put(key, optionLabel == null ? key : optionLabel);
                    }
                }
            }
        }
        return new FieldDef(name, node.getString("type"),
                node.getString("label") == null ? name : node.getString("label"),
                options, node.getString("source"), node.getString("dictType"),
                new ArrayList<>());
    }

    private record FieldDef(String name, String type, String label,
                            Map<String, String> options, String source, String dictType,
                            List<FieldDef> subFields) {
    }

    /** printTemplate 配置；缺省=全部可见字段 + 全部明细表 + 审批历史（F2-1）。 */
    private record PrintTemplate(List<String> fields, List<String> tables,
                                 boolean includeHistory, String header, String footer) {
        private static PrintTemplate of(JSONObject def) {
            JSONObject t = def == null ? null : def.getJSONObject(PRINT_TEMPLATE_KEY);
            if (t == null) {
                return new PrintTemplate(null, null, true, null, null);
            }
            return new PrintTemplate(
                    toStringList(t.getJSONArray("fields")),
                    toStringList(t.getJSONArray("tables")),
                    t.getBooleanValue("includeHistory", true),
                    t.getString("header"),
                    t.getString("footer"));
        }

        private static List<String> toStringList(JSONArray arr) {
            if (arr == null || arr.isEmpty()) {
                return null;
            }
            return arr.stream().map(String::valueOf).toList();
        }
    }

    private record Column(String title, String key, float width) {
    }

    // ==================== 值渲染 ====================

    /** 按字段类型解析显示值（人员/部门/字典/多选/日期/布尔/附件），与页面回读一致。 */
    private String displayValue(FieldDef field, Object raw) {
        if (raw == null) {
            return "";
        }
        String type = field.type() == null ? "TEXT" : field.type();
        return switch (type) {
            case "DATE" -> formatDate(raw);
            case "TIME" -> formatTime(raw);
            case "BOOL" -> boolText(raw);
            case "USER" -> resolveUsers(raw);
            case "DEPT" -> resolveDepts(raw);
            case "DICT" -> resolveDict(field, raw);
            case "MULTISELECT" -> resolveMulti(field, raw);
            case "ATTACHMENT", "IMAGE" -> attachmentText(raw);
            default -> renderValue(raw);
        };
    }

    private String resolveUsers(Object raw) {
        List<Long> ids = numericIds(raw);
        if (ids.isEmpty() || userQueryFacade == null) {
            return "";
        }
        Optional<Map<Long, String>> names = userQueryFacade.getUserDisplayNames(ids);
        Map<Long, String> resolved = names.orElse(Map.of());
        List<String> labels = new ArrayList<>();
        for (Long id : ids) {
            String label = resolved.get(id);
            if (label != null && !label.isBlank()) {
                labels.add(label);
            }
        }
        return String.join("、", labels);
    }

    private String resolveDepts(Object raw) {
        List<Long> ids = numericIds(raw);
        if (ids.isEmpty() || deptQueryFacade == null) {
            return "";
        }
        Optional<Map<Long, String>> names = deptQueryFacade.findActiveDeptNames(ids);
        Map<Long, String> resolved = names.orElse(Map.of());
        List<String> labels = new ArrayList<>();
        for (Long id : ids) {
            String label = resolved.get(id);
            if (label != null && !label.isBlank()) {
                labels.add(label);
            }
        }
        return String.join("、", labels);
    }

    private String resolveDict(FieldDef field, Object raw) {
        String code = String.valueOf(raw);
        if (code.isBlank()) {
            return "";
        }
        String label = dictLabel(field, code);
        return label == null ? code : label;
    }

    private String resolveMulti(FieldDef field, Object raw) {
        List<String> values = splitValues(raw);
        List<String> labels = new ArrayList<>(values.size());
        for (String value : values) {
            String label = dictLabel(field, value);
            labels.add(label == null ? value : label);
        }
        return String.join("、", labels);
    }

    private String dictLabel(FieldDef field, String code) {
        if (field.options() != null && field.options().containsKey(code)) {
            return field.options().get(code);
        }
        if (dictFacade == null || field.dictType() == null || field.dictType().isBlank()) {
            return null;
        }
        Optional<List<DictItemDTO>> items = dictFacade.listByType(field.dictType());
        if (items.isEmpty()) {
            return null;
        }
        for (DictItemDTO item : items.get()) {
            if (code.equals(item.getCode())) {
                return item.getLabel();
            }
        }
        return null;
    }

    private static String formatDate(Object raw) {
        if (raw instanceof LocalDateTime dateTime) {
            return TIME_FMT.format(dateTime);
        }
        if (raw instanceof java.sql.Timestamp timestamp) {
            return TIME_FMT.format(timestamp.toLocalDateTime());
        }
        if (raw instanceof java.util.Date date) {
            return TIME_FMT.format(LocalDateTime.ofInstant(date.toInstant(),
                    java.time.ZoneId.systemDefault()));
        }
        String text = String.valueOf(raw).replace('T', ' ');
        return text.length() > 16 ? text.substring(0, 16) : text;
    }

    private static String formatTime(Object raw) {
        String text = String.valueOf(raw);
        return text.length() > 5 ? text.substring(0, 5) : text;
    }

    private static String boolText(Object raw) {
        String text = String.valueOf(raw);
        return switch (text) {
            case "1", "true", "TRUE", "True" -> "是";
            case "0", "false", "FALSE", "False" -> "否";
            default -> text;
        };
    }

    private static List<Long> numericIds(Object raw) {
        List<Long> ids = new ArrayList<>();
        for (String value : splitValues(raw)) {
            String trimmed = value.trim();
            if (trimmed.matches("\\d{1,19}")) {
                ids.add(Long.valueOf(trimmed));
            }
        }
        return ids;
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
        String text = String.valueOf(raw).trim();
        if (text.startsWith("[")) {
            try {
                JSONArray array = JSON.parseArray(text);
                for (int i = 0; i < array.size(); i++) {
                    String item = array.getString(i);
                    if (item != null && !item.isBlank()) {
                        values.add(item);
                    }
                }
                return values;
            } catch (Exception ignored) {
                // 非 JSON：按逗号回退
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

    private String attachmentText(Object raw) {
        String text = String.valueOf(raw);
        if (text.isBlank()) {
            return "";
        }
        try {
            JSONArray array = JSON.parseArray(text);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                String name = item == null ? null : item.getString("name");
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            return String.join("、", names);
        } catch (Exception ignored) {
            return text;
        }
    }

    private String renderValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                items.add(renderValue(item));
            }
            return String.join("、", items);
        }
        if (value instanceof Map<?, ?> map) {
            List<String> pairs = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                pairs.add(e.getKey() + ": " + renderValue(e.getValue()));
            }
            return "{" + String.join(", ", pairs) + "}";
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asRows(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add((Map<String, Object>) map);
            }
        }
        return rows;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private InputStream fontStream() {
        InputStream in = getClass().getResourceAsStream("/fonts/NotoSansSC-Regular.ttf");
        if (in == null) {
            throw new IllegalStateException("打印字体缺失（fonts/NotoSansSC-Regular.ttf）");
        }
        return in;
    }

    // ==================== 绘制画布 ====================

    /** A4 页面游标画布：段落、字段行、可分页表格（表头每页重复、行不截断）、页脚。 */
    private final class Canvas {

        private final PDDocument doc;
        private final PDType0Font font;
        private PDPage page;
        private PDPageContentStream stream;
        private float y;
        private final List<String> footers = new ArrayList<>();

        private Canvas(PDDocument doc, PDType0Font font) throws IOException {
            this.doc = doc;
            this.font = font;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            page = new PDPage(PAGE_SIZE);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            y = PAGE_SIZE.getHeight() - MARGIN;
        }

        private void ensure(float needed) throws IOException {
            if (y - needed < MARGIN + 24) {
                newPage();
            }
        }

        private void spacer(float h) throws IOException {
            y -= h;
        }

        /** 单行文本；超宽截断（段落请用 paragraph 换行）。 */
        private void text(String value, float x, float size) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, y);
            stream.showText(truncate(value, size, x));
            stream.endText();
        }

        private String truncate(String value, float size, float x) throws IOException {
            if (value == null) {
                return "";
            }
            float maxWidth = PAGE_WIDTH - (x - MARGIN) - 2;
            if (stringWidth(value, size) <= maxWidth) {
                return value;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                String candidate = sb + String.valueOf(value.charAt(i));
                if (stringWidth(candidate, size) > maxWidth) {
                    break;
                }
                sb.append(value.charAt(i));
            }
            return sb + "…";
        }

        private float stringWidth(String value, float size) throws IOException {
            return font.getStringWidth(value) / 1000f * size;
        }

        /** 自动换行段落；height 为段后间距系数。 */
        private void paragraph(String content, float size, float gap) throws IOException {
            for (String line : wrap(content, size, PAGE_WIDTH)) {
                ensure(size + 4);
                y -= size + 2;
                text(line, MARGIN, size);
            }
            y -= size * gap + 6;
        }

        /** 字段行：加粗标签（同字体）+ 值，值过长续行。 */
        private void fieldLine(String label, String value) throws IOException {
            float size = 11f;
            String prefix = label + "：";
            float prefixWidth = stringWidth(prefix, size);
            List<String> valueLines = wrap(value, size, PAGE_WIDTH - prefixWidth);
            for (int i = 0; i < valueLines.size(); i++) {
                ensure(size + 4);
                y -= size + 4;
                if (i == 0) {
                    text(prefix, MARGIN, size);
                }
                text(valueLines.get(i), MARGIN + prefixWidth, size);
            }
        }

        /** 表格：表头每页重复；行内换行后整行不跨页（行高按最大行计算，行不截断）。 */
        private void table(List<Column> columns, List<Map<String, Object>> rows,
                           boolean repeatHeaderEveryPage) throws IOException {
            float size = 9.5f;
            float padding = 4f;
            float lineGap = size + 2;
            drawTableHeader(columns, size, padding);
            y -= lineGap + padding;
            for (Map<String, Object> row : rows) {
                // 每列换行文本
                List<List<String>> cellLines = new ArrayList<>();
                int maxLines = 1;
                for (Column column : columns) {
                    List<String> lines = wrap(renderValue(row.get(column.key())), size,
                            column.width() - 2 * padding);
                    cellLines.add(lines);
                    maxLines = Math.max(maxLines, lines.size());
                }
                float rowHeight = maxLines * lineGap + 2 * padding;
                // 行不跨页：放不下则新页并重复表头
                if (y - rowHeight < MARGIN + 24) {
                    newPage();
                    drawTableHeader(columns, size, padding);
                    y -= lineGap + padding;
                }
                float rowTop = y;
                // 行底色（浅灰）与竖向网格线
                stream.setNonStrokingColor(0.96f);
                stream.addRect(MARGIN, rowTop - rowHeight, PAGE_WIDTH, rowHeight);
                stream.fill();
                stream.setNonStrokingColor(0f);
                float x = MARGIN;
                for (int c = 0; c < columns.size(); c++) {
                    Column column = columns.get(c);
                    List<String> lines = cellLines.get(c);
                    float cy = rowTop - padding - size;
                    stream.beginText();
                    stream.setFont(font, size);
                    stream.newLineAtOffset(x + padding, cy);
                    stream.showText(lines.isEmpty() ? "" : lines.get(0));
                    stream.endText();
                    for (int li = 1; li < lines.size(); li++) {
                        cy -= lineGap;
                        stream.beginText();
                        stream.setFont(font, size);
                        stream.newLineAtOffset(x + padding, cy);
                        stream.showText(lines.get(li));
                        stream.endText();
                    }
                    x += column.width();
                }
                y -= rowHeight;
                // 行分隔线
                stream.moveTo(MARGIN, y);
                stream.lineTo(MARGIN + PAGE_WIDTH, y);
                stream.setLineWidth(0.4f);
                stream.setStrokingColor(0.8f);
                stream.stroke();
                stream.setStrokingColor(0f);
            }
            y -= 6;
        }

        private void drawTableHeader(List<Column> columns, float size, float padding)
                throws IOException {
            ensure(size + 2 * padding + 8);
            stream.setNonStrokingColor(0.92f);
            stream.addRect(MARGIN, y - size - 2 * padding, PAGE_WIDTH, size + 2 * padding);
            stream.fill();
            stream.setNonStrokingColor(0f);
            float x = MARGIN;
            y -= padding;
            for (Column column : columns) {
                text(column.title(), x + padding, size);
                x += column.width();
            }
            y -= size + padding;
        }

        private List<String> wrap(String content, float size, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            if (content == null || content.isEmpty()) {
                lines.add("");
                return lines;
            }
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '\n') {
                    lines.add(current.toString());
                    current.setLength(0);
                    continue;
                }
                String candidate = current.toString() + ch;
                if (stringWidth(candidate, size) > maxWidth) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                current.append(ch);
            }
            lines.add(current.toString());
            return lines;
        }

        /** 页脚（全部页最后统一画）：页脚文案 + 版本说明 + 第 x/y 页。 */
        private void drawFooters(String footerText, String versionNote) throws IOException {
            // 先关闭正文流，再以 APPEND 模式补页脚（PDFBox 不允许同页双开流）
            if (stream != null) {
                stream.close();
                stream = null;
            }
            if (notBlank(footerText)) {
                footers.add(footerText);
            }
            if (notBlank(versionNote)) {
                footers.add(versionNote);
            }
            int totalPages = doc.getNumberOfPages();
            for (int i = 0; i < totalPages; i++) {
                PDPage target = doc.getPage(i);
                try (PDPageContentStream footer = new PDPageContentStream(doc, target,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    float size = 8f;
                    float baseY = MARGIN - 14;
                    float x = MARGIN;
                    for (String line : footers) {
                        footer.beginText();
                        footer.setFont(font, size);
                        footer.newLineAtOffset(x, baseY);
                        footer.showText(truncateOn(line, size, x));
                        footer.endText();
                        baseY -= size + 3;
                    }
                    String pageLabel = "第 " + (i + 1) + " / " + totalPages + " 页";
                    float w = font.getStringWidth(pageLabel) / 1000f * size;
                    footer.beginText();
                    footer.setFont(font, size);
                    footer.newLineAtOffset(MARGIN + PAGE_WIDTH - w, MARGIN - 14);
                    footer.showText(pageLabel);
                    footer.endText();
                }
            }
        }

        private String truncateOn(String value, float size, float x) throws IOException {
            return truncate(value, size, x);
        }
    }

}

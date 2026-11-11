package com.sw.ck.form.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import com.sw.ck.system.api.dict.DictFacade;
import com.sw.ck.system.api.dict.DictItemDTO;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 明细表导出（F2-6）单元测试：列头/显示值/字段隐藏/公式注入防护/全部行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("明细表导出 Excel")
class FormDetailTableExportServiceTest {

    private static final String FORM_KEY = "demo_form";
    private static final String RECORD_ID = "rec-001";
    private static final String DEFINITION = """
            {
              "fields": [
                {"name": "title", "type": "TEXT", "label": "标题"},
                {"name": "items", "type": "TABLE", "label": "采购明细", "subFields": [
                  {"name": "product", "type": "TEXT", "label": "产品名称"},
                  {"name": "owner", "type": "USER", "label": "负责人"},
                  {"name": "dept", "type": "DEPT", "label": "所属部门"},
                  {"name": "level", "type": "DICT", "dictType": "risk_level", "label": "风险等级"},
                  {"name": "tags", "type": "MULTISELECT", "source": "custom", "label": "标签",
                   "options": [{"key": "a", "value": "红"}, {"key": "b", "value": "蓝"}]},
                  {"name": "due", "type": "DATE", "label": "截止时间"},
                  {"name": "dueAt", "type": "DATE", "label": "验收时间", "format": "datetime"},
                  {"name": "amount", "type": "NUMBER", "label": "金额"},
                  {"name": "remark", "type": "TEXT", "label": "备注"},
                  {"name": "secret", "type": "TEXT", "label": "隐藏字段"}
                ]}
              ]
            }
            """;

    @Mock
    private FormDefService formDefService;
    @Mock
    private FormDataQueryService formDataQueryService;
    @Mock
    private UserQueryFacade userQueryFacade;
    @Mock
    private DeptQueryFacade deptQueryFacade;
    @Mock
    private DictFacade dictFacade;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private FormDetailTableExportService service;

    @BeforeEach
    void setUp() {
        service = new FormDetailTableExportService(formDefService, formDataQueryService,
                userQueryFacade, deptQueryFacade, dictFacade, jdbcTemplate, new ObjectMapper());
        LoginUser user = new LoginUser();
        user.setUserId(2L);
        user.setTenantId(9001L);
        LoginUserHolder.set(user);

        FormDefDTO formDef = new FormDefDTO();
        formDef.setId("form-1");
        formDef.setFormKey(FORM_KEY);
        formDef.setName("演示表单");
        when(formDefService.getFormDefByKey(FORM_KEY)).thenReturn(formDef);
        when(formDefService.getDefinition(FORM_KEY)).thenReturn(DEFINITION);
        when(userQueryFacade.getUserDisplayNames(anyCollection()))
                .thenReturn(Optional.of(Map.of(21L, "张三")));
        when(deptQueryFacade.findActiveDeptNames(anyCollection()))
                .thenReturn(Optional.of(Map.of(91001L, "总公司")));
        when(dictFacade.listByType(anyString())).thenReturn(Optional.of(List.of(
                DictItemDTO.builder().dictType("risk_level").code("high").label("高").build())));
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    @DisplayName("列头用显示名、值用显示值，多选顿号分隔，隐藏字段为空，全部行导出")
    void exportUsesDisplayValuesAndAllRows() throws Exception {
        Map<String, Object> hiddenRow = new LinkedHashMap<>();
        hiddenRow.put("product", "服务器");
        hiddenRow.put("owner", "21");
        hiddenRow.put("dept", "91001");
        hiddenRow.put("level", "high");
        hiddenRow.put("tags", "[\"a\",\"b\"]");
        hiddenRow.put("due", "2026-11-11T03:04:51.972153");
        hiddenRow.put("dueAt", "2026-11-11T03:04:51.972153");
        hiddenRow.put("amount", new BigDecimal("12.50"));
        hiddenRow.put("remark", "普通备注");
        // secret 列缺失 = 字段级隐藏（详情投影剔除）

        Map<String, Object> secondRow = new LinkedHashMap<>(hiddenRow);
        secondRow.put("product", "交换机");
        secondRow.put("remark", 10); // 行数多于页面一页（此处验证不受分页影响）
        when(formDataQueryService.getRecordDetail(FORM_KEY, RECORD_ID))
                .thenReturn(Map.of("title", "采购单", "items", List.of(hiddenRow, secondRow)));

        FormDetailTableExportService.ExportFile file =
                service.exportTable(FORM_KEY, RECORD_ID, "items");

        assertThat(file.filename()).isEqualTo("演示表单_采购明细_rec-001.xlsx");
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2); // 表头 + 2 行
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("产品名称");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("负责人");
            assertThat(header.getCell(6).getStringCellValue()).isEqualTo("验收时间");
            assertThat(header.getCell(8).getStringCellValue()).isEqualTo("备注");

            Row first = sheet.getRow(1);
            assertThat(first.getCell(0).getStringCellValue()).isEqualTo("服务器");
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo("张三");
            assertThat(first.getCell(2).getStringCellValue()).isEqualTo("总公司");
            assertThat(first.getCell(3).getStringCellValue()).isEqualTo("高");
            assertThat(first.getCell(4).getStringCellValue()).isEqualTo("红、蓝");
            // 缺省日期字段只到日；datetime 字段到分（U2）
            assertThat(first.getCell(5).getStringCellValue()).isEqualTo("2026-11-11");
            assertThat(first.getCell(6).getStringCellValue()).isEqualTo("2026-11-11 03:04");
            assertThat(first.getCell(7).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(first.getCell(7).getNumericCellValue()).isEqualTo(12.5);
            // 隐藏字段列在表头存在但值为空（与页面空白单元格一致），不泄漏原值
            assertThat(first.getCell(9).getStringCellValue()).isEmpty();
        }
    }

    @Test
    @DisplayName("以 = + - @ 开头的业务文本按文本单元格写入（公式注入防护）")
    void exportEscapesFormulaLikeText() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("product", "=HYPERLINK(\"http://evil\",\"x\")");
        row.put("owner", "");
        row.put("dept", "");
        row.put("level", "");
        row.put("tags", "");
        row.put("due", null);
        row.put("amount", null);
        row.put("remark", "@SUM(1,2)");
        when(formDataQueryService.getRecordDetail(FORM_KEY, RECORD_ID))
                .thenReturn(Map.of("items", List.of(row)));

        FormDetailTableExportService.ExportFile file =
                service.exportTable(FORM_KEY, RECORD_ID, "items");

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            Row first = workbook.getSheetAt(0).getRow(1);
            Cell product = first.getCell(0);
            assertThat(product.getCellType()).isEqualTo(CellType.STRING);
            assertThat(product.getStringCellValue()).startsWith("=HYPERLINK");
            assertThat(product.getCellStyle().getDataFormatString()).isEqualTo("@");
            Cell remark = first.getCell(8);
            assertThat(remark.getCellType()).isEqualTo(CellType.STRING);
            assertThat(remark.getStringCellValue()).isEqualTo("@SUM(1,2)");
        }
    }
}

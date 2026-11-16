package com.sw.ck.form.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.common.response.R;
import com.sw.ck.form.api.dto.FormDataQueryRequest;
import com.sw.ck.form.service.FormImportExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.log4j.Log4j2;
/**
 * 表单数据导入导出入口。
 *
 * <p>端点路径：</p>
 * <ul>
 *   <li>{@code GET /api/form/data/{formKey}/template} — 下载表单模板</li>
 *   <li>{@code POST /api/form/data/{formKey}/import} — 导入表单数据（待实现）</li>
 *   <li>{@code GET /api/form/data/{formKey}/export} — 导出表单数据（待实现）</li>
 * </ul>
 */
@RestController
@RequestMapping("/form/data")
@Log4j2
public class FormImportExportController {

    @Autowired
    private FormImportExportService formImportExportService;

    /**
     * 下载表单模板。
     *
     * <p>返回指定表单的 .xlsx 模板文件，包含：</p>
     * <ul>
     *   <li>第一行：字段显示名称（中文标签）</li>
     *   <li>第二行：字段映射标识（稳定的内部标识符）</li>
     * </ul>
     *
     * @param formKey 表单业务标识
     * @return .xlsx 文件
     */
    @PreAuthorize("@ss.hasPermi('form:data:template')")
    @GetMapping("/{formKey}/template")
    public ResponseEntity<byte[]> downloadTemplate(@PathVariable("formKey") String formKey) {
        log.info("Download template: formKey={}", formKey);

        byte[] content = formImportExportService.generateTemplate(formKey);

        String filename = formKey + "_template.xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(content.length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(content);
    }

    /**
     * 导入表单数据。
     *
     * <p>导入语义：</p>
     * <ul>
     *   <li>导入目标必须是用户当前选择且有权操作的单个表单</li>
     *   <li>采用新增数据语义，不提供更新、覆盖、合并或 upsert</li>
     *   <li>每行必须经过与现有单条新增一致的校验</li>
     *   <li>采用整批原子失败策略</li>
     *   <li>必须返回行/字段级错误反馈</li>
     * </ul>
     *
     * @param formKey 表单业务标识
     * @param file .xlsx 文件
     * @return 导入结果
     */
    @PreAuthorize("@ss.hasPermi('form:data:import')")
    @PostMapping("/{formKey}/import")
    public R<FormImportExportService.ImportResult> importData(
            @PathVariable("formKey") String formKey,
            @RequestParam("file") MultipartFile file) {
        log.info("Import data: formKey={}, filename={}, size={}", formKey, file.getOriginalFilename(), file.getSize());

        if (file == null || file.isEmpty()) {
            return R.fail(1499, "导入失败: 上传文件为空");
        }
        if (file.getSize() > FormImportExportService.MAX_IMPORT_FILE_BYTES) {
            return R.fail(1499, "导入失败: 文件超过大小上限 "
                    + (FormImportExportService.MAX_IMPORT_FILE_BYTES / 1024 / 1024) + "MB");
        }

        try {
            FormImportExportService.ImportResult result = formImportExportService.importData(formKey, file.getInputStream());
            return R.ok(result);
        } catch (Exception e) {
            log.error("Import failed: formKey={}", formKey, e);
            return R.fail(1499, "导入失败: " + e.getMessage());
        }
    }

    /**
     * 导出表单数据。
     *
     * <p>导出语义：</p>
     * <ul>
     *   <li>导出对象是指定表单在当前查询条件、租户边界和数据权限下可见的数据</li>
     *   <li>导出列与当前有效表单字段对应</li>
     *   <li>无数据时仍应返回结构正确、可打开且只有表头的文件</li>
     *   <li>导出大数据量必须采用有界方式</li>
     * </ul>
     *
     * @param formKey 表单业务标识
     * @param queryRequest 查询请求（可选）
     * @return .xlsx 文件
     */
    @PreAuthorize("@ss.hasPermi('form:data:export')")
    @PostMapping("/{formKey}/export")
    public ResponseEntity<byte[]> exportData(
            @PathVariable("formKey") String formKey,
            @RequestBody(required = false) FormDataQueryRequest queryRequest) {
        log.info("Export data: formKey={}", formKey);

        byte[] content = formImportExportService.exportData(formKey, queryRequest);

        String filename = formKey + "_data.xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(content.length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(content);
    }
}

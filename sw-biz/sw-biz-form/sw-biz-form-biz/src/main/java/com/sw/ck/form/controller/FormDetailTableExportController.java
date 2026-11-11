package com.sw.ck.form.controller;

import com.sw.ck.form.service.FormDetailTableExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 明细表导出入口（1.0.0 F2-6）。
 * <p>
 * 从表单记录详情导出指定的一张明细表（TABLE 字段）为 .xlsx。
 * 记录读取与字段级隐藏沿用详情查询的权限链路；导出全部子表行，
 * 文件名包含表单名、明细表名与记录编号。
 * </p>
 */
@RestController
@RequestMapping("/form/data")
public class FormDetailTableExportController {

    private static final Logger log = LoggerFactory.getLogger(FormDetailTableExportController.class);

    private final FormDetailTableExportService exportService;

    public FormDetailTableExportController(FormDetailTableExportService exportService) {
        this.exportService = exportService;
    }

    @PreAuthorize("@ss.hasPermi('form:data:query')")
    @GetMapping("/{formKey}/{recordId}/tables/{fieldName}/export")
    public ResponseEntity<byte[]> exportTable(@PathVariable("formKey") String formKey,
                                              @PathVariable("recordId") String recordId,
                                              @PathVariable("fieldName") String fieldName) {
        log.info("Export detail table: formKey={}, recordId={}, field={}", formKey, recordId, fieldName);
        FormDetailTableExportService.ExportFile file = exportService.exportTable(formKey, recordId, fieldName);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(file.content().length);
        return ResponseEntity.ok().headers(headers).body(file.content());
    }
}

package com.sw.ck.form.controller;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.response.R;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.bundle.FormBundleImportReport;
import com.sw.ck.form.bundle.FormBundlePreviewResult;
import com.sw.ck.form.entity.FormAppImportRecordEntity;
import com.sw.ck.form.service.FormAppBundleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 应用整包导入导出（0.2.2 F2，D022-10～15）。
 * <p>
 * 导出为后台权限 {@code form:app:export}；导入为 {@code form:app:import}（与
 * 应用管理同权限位的独立按钮，V0.2.6 菜单脚本授予持有应用管理的角色）。
 * 导入两步走（D022-15）：{@code /preview} 预检返回对象清单、意见表单冲突与
 * 外部引用匹配；{@code /confirm} 携带同一文件与意见表单 key 解决方案，单事务
 * 写入（失败整体回滚）。服务端不保存上传包，两步之间无会话状态。
 * </p>
 */
@RestController
@RequestMapping("/form/app/manage/bundle")
public class FormAppBundleController {

    private static final Logger log = LoggerFactory.getLogger(FormAppBundleController.class);

    private final FormAppBundleService bundleService;

    public FormAppBundleController(FormAppBundleService bundleService) {
        this.bundleService = bundleService;
    }

    /** 导出应用整包（当前版本定义；不含业务数据、附件与密钥类信息，D022-10）。 */
    @PreAuthorize("@ss.hasPermi('form:app:export')")
    @GetMapping("/{appId}/export")
    public ResponseEntity<byte[]> exportBundle(@PathVariable("appId") String appId) {
        FormAppBundleService.ExportedBundle bundle = bundleService.exportAppBundle(appId);
        String fileName = bundle.fileName();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + urlEncode(fileName))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bundle.content());
    }

    /** 导入第一步：预检（对象清单、意见表单冲突、外部引用匹配），不写库。 */
    @PreAuthorize("@ss.hasPermi('form:app:import')")
    @PostMapping("/preview")
    public R<FormBundlePreviewResult> preview(@RequestParam("file") MultipartFile file) {
        return R.ok(bundleService.previewBundle(readBundle(file)));
    }

    /**
     * 导入第二步：确认导入。重复上传同一包文件（服务端无状态），单事务写入；
     * 失败整体回滚且不落导入记录（D022-15）。
     *
     * @param opinionKeysJson 意见表单冲突解决：[{"formKey":"包内原key","newKey":"新key"}]
     */
    @PreAuthorize("@ss.hasPermi('form:app:import')")
    @PostMapping("/confirm")
    public R<FormBundleImportReport> confirm(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "opinionKeys", required = false)
                                            String opinionKeysJson,
                                            @RequestParam(value = "fileName", required = false)
                                            String fileName) {
        FormBundleImportReport report =
                bundleService.confirmImport(readBundle(file), opinionKeysJson);
        log.info("Bundle confirm import done: appId={}, operatorFile={}",
                report.getAppId(), fileName);
        return R.ok(report);
    }

    /** 导入记录列表（当前租户，含报告 JSON 摘要）。 */
    @PreAuthorize("@ss.hasPermi('form:app:import')")
    @GetMapping("/import-records")
    public R<List<FormAppImportRecordEntity>> importRecords() {
        return R.ok(bundleService.listImportRecords());
    }

    /** 单条导入记录。 */
    @PreAuthorize("@ss.hasPermi('form:app:import')")
    @GetMapping("/import-records/{id}")
    public R<FormAppImportRecordEntity> importRecord(@PathVariable("id") String id) {
        return R.ok(bundleService.getImportRecord(id));
    }

    /** 下载导入报告 JSON（随导入记录持久化的报告原文）。 */
    @PreAuthorize("@ss.hasPermi('form:app:import')")
    @GetMapping("/import-records/{id}/report")
    public ResponseEntity<byte[]> importRecordReport(@PathVariable("id") String id) {
        FormAppImportRecordEntity record = bundleService.getImportRecord(id);
        if (record.getReportJson() == null || record.getReportJson().isBlank()) {
            throw new BaseException(FormErrorCode.BUNDLE_RECORD_NOT_FOUND.getCode(),
                    "该导入记录没有可下载的报告");
        }
        String fileName = sanitizeFileName(record.getAppName()) + "_import_report.json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + urlEncode(fileName))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(record.getReportJson().getBytes(StandardCharsets.UTF_8));
    }

    /** 读取上传包字节：为空或超限直接结构化拒绝（容器上限 6MB，业务上限 5MB）。 */
    private byte[] readBundle(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(), "请选择应用导出包文件");
        }
        if (file.getSize() > FormAppBundleService.MAX_BUNDLE_FILE_BYTES) {
            throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(),
                    "导入包超过大小上限 " + (FormAppBundleService.MAX_BUNDLE_FILE_BYTES / 1024 / 1024) + "MB");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(), "读取导入包失败，请重试");
        }
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String sanitizeFileName(String name) {
        return name == null ? "bundle" : name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }
}

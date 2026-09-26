package com.sw.ck.storage.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.storage.api.StorageFacade;
import com.sw.ck.storage.api.StorageUploadResult;
import com.sw.ck.storage.entity.StorageFile;
import com.sw.ck.storage.service.StorageFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 文件存储 REST 控制器。
 * <p>
 * 提供文件上传/下载/删除/列表/详情接口。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/storage/files")
@RequiredArgsConstructor
public class StorageController {

    private final StorageFacade storageFacade;
    private final StorageFileService storageFileService;

    /**
     * 上传文件。
     */
    @PostMapping("/upload")
    @PreAuthorize("@ss.hasPermi('storage:upload')")
    public R<StorageUploadResult> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "上传文件不能为空");
        }
        StorageUploadResult result = storageFacade.upload(
                        file.getInputStream(),
                        file.getOriginalFilename(),
                        file.getContentType())
                // 契约：上传恒 present，empty 属实现违约
                .orElseThrow(() -> new IllegalStateException("文件上传未返回结果"));
        return R.ok(result);
    }

    /**
     * 文件列表（分页，create_time 倒序；数据范围纳管入口，见 {@code StorageFileService#pageFiles}）。
     */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('storage:list')")
    public R<Page<StorageFile>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(storageFileService.pageFiles(page, size));
    }

    /**
     * 文件详情。
     */
    @GetMapping("/{storageKey}")
    @PreAuthorize("@ss.hasPermi('storage:list')")
    public R<StorageFile> info(@PathVariable String storageKey) {
        StorageFile file = storageFileService.findByStorageKey(storageKey);
        if (file == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "文件不存在");
        }
        return R.ok(file);
    }

    /**
     * 删除文件。
     */
    @DeleteMapping("/{storageKey}")
    @PreAuthorize("@ss.hasPermi('storage:delete')")
    public R<Void> delete(@PathVariable String storageKey) {
        // 契约：删除恒 present（记录不存在为 ALREADY_APPLIED 幂等）；对外仍统一返回 R.ok()
        storageFacade.delete(storageKey)
        .orElseThrow(() -> new IllegalStateException(
                "StorageFacade#delete 契约恒 present，empty 属契约违约"));
        return R.ok();
    }

    /**
     * 下载文件。
     */
    @GetMapping("/{storageKey}/download")
    @PreAuthorize("@ss.hasPermi('storage:download')")
    public ResponseEntity<InputStreamResource> download(@PathVariable String storageKey) {
        StorageFile file = storageFileService.findByStorageKey(storageKey);
        if (file == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "文件不存在");
        }
        Optional<InputStream> inputStream = storageFacade.download(storageKey);
        if (inputStream.isEmpty()) {
            // 契约：empty = 记录不存在或已逻辑删除；对外保持原 NOT_FOUND 语义
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "文件不存在");
        }
        InputStreamResource resource = new InputStreamResource(inputStream.orElseThrow());
        String encodedFileName = URLEncoder.encode(
                file.getOriginalName() != null ? file.getOriginalName() : "file",
                StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        file.getContentType() != null ? file.getContentType() : "application/octet-stream"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFileName)
                .body(resource);
    }
}

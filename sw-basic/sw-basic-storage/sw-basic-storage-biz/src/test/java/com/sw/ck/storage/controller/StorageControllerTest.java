package com.sw.ck.storage.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.storage.api.StorageFacade;
import com.sw.ck.storage.api.StorageMutationOutcome;
import com.sw.ck.storage.api.StorageUploadResult;
import com.sw.ck.storage.entity.StorageFile;
import com.sw.ck.storage.service.StorageFileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link StorageController} 单元测试。
 * <p>
 * 覆盖全部 5 个端点的 happy path + 异常路径。
 * 纯 Mockito，不装载 Spring 上下文。
 * </p>
 */
@DisplayName("文件存储控制器测试")
class StorageControllerTest {

    private final StorageFacade storageFacade = mock(StorageFacade.class);
    private final StorageFileService storageFileService = mock(StorageFileService.class);

    private final StorageController controller = new StorageController(storageFacade, storageFileService);

    @Test
    @DisplayName("storage 方法边界 → 上传/删除/下载都有显式 permission")
    void endpoints_shouldDeclareExplicitPermissions() throws NoSuchMethodException {
        assertThat(StorageController.class.getMethod("upload", org.springframework.web.multipart.MultipartFile.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('storage:upload')");
        assertThat(StorageController.class.getMethod("delete", String.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('storage:delete')");
        assertThat(StorageController.class.getMethod("download", String.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('storage:download')");
    }

    // ==================== 测试数据工厂 ====================

    private void mockListMocks() {
        // 列表入口已改为 StorageFileService.pageFiles（数据范围纳管），不再经 lambdaQuery
    }

    private StorageFile createFile(String storageKey) {
        StorageFile file = new StorageFile();
        file.setId(10001L);
        file.setStorageKey(storageKey);
        file.setOriginalName("test.pdf");
        file.setStorageName(storageKey + ".pdf");
        file.setFileSize(1024L);
        file.setContentType("application/pdf");
        file.setFileExt("pdf");
        file.setProviderType("local");
        file.setBucketName("./uploads");
        file.setStorageUrl("http://localhost:8080/storage/files/" + storageKey + "/download");
        return file;
    }

    private StorageUploadResult createResult(String storageKey) {
        return StorageUploadResult.builder()
                .storageKey(storageKey)
                .storageName(storageKey + ".pdf")
                .storageUrl("http://localhost:8080/storage/files/" + storageKey + "/download")
                .fileSize(1024L)
                .build();
    }

    private MockMultipartFile createMultipartFile() {
        return new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "dummy file content".getBytes(StandardCharsets.UTF_8));
    }

    // ==================== POST /storage/files/upload ====================

    @Test
    @DisplayName("上传文件成功 → 返回 StorageUploadResult")
    void upload_shouldReturnResult() throws Exception {
        String storageKey = "key-001";
        MockMultipartFile multipartFile = createMultipartFile();
        StorageUploadResult expectedResult = createResult(storageKey);

        when(storageFacade.upload(any(InputStream.class), eq("test.pdf"), eq("application/pdf")))
                .thenReturn(Optional.of(expectedResult));

        R<StorageUploadResult> result = controller.upload(multipartFile);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().getStorageKey()).isEqualTo(storageKey);
        assertThat(result.getData().getStorageName()).isEqualTo(storageKey + ".pdf");
        assertThat(result.getData().getFileSize()).isEqualTo(1024L);
        verify(storageFacade).upload(any(InputStream.class), eq("test.pdf"), eq("application/pdf"));
    }

    @Test
    @DisplayName("上传空文件 → 抛 BaseException(PARAM_ERROR)")
    void upload_emptyFile_shouldThrow() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> controller.upload(emptyFile))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不能为空");
        verify(storageFacade, never()).upload(any(), any(), any());
    }

    // ==================== GET /storage/files ====================

    @Test
    @DisplayName("分页列表 → 返回 Page 含 records + total")
    void list_shouldReturnPage() {
        mockListMocks();
        StorageFile f1 = createFile("key-001");
        StorageFile f2 = createFile("key-002");
        Page<StorageFile> expectedPage = new Page<>(1, 20);
        expectedPage.setRecords(List.of(f1, f2));
        expectedPage.setTotal(2L);

        when(storageFileService.pageFiles(1L, 20L)).thenReturn(expectedPage);

        R<Page<StorageFile>> result = controller.list(1L, 20L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().getRecords()).hasSize(2);
        assertThat(result.getData().getTotal()).isEqualTo(2L);
        verify(storageFileService).pageFiles(1L, 20L);
    }

    @Test
    @DisplayName("分页列表无数据 → 返回空 records, total=0")
    void list_empty_shouldReturnEmptyPage() {
        mockListMocks();
        Page<StorageFile> emptyPage = new Page<>(1, 20);
        emptyPage.setRecords(List.of());
        emptyPage.setTotal(0L);

        when(storageFileService.pageFiles(1L, 20L)).thenReturn(emptyPage);

        R<Page<StorageFile>> result = controller.list(1L, 20L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getRecords()).isEmpty();
        assertThat(result.getData().getTotal()).isZero();
    }

    @Test
    @DisplayName("分页列表传参 page=1, size=20 → service 收到正确参数")
    void list_shouldPassDefaultPageAndSize() {
        mockListMocks();
        Page<StorageFile> emptyPage = new Page<>(1, 20);
        emptyPage.setRecords(List.of());
        emptyPage.setTotal(0L);

        when(storageFileService.pageFiles(1L, 20L)).thenReturn(emptyPage);

        controller.list(1L, 20L);

        verify(storageFileService).pageFiles(1L, 20L);
    }

    // ==================== GET /storage/files/{storageKey} ====================

    @Test
    @DisplayName("文件详情 → 返回 StorageFile")
    void info_shouldReturnFile() {
        StorageFile file = createFile("key-001");
        when(storageFileService.findByStorageKey("key-001")).thenReturn(file);

        R<StorageFile> result = controller.info("key-001");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().getStorageKey()).isEqualTo("key-001");
        assertThat(result.getData().getOriginalName()).isEqualTo("test.pdf");
        assertThat(result.getData().getContentType()).isEqualTo("application/pdf");
        assertThat(result.getData().getFileSize()).isEqualTo(1024L);
        verify(storageFileService).findByStorageKey("key-001");
    }

    @Test
    @DisplayName("文件不存在 → 抛 BaseException(NOT_FOUND)")
    void info_notFound_shouldThrow() {
        when(storageFileService.findByStorageKey("key-999")).thenReturn(null);

        assertThatThrownBy(() -> controller.info("key-999"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不存在");
        verify(storageFileService).findByStorageKey("key-999");
    }

    // ==================== DELETE /storage/files/{storageKey} ====================

    @Test
    @DisplayName("删除文件成功 → 返回 R.ok()")
    void delete_shouldReturnOk() {
        when(storageFacade.delete("key-001")).thenReturn(Optional.of(StorageMutationOutcome.APPLIED));

        R<Void> result = controller.delete("key-001");

        assertThat(result.getCode()).isZero();
        verify(storageFacade).delete("key-001");
    }

    @Test
    @DisplayName("删除不存在的文件（合法幂等）→ 仍返回 R.ok()")
    void delete_alreadyApplied_shouldReturnOk() {
        when(storageFacade.delete("key-404"))
                .thenReturn(Optional.of(StorageMutationOutcome.ALREADY_APPLIED));

        R<Void> result = controller.delete("key-404");

        assertThat(result.getCode()).isZero();
        verify(storageFacade).delete("key-404");
    }

    // ==================== GET /storage/files/{storageKey}/download ====================

    @Test
    @DisplayName("下载文件 → 返回 InputStreamResource + 正确 Content-Type + Content-Disposition")
    void download_shouldReturnResource() {
        StorageFile file = createFile("key-001");
        InputStream inputStream = new ByteArrayInputStream("file content".getBytes(StandardCharsets.UTF_8));
        when(storageFileService.findByStorageKey("key-001")).thenReturn(file);
        when(storageFacade.download("key-001")).thenReturn(Optional.of(inputStream));

        ResponseEntity<InputStreamResource> result = controller.download("key-001");

        assertThat(result.getStatusCodeValue()).isEqualTo(200);
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment; filename*=UTF-8''");
        assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("test.pdf");
        assertThat(result.getBody()).isNotNull();
        verify(storageFileService).findByStorageKey("key-001");
        verify(storageFacade).download("key-001");
    }

    @Test
    @DisplayName("下载文件不存在 → 抛 BaseException(NOT_FOUND)")
    void download_notFound_shouldThrow() {
        when(storageFileService.findByStorageKey("key-999")).thenReturn(null);

        assertThatThrownBy(() -> controller.download("key-999"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不存在");
        verify(storageFileService).findByStorageKey("key-999");
        verify(storageFacade, never()).download(anyString());
    }

    @Test
    @DisplayName("记录已逻辑删除（facade 返回 empty）→ 抛 BaseException(NOT_FOUND)")
    void download_emptyFromFacade_shouldThrowNotFound() {
        StorageFile file = createFile("key-001");
        when(storageFileService.findByStorageKey("key-001")).thenReturn(file);
        when(storageFacade.download("key-001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.download("key-001"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不存在");
        verify(storageFacade).download("key-001");
    }

    @Test
    @DisplayName("下载文件 content-type 为 null → 降级为 application/octet-stream")
    void download_nullContentType_shouldDefaultToOctetStream() {
        StorageFile file = createFile("key-001");
        file.setContentType(null);
        InputStream inputStream = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));
        when(storageFileService.findByStorageKey("key-001")).thenReturn(file);
        when(storageFacade.download("key-001")).thenReturn(Optional.of(inputStream));

        ResponseEntity<InputStreamResource> result = controller.download("key-001");

        assertThat(result.getStatusCodeValue()).isEqualTo(200);
        assertThat(result.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    }

    @Test
    @DisplayName("下载文件 originalName 为 null → 降级为「file」，不抛 NPE")
    void download_nullOriginalName_shouldDefaultToFile() {
        StorageFile file = createFile("key-001");
        file.setOriginalName(null);
        InputStream inputStream = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));
        when(storageFileService.findByStorageKey("key-001")).thenReturn(file);
        when(storageFacade.download("key-001")).thenReturn(Optional.of(inputStream));

        ResponseEntity<InputStreamResource> result = controller.download("key-001");

        assertThat(result.getStatusCodeValue()).isEqualTo(200);
        assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("filename*=UTF-8''file");
    }
}

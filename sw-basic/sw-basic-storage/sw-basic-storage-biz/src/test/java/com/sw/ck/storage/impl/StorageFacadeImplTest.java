package com.sw.ck.storage.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.storage.api.StorageMutationOutcome;
import com.sw.ck.storage.api.StorageUploadResult;
import com.sw.ck.storage.config.StorageProperties;
import com.sw.ck.storage.entity.StorageFile;
import com.sw.ck.storage.provider.StorageProvider;
import com.sw.ck.storage.provider.StorageProviderRegistry;
import com.sw.ck.storage.service.StorageFileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StorageFacadeImpl} 契约（{@link Optional}）语义测试。
 * <p>
 * 覆盖迁移后的关键语义：upload 恒 present；download 目标缺失 → empty、提供商不可用继续抛；
 * delete 幂等（记录缺失 → ALREADY_APPLIED）；exists 恒 present。
 * </p>
 */
@DisplayName("文件存储 Facade 契约语义测试")
class StorageFacadeImplTest {

    private final StorageProviderRegistry registry = mock(StorageProviderRegistry.class);
    private final StorageFileService storageFileService = mock(StorageFileService.class);
    private final StorageProperties storageProperties = new StorageProperties();

    private final StorageFacadeImpl facade =
            new StorageFacadeImpl(registry, storageFileService, storageProperties);

    private StorageFile createFile(String storageKey, String providerType) {
        StorageFile file = new StorageFile();
        file.setId(10001L);
        file.setStorageKey(storageKey);
        file.setProviderType(providerType);
        file.setOriginalName("test.pdf");
        file.setStorageUrl("http://localhost:8080/storage/files/" + storageKey + "/download");
        return file;
    }

    private InputStream stream() {
        return new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("upload")
    class UploadTests {

        @Test
        @DisplayName("上传成功 → present 上传结果，并落库")
        void upload_shouldReturnPresentResult() {
            StorageProvider provider = mock(StorageProvider.class);
            when(registry.getActiveProvider()).thenReturn(provider);
            when(provider.getType()).thenReturn("local");
            StorageUploadResult uploaded = StorageUploadResult.builder()
                    .storageKey("key-001")
                    .storageName("generated.pdf")
                    .storageUrl("http://localhost:8080/storage/files/key-001/download")
                    .fileSize(1024L)
                    .build();
            when(provider.upload(any(InputStream.class), anyString(), eq("application/pdf")))
                    .thenReturn(uploaded);

            Optional<StorageUploadResult> result =
                    facade.upload(stream(), "test.pdf", "application/pdf");

            assertThat(result).contains(uploaded);
            verify(storageFileService).save(any(StorageFile.class));
        }
    }

    @Nested
    @DisplayName("download")
    class DownloadTests {

        @Test
        @DisplayName("记录不存在或已逻辑删除 → empty（不再抛 NOT_FOUND）")
        void download_missingRecord_shouldReturnEmpty() {
            when(storageFileService.findByStorageKey("ghost")).thenReturn(null);

            assertThat(facade.download("ghost")).isEmpty();
        }

        @Test
        @DisplayName("记录存在且提供商可用 → present 输入流")
        void download_shouldReturnPresentStream() {
            StorageFile file = createFile("key-001", "local");
            InputStream inputStream = stream();
            StorageProvider provider = mock(StorageProvider.class);
            when(storageFileService.findByStorageKey("key-001")).thenReturn(file);
            when(registry.getProvider("local")).thenReturn(provider);
            when(provider.download("key-001")).thenReturn(inputStream);

            assertThat(facade.download("key-001")).contains(inputStream);
        }

        @Test
        @DisplayName("提供商不可用 → 继续抛 BaseException（不吞成 empty）")
        void download_providerUnavailable_shouldThrow() {
            StorageFile file = createFile("key-001", "minio");
            when(storageFileService.findByStorageKey("key-001")).thenReturn(file);
            when(registry.getProvider("minio")).thenReturn(null);

            assertThatThrownBy(() -> facade.download("key-001"))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("存储提供商不可用");
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("记录本就不存在 → present ALREADY_APPLIED（合法幂等，不抛）")
        void delete_missingRecord_shouldReturnAlreadyApplied() {
            when(storageFileService.findByStorageKey("ghost")).thenReturn(null);

            assertThat(facade.delete("ghost")).contains(StorageMutationOutcome.ALREADY_APPLIED);
            verify(storageFileService, never()).removeById(any(Serializable.class));
        }

        @Test
        @DisplayName("记录存在 → present APPLIED，软删除记录 + 提供商侧删除")
        void delete_existingRecord_shouldReturnApplied() {
            StorageFile file = createFile("key-001", "local");
            StorageProvider provider = mock(StorageProvider.class);
            when(storageFileService.findByStorageKey("key-001")).thenReturn(file);
            when(registry.getProvider("local")).thenReturn(provider);

            assertThat(facade.delete("key-001")).contains(StorageMutationOutcome.APPLIED);
            verify(provider).delete("key-001");
            verify(storageFileService).removeById(file.getId());
        }
    }

    @Nested
    @DisplayName("exists")
    class ExistsTests {

        @Test
        @DisplayName("storageKey 为空/空白 → present false（恒 present，不 empty）")
        void exists_blankKey_shouldReturnPresentFalse() {
            assertThat(facade.exists(null)).contains(false);
            assertThat(facade.exists("  ")).contains(false);
        }

        @Test
        @DisplayName("记录存在 → present true；不存在 → present false")
        void exists_shouldReturnPresentBoolean() {
            when(storageFileService.count(any(Wrapper.class))).thenReturn(1L);
            assertThat(facade.exists("key-001")).contains(true);

            when(storageFileService.count(any(Wrapper.class))).thenReturn(0L);
            assertThat(facade.exists("key-002")).contains(false);
        }
    }
}

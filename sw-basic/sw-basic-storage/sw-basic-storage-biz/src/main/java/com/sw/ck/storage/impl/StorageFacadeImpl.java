package com.sw.ck.storage.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.storage.api.StorageFacade;
import com.sw.ck.storage.api.StorageUploadResult;
import com.sw.ck.storage.config.StorageProperties;
import com.sw.ck.storage.entity.StorageFile;
import com.sw.ck.storage.provider.StorageProvider;
import com.sw.ck.storage.provider.StorageProviderRegistry;
import com.sw.ck.storage.service.StorageFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageFacadeImpl implements StorageFacade {

    private final StorageProviderRegistry registry;
    private final StorageFileService storageFileService;
    private final StorageProperties storageProperties;

    @Override
    public StorageUploadResult upload(InputStream inputStream, String originalName, String contentType) {
        // 1. 生成存储文件名：UUID（无连字符）+ 小写扩展名
        String storageName = generateStorageName(originalName);

        // 2. 获取活跃提供商并上传
        StorageProvider provider = registry.getActiveProvider();
        StorageUploadResult result = provider.upload(inputStream, storageName, contentType);

        // 3. 构建实体并落库
        StorageFile entity = new StorageFile();
        entity.setOriginalName(originalName);
        entity.setStorageKey(result.getStorageKey());
        entity.setStorageName(result.getStorageName());
        entity.setFileSize(result.getFileSize());
        entity.setContentType(contentType);
        entity.setFileExt(extractExtension(originalName));
        entity.setProviderType(provider.getType());
        entity.setBucketName(resolveBucketName(provider.getType()));
        entity.setStorageUrl(result.getStorageUrl());

        storageFileService.save(entity);

        log.info("文件上传成功: originalName={}, storageKey={}, provider={}, size={}",
                originalName, result.getStorageKey(), provider.getType(), result.getFileSize());

        return result;
    }

    @Override
    public boolean exists(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return false;
        }
        return storageFileService.count(new LambdaQueryWrapper<StorageFile>()
                .eq(StorageFile::getStorageKey, storageKey)) > 0;
    }

    @Override
    public InputStream download(String storageKey) {
        StorageFile file = getFileOrThrow(storageKey);
        StorageProvider provider = registry.getProvider(file.getProviderType());
        if (provider == null) {
            throw new BaseException(CommonErrorCode.SYSTEM_ERROR.getCode(),
                    "存储提供商不可用: " + file.getProviderType());
        }
        return provider.download(storageKey);
    }

    @Override
    public void delete(String storageKey) {
        StorageFile file = getFileOrThrow(storageKey);
        StorageProvider provider = registry.getProvider(file.getProviderType());
        if (provider != null) {
            provider.delete(storageKey);
        } else {
            log.warn("删除时提供商不可用，跳过提供商侧删除: storageKey={}, providerType={}",
                    storageKey, file.getProviderType());
        }
        storageFileService.removeById(file.getId());
        log.info("文件删除成功: storageKey={}, providerType={}", storageKey, file.getProviderType());
    }

    @Override
    public String getUrl(String storageKey) {
        StorageFile file = getFileOrThrow(storageKey);
        StorageProvider provider = registry.getProvider(file.getProviderType());
        if (provider != null) {
            return provider.getUrl(storageKey);
        }
        // 提供商不可用时返回缓存的 URL（降级）
        log.warn("URL 生成时提供商不可用，返回缓存 URL: storageKey={}", storageKey);
        return file.getStorageUrl();
    }

    // ---------- 私有方法 ----------

    /**
     * 生成存储文件名：UUID 去连字符 + 小写扩展名。
     */
    private String generateStorageName(String originalName) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String ext = extractExtension(originalName);
        return ext.isEmpty() ? uuid : uuid + "." + ext;
    }

    /**
     * 提取小写扩展名（不含点），无扩展名返回空串。
     */
    private String extractExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase();
    }

    /**
     * 按提供商类型解析存储桶名称。
     */
    private String resolveBucketName(String providerType) {
        StorageProperties.ProviderConfig config = storageProperties.getProviders().get(providerType);
        if (config == null) {
            return null;
        }
        if ("local".equals(providerType)) {
            return config.getBasePath() != null ? config.getBasePath() : "./uploads";
        }
        return config.getBucket();
    }

    /**
     * 按 storageKey 查询文件记录，不存在时抛 NOT_FOUND。
     */
    private StorageFile getFileOrThrow(String storageKey) {
        StorageFile file = storageFileService.findByStorageKey(storageKey);
        if (file == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "文件不存在: " + storageKey);
        }
        return file;
    }
}

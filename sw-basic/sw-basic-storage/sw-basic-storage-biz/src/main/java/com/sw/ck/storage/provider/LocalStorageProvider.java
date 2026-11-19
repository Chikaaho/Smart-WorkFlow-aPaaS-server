package com.sw.ck.storage.provider;

import com.sw.ck.storage.api.StorageUploadResult;
import com.sw.ck.storage.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地文件存储提供商。
 * <p>
 * 将文件存储到本地文件系统，按日期分目录：{basePath}/yyyy/MM/dd/{uuid}.{ext}。
 * </p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class LocalStorageProvider implements StorageProvider {

    private final StorageProperties storageProperties;

    private Path basePath;

    @PostConstruct
    public void init() {
        StorageProperties.ProviderConfig config = storageProperties.getProviders().get("local");
        if (config != null && config.getBasePath() != null) {
            basePath = Paths.get(config.getBasePath()).normalize();
        } else {
            basePath = Paths.get("./uploads").normalize();
        }
        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地存储基目录: " + basePath, e);
        }
        log.info("本地存储提供商初始化完成，基路径: {}", basePath.toAbsolutePath());
    }

    @Override
    public StorageUploadResult upload(InputStream inputStream, String storageName, String contentType) {
        // 按日期分目录
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String ext = extractExtension(storageName);
        String uuid = UUID.randomUUID().toString();
        String fileName = uuid + (ext != null ? "." + ext : "");

        Path targetDir = basePath.resolve(dateDir);
        Path targetFile = targetDir.resolve(fileName);

        try {
            Files.createDirectories(targetDir);
            Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);

            String storageKey = dateDir + "/" + fileName;
            long fileSize = Files.size(targetFile);

            log.debug("本地文件上传成功: key={}, size={}, path={}", storageKey, fileSize, targetFile.toAbsolutePath());

            return StorageUploadResult.builder()
                    .storageKey(storageKey)
                    .storageName(fileName)
                    .storageUrl(getUrl(storageKey))
                    .fileSize(fileSize)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("本地文件上传失败: " + storageName, e);
        }
    }

    @Override
    public InputStream download(String storageKey) {
        Path filePath = basePath.resolve(storageKey).normalize();
        // 确保路径不超出 basePath（路径穿越防护）
        if (!filePath.startsWith(basePath)) {
            throw new SecurityException("非法的文件路径: " + storageKey);
        }
        try {
            return Files.newInputStream(filePath);
        } catch (IOException e) {
            throw new RuntimeException("本地文件下载失败: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path filePath = basePath.resolve(storageKey).normalize();
        if (!filePath.startsWith(basePath)) {
            throw new SecurityException("非法的文件路径: " + storageKey);
        }
        try {
            Files.deleteIfExists(filePath);
            log.debug("本地文件删除成功: key={}", storageKey);
        } catch (IOException e) {
            log.warn("本地文件删除失败: key={}", storageKey, e);
        }
    }

    @Override
    public String getUrl(String storageKey) {
        StorageProperties.ProviderConfig config = storageProperties.getProviders().get("local");
        String urlPrefix = (config != null && config.getUrlPrefix() != null)
                ? config.getUrlPrefix()
                : "/files";
        return urlPrefix + "/" + storageKey;
    }

    @Override
    public String getType() {
        return "local";
    }

    /**
     * 从文件名中提取扩展名（小写，不含点）。
     */
    private String extractExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dotIndex + 1).toLowerCase();
    }
}

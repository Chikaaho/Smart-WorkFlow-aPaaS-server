package com.sw.ck.storage.provider;

import com.sw.ck.storage.api.StorageUploadResult;
import com.sw.ck.storage.config.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * MinIO 文件存储提供商。
 * <p>
 * 使用 MinIO SDK 8.x Builder API，支持懒加载 MinioClient。
 * </p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class MinioStorageProvider implements StorageProvider {

    private final StorageProperties storageProperties;

    private volatile MinioClient minioClient;
    private String bucket;

    @PostConstruct
    public void init() {
        StorageProperties.ProviderConfig config = storageProperties.getProviders().get("minio");
        if (config != null) {
            bucket = config.getBucket() != null ? config.getBucket() : "smart-workflow";
        } else {
            bucket = "smart-workflow";
        }
        // 初次初始化时检查/创建 bucket
        try {
            MinioClient client = getClient();
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket 已自动创建: {}", bucket);
            }
            log.info("MinIO 存储提供商初始化完成，bucket: {}", bucket);
        } catch (Exception e) {
            log.warn("MinIO 初始化时检查 bucket 失败，将在首次上传时重试: {}", e.getMessage());
        }
    }

    @Override
    public StorageUploadResult upload(InputStream inputStream, String storageName, String contentType) {
        try {
            MinioClient client = getClient();
            String storageKey = storageName;

            PutObjectArgs args = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .stream(inputStream, -1, 10485760)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build();
            client.putObject(args);

            StatObjectArgs statArgs = StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .build();
            long fileSize = client.statObject(statArgs).size();

            log.debug("MinIO 文件上传成功: key={}, bucket={}", storageKey, bucket);

            return StorageUploadResult.builder()
                    .storageKey(storageKey)
                    .storageName(storageName)
                    .storageUrl(getUrl(storageKey))
                    .fileSize(fileSize)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("MinIO 文件上传失败: " + storageName, e);
        }
    }

    @Override
    public InputStream download(String storageKey) {
        try {
            MinioClient client = getClient();
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("MinIO 文件下载失败: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            MinioClient client = getClient();
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .build());
            log.debug("MinIO 文件删除成功: key={}, bucket={}", storageKey, bucket);
        } catch (Exception e) {
            log.warn("MinIO 文件删除失败: key={}", storageKey, e);
        }
    }

    @Override
    public String getUrl(String storageKey) {
        StorageProperties.ProviderConfig config = storageProperties.getProviders().get("minio");
        String endpoint = config != null && config.getUrl() != null ? config.getUrl() : "http://localhost:9000";
        return endpoint + "/" + bucket + "/" + storageKey;
    }

    @Override
    public String getType() {
        return "minio";
    }

    /**
     * 懒加载 MinioClient（双重检查锁定）。
     */
    private MinioClient getClient() {
        if (minioClient == null) {
            synchronized (this) {
                if (minioClient == null) {
                    StorageProperties.ProviderConfig config = storageProperties.getProviders().get("minio");
                    String endpoint = config != null && config.getUrl() != null ? config.getUrl() : "http://localhost:9000";
                    String accessKey = config != null && config.getAccessKey() != null ? config.getAccessKey() : "";
                    String secretKey = config != null && config.getSecretKey() != null ? config.getSecretKey() : "";

                    minioClient = MinioClient.builder()
                            .endpoint(endpoint)
                            .credentials(accessKey, secretKey)
                            .build();
                    log.info("MinIO 客户端已创建，endpoint: {}", endpoint);
                }
            }
        }
        return minioClient;
    }
}

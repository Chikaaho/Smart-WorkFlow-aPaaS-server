package com.sw.ck.storage.provider;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.region.Region;
import com.sw.ck.storage.api.StorageUploadResult;
import com.sw.ck.storage.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URL;
import java.util.Date;

/**
 * 腾讯云 COS 文件存储提供商。
 * <p>
 * 使用 COS SDK 5.x (com.qcloud.cos)，支持预签名 URL。
 * </p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class CosStorageProvider implements StorageProvider {

    private final StorageProperties storageProperties;

    private volatile COSClient cosClient;
    private String bucket;
    private String regionName;
    private String secretId;
    private String secretKey;

    @PostConstruct
    public void init() {
        StorageProperties.ProviderConfig config = storageProperties.getProviders().get("cos");
        if (config != null) {
            bucket = config.getBucket() != null ? config.getBucket() : "smart-workflow-1250000000";
            regionName = config.getRegion() != null ? config.getRegion() : "ap-guangzhou";
            secretId = config.getAccessKey() != null ? config.getAccessKey() : "";
            secretKey = config.getSecretKey() != null ? config.getSecretKey() : "";
        } else {
            bucket = "smart-workflow-1250000000";
            regionName = "ap-guangzhou";
            secretId = "";
            secretKey = "";
        }
        log.info("COS 存储提供商初始化完成，bucket: {}, region: {}", bucket, regionName);
    }

    @Override
    public StorageUploadResult upload(InputStream inputStream, String storageName, String contentType) {
        try {
            COSClient client = getClient();
            String storageKey = storageName;

            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentType(contentType != null ? contentType : "application/octet-stream");
            // 使用 -1 表示未知长度，SDK 会自行缓冲
            meta.setContentLength(-1);

            PutObjectRequest putReq = new PutObjectRequest(bucket, storageKey, inputStream, meta);
            PutObjectResult putResult = client.putObject(putReq);

            // 上传后获取文件元数据得到实际大小
            ObjectMetadata resultMeta = client.getObjectMetadata(bucket, storageKey);
            long fileSize = resultMeta.getContentLength();

            log.debug("COS 文件上传成功: key={}, bucket={}, etag={}", storageKey, bucket, putResult.getETag());

            return StorageUploadResult.builder()
                    .storageKey(storageKey)
                    .storageName(storageName)
                    .storageUrl(getUrl(storageKey))
                    .fileSize(fileSize)
                    .build();
        } catch (CosClientException e) {
            throw new RuntimeException("COS 文件上传失败: " + storageName, e);
        }
    }

    @Override
    public InputStream download(String storageKey) {
        try {
            COSClient client = getClient();
            GetObjectRequest getReq = new GetObjectRequest(bucket, storageKey);
            COSObject cosObject = client.getObject(getReq);
            return cosObject.getObjectContent();
        } catch (CosClientException e) {
            throw new RuntimeException("COS 文件下载失败: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            COSClient client = getClient();
            client.deleteObject(bucket, storageKey);
            log.debug("COS 文件删除成功: key={}, bucket={}", storageKey, bucket);
        } catch (CosClientException e) {
            log.warn("COS 文件删除失败: key={}", storageKey, e);
        }
    }

    @Override
    public String getUrl(String storageKey) {
        try {
            COSClient client = getClient();
            // 生成预签名 URL，有效期 1 小时
            Date expiration = new Date(System.currentTimeMillis() + 3600_000);
            URL url = client.generatePresignedUrl(bucket, storageKey, expiration);
            return url.toString();
        } catch (Exception e) {
            // 降级：返回静态 URL（可能无法直接访问，但可用于调试）
            log.warn("COS 预签名 URL 生成失败，回退到静态 URL", e);
            return "https://" + bucket + ".cos." + regionName + ".myqcloud.com/" + storageKey;
        }
    }

    @Override
    public String getType() {
        return "cos";
    }

    /**
     * 懒加载 COSClient（双重检查锁定）。
     */
    private COSClient getClient() {
        if (cosClient == null) {
            synchronized (this) {
                if (cosClient == null) {
                    BasicCOSCredentials credentials = new BasicCOSCredentials(secretId, secretKey);
                    ClientConfig clientConfig = new ClientConfig(new Region(regionName));
                    cosClient = new COSClient(credentials, clientConfig);
                    log.info("COS 客户端已创建，region: {}", regionName);
                }
            }
        }
        return cosClient;
    }
}

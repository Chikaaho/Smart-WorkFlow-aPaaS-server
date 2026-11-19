package com.sw.ck.storage.provider;

import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.DefaultPutRet;
import com.qiniu.util.Auth;
import com.sw.ck.storage.api.StorageUploadResult;
import com.sw.ck.storage.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * 七牛云文件存储提供商。
 * <p>
 * 使用七牛云 SDK 7.x (com.qiniu)，支持 UploadManager + Auth + BucketManager。
 * </p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class QiniuStorageProvider implements StorageProvider {

    private final StorageProperties storageProperties;

    private volatile Auth auth;
    private volatile UploadManager uploadManager;
    private volatile BucketManager bucketManager;
    private String bucket;
    private String domain;
    private String accessKey;
    private String secretKey;

    @PostConstruct
    public void init() {
        StorageProperties.ProviderConfig config = storageProperties.getProviders().get("qiniu");
        if (config != null) {
            bucket = config.getBucket() != null ? config.getBucket() : "smart-workflow";
            domain = config.getDomain() != null ? config.getDomain() : "http://cdn.example.com";
            accessKey = config.getAccessKey() != null ? config.getAccessKey() : "";
            secretKey = config.getSecretKey() != null ? config.getSecretKey() : "";
        } else {
            bucket = "smart-workflow";
            domain = "http://cdn.example.com";
            accessKey = "";
            secretKey = "";
        }
        log.info("七牛云存储提供商初始化完成，bucket: {}, domain: {}", bucket, domain);
    }

    @Override
    public StorageUploadResult upload(InputStream inputStream, String storageName, String contentType) {
        try {
            String storageKey = storageName;
            Auth authInstance = getAuth();
            String uploadToken = authInstance.uploadToken(bucket);

            // 使用 byte[] 上传（从 InputStream 读取），兼容 Qiniu SDK 7.x API
            byte[] data = inputStream.readAllBytes();
            Response response = getUploadManager().put(data, storageKey, uploadToken);
            DefaultPutRet putRet = response.jsonToObject(DefaultPutRet.class);

            log.debug("七牛云文件上传成功: key={}, bucket={}, hash={}", storageKey, bucket, putRet.hash);

            return StorageUploadResult.builder()
                    .storageKey(storageKey)
                    .storageName(storageName)
                    .storageUrl(getUrl(storageKey))
                    .fileSize((long) data.length)
                    .build();
        } catch (QiniuException e) {
            log.error("七牛云文件上传失败: {}, code={}", storageName, e.code(), e);
            throw new RuntimeException("七牛云文件上传失败: " + storageName, e);
        } catch (Exception e) {
            throw new RuntimeException("七牛云文件上传失败: " + storageName, e);
        }
    }

    @Override
    public InputStream download(String storageKey) {
        // 七牛云 SDK 无直接下载 API；文件通过公开 URL 或私有签名 URL 直接 HTTP 访问
        // 此处通过 BucketManager 获取文件信息后返回 HTTP 流
        // 实际场景建议直接使用 getUrl() 返回的 URL 进行 HTTP 下载
        throw new UnsupportedOperationException(
                "七牛云文件下载请直接使用 getUrl() 获取的 URL 通过 HTTP 下载。storageKey: " + storageKey);
    }

    @Override
    public void delete(String storageKey) {
        try {
            getBucketManager().delete(bucket, storageKey);
            log.debug("七牛云文件删除成功: key={}, bucket={}", storageKey, bucket);
        } catch (QiniuException e) {
            log.warn("七牛云文件删除失败: key={}, code={}", storageKey, e.code(), e);
        }
    }

    @Override
    public String getUrl(String storageKey) {
        if (accessKey.isEmpty() && secretKey.isEmpty()) {
            // 无密钥时返回公开 URL
            return domain + "/" + storageKey;
        }
        // 私有空间：生成签名下载 URL（1 小时有效期）
        Auth authInstance = getAuth();
        return authInstance.privateDownloadUrl(domain + "/" + storageKey, 3600);
    }

    @Override
    public String getType() {
        return "qiniu";
    }

    private Auth getAuth() {
        if (auth == null) {
            synchronized (this) {
                if (auth == null) {
                    auth = Auth.create(accessKey, secretKey);
                }
            }
        }
        return auth;
    }

    private UploadManager getUploadManager() {
        if (uploadManager == null) {
            synchronized (this) {
                if (uploadManager == null) {
                    Configuration cfg = new Configuration(Region.autoRegion());
                    uploadManager = new UploadManager(cfg);
                }
            }
        }
        return uploadManager;
    }

    private BucketManager getBucketManager() {
        if (bucketManager == null) {
            synchronized (this) {
                if (bucketManager == null) {
                    Configuration cfg = new Configuration(Region.autoRegion());
                    bucketManager = new BucketManager(getAuth(), cfg);
                }
            }
        }
        return bucketManager;
    }
}

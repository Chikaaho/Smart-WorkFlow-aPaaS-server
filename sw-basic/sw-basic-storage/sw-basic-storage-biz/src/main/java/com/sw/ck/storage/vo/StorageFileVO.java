package com.sw.ck.storage.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.storage.entity.StorageFile;

/**
 * StorageFile 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class StorageFileVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String originalName;
    private String storageKey;
    private String storageName;
    private Long fileSize;
    private String contentType;
    private String fileExt;
    private String providerType;
    private String bucketName;
    private String storageUrl;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static StorageFileVO from(StorageFile source) {
        if (source == null) {
            return null;
        }
        StorageFileVO target = new StorageFileVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}

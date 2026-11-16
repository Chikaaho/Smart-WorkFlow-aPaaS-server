package com.sw.ck.bpm.process.dto;

import lombok.Data;

/**
 * 附件上传结果：storageKey 供表单字段关联，另回显名称与大小。
 * <p>
 * {@code fileSize} 保持 {@link Long}，沿用全局 Long→String 序列化口径。
 * </p>
 */
@Data
public class AttachmentUploadResultDTO {

    private String storageKey;

    private String storageName;

    private Long fileSize;
}

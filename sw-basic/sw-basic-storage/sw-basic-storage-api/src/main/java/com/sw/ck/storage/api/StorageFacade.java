package com.sw.ck.storage.api;

import java.io.InputStream;

/**
 * 文件存储 Facade 接口。
 * <p>
 * 供其他模块（form/bpm/notify/knowledge）通过 Facade 模式调用文件存储能力。
 * 定义于 {@code -api} 模块，实现于 {@code -biz} 模块。
 * </p>
 */
public interface StorageFacade {

    /**
     * 上传文件。
     *
     * @param inputStream  文件输入流
     * @param originalName 原始文件名
     * @param contentType  文件 MIME 类型
     * @return 上传结果
     */
    StorageUploadResult upload(InputStream inputStream, String originalName, String contentType);

    /**
     * 下载文件。
     * <p>
     * 按照文件上传时记录的 {@code providerType} 选择对应提供商进行下载，
     * 而非当前活跃提供商，确保提供商切换后历史文件仍可访问。
     * </p>
     *
     * @param storageKey 存储唯一标识
     * @return 文件输入流
     */
    InputStream download(String storageKey);

    /**
     * 删除文件（软删除文件记录 + 提供商侧文件删除）。
     *
     * @param storageKey 存储唯一标识
     */
    void delete(String storageKey);

    /**
     * 获取文件访问 URL（由提供商重新生成，确保预签名 URL 在有效期内）。
     *
     * @param storageKey 存储唯一标识
     * @return 文件访问 URL
     */
    String getUrl(String storageKey);

    /**
     * 判断存储文件是否存在（未逻辑删除）。
     * <p>供表单等业务模块校验附件/图片引用的真实性（I2 §4.1 对象校验）。</p>
     *
     * @param storageKey 存储唯一标识
     * @return true = 存在
     */
    boolean exists(String storageKey);
}

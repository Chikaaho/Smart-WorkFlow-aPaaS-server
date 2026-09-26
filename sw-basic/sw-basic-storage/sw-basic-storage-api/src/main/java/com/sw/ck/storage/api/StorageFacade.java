package com.sw.ck.storage.api;

import java.io.InputStream;
import java.util.Optional;

/**
 * 文件存储 Facade 接口。
 * <p>
 * 供其他模块（form/bpm/notify/knowledge）通过 Facade 模式调用文件存储能力。
 * 定义于 {@code -api} 模块，实现于 {@code -biz} 模块。
 * </p>
 * <p>
 * 模块内部调用边界统一以非空 {@link Optional} 表达结果：empty 只表达
 * "查询目标/上下文不存在"，基础设施与提供商失败继续抛明确异常。
 * </p>
 */
public interface StorageFacade {

    /**
     * 上传文件。
     *
     * @param inputStream  文件输入流
     * @param originalName 原始文件名
     * @param contentType  文件 MIME 类型
     * @return present = 上传结果；当前契约恒 present（上传失败抛异常，不以上空表达失败）
     */
    Optional<StorageUploadResult> upload(InputStream inputStream, String originalName, String contentType);

    /**
     * 下载文件。
     * <p>
     * 按照文件上传时记录的 {@code providerType} 选择对应提供商进行下载，
     * 而非当前活跃提供商，确保提供商切换后历史文件仍可访问。
     * </p>
     *
     * @param storageKey 存储唯一标识
     * @return present = 文件输入流；empty = 该 storageKey 无存储记录或已逻辑删除；
     *         提供商不可用等基础设施失败继续抛异常
     */
    Optional<InputStream> download(String storageKey);

    /**
     * 删除文件（软删除文件记录 + 提供商侧文件删除）。
     *
     * @param storageKey 存储唯一标识
     * @return present = {@link StorageMutationOutcome#APPLIED} 已删除 /
     *         {@link StorageMutationOutcome#ALREADY_APPLIED} 记录本就不存在或已删除（合法幂等）；
     *         当前契约恒 present，目标缺失不再是异常
     */
    Optional<StorageMutationOutcome> delete(String storageKey);

    /**
     * 判断存储文件是否存在（未逻辑删除）。
     * <p>供表单等业务模块校验附件/图片引用的真实性（I2 §4.1 对象校验）。</p>
     *
     * @param storageKey 存储唯一标识
     * @return present = true 存在 / false 不存在（含 storageKey 为空的情况，判定明确有效）；
     *         当前契约恒 present
     */
    Optional<Boolean> exists(String storageKey);
}

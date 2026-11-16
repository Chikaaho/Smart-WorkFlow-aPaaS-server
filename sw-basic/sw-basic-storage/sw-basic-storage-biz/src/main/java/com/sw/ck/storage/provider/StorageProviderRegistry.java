package com.sw.ck.storage.provider;

import com.sw.ck.storage.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 存储提供商注册表。
 * <p>
 * 收集所有 StorageProvider 实现，提供按类型查询和获取当前活跃提供商的能力。
 * </p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class StorageProviderRegistry {

    private final List<StorageProvider> providers;
    private final StorageProperties storageProperties;

    private Map<String, StorageProvider> providerMap;

    @PostConstruct
    public void init() {
        providerMap = providers.stream()
                .collect(Collectors.toMap(
                        StorageProvider::getType,
                        Function.identity(),
                        (existing, replacement) -> {
                            log.warn("检测到重复的存储提供商类型 '{}'，保留后注册的实例", existing.getType());
                            return replacement;
                        }
                ));
        log.info("存储提供商注册表初始化完成，已注册类型: {}", getAvailableTypes());
    }

    /**
     * 获取当前活跃的存储提供商。
     * <p>
     * 活跃提供商由配置 {@code sw.storage.active-provider} 指定，默认为 local。
     * </p>
     *
     * @return 活跃的 StorageProvider
     * @throws IllegalStateException 如果活跃提供商未注册
     */
    public StorageProvider getActiveProvider() {
        String activeType = storageProperties.getActiveProvider();
        StorageProvider provider = providerMap.get(activeType);
        if (provider == null) {
            throw new IllegalStateException(
                    "未找到活跃的存储提供商 '" + activeType + "'，已注册类型: " + getAvailableTypes());
        }
        return provider;
    }

    /**
     * 根据类型标识获取对应的存储提供商。
     *
     * @param type 提供商类型（local / minio / cos / qiniu）
     * @return StorageProvider，不存在时返回 null
     */
    public StorageProvider getProvider(String type) {
        return providerMap.get(type);
    }

    /**
     * 获取所有已注册的存储提供商类型。
     *
     * @return 不可修改的类型列表
     */
    public List<String> getAvailableTypes() {
        return Collections.unmodifiableList(
                providers.stream().map(StorageProvider::getType).collect(Collectors.toList()));
    }
}

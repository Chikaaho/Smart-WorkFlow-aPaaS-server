package com.sw.ck.bootstrap.config;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.exception.ErrorKeyRegistry;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.openapi.api.exception.OpenApiErrorCode;
import com.sw.ck.system.security.AuthErrorCode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * 把跨模块的 {@link com.sw.ck.common.exception.ErrorCode} 枚举注册进
 * {@link ErrorKeyRegistry}，使 {@code R.fail(code, msg)} 形式的业务失败也能携带 errorKey。
 *
 * <p>注册源与 {@code ErrorCodeCatalogTest} 聚合的枚举集合保持同一份：新增模块错误码时，
 * 测试与运行期注册同步覆盖，不会出现「目录里有、运行期查不到」。</p>
 */
@Configuration
public class ErrorKeyRegistryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ErrorKeyRegistryConfiguration.class);

    @PostConstruct
    public void registerErrorCodes() {
        ErrorKeyRegistry.register(CommonErrorCode.values());
        ErrorKeyRegistry.register(AuthErrorCode.values());
        ErrorKeyRegistry.register(FormErrorCode.values());
        ErrorKeyRegistry.register(BpmErrorCode.values());
        ErrorKeyRegistry.register(OpenApiErrorCode.values());
        log.info("P61 errorKey 注册表就绪：已登记数值码 {} 个，冲突码 {} 个（冲突码不猜测含义，查表返回 null）",
                ErrorKeyRegistry.size(), ErrorKeyRegistry.ambiguousCodes().size());
    }
}

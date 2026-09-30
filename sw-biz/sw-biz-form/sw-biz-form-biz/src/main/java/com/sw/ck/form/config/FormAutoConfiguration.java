package com.sw.ck.form.config;

import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.form.entity.FormBaseEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 低代码表单自动配置。
 * <p>
 * 默认关闭，通过 sw.form.enabled=true 开启。
 * workflow 模块依赖此配置的顺序保证（after = FormAutoConfiguration.class）。
 * </p>
 */
@AutoConfiguration
@EnableAsync
@EnableScheduling
@ConditionalOnProperty(prefix = "sw.form", name = "enabled", havingValue = "true")
@MapperScan({"com.sw.ck.form.mapper", "com.sw.ck.form.txn.mapper"})
public class FormAutoConfiguration {

    /**
     * 向全局 {@link CommonMetaObjectHandler} 注册表单 ID 自动填充逻辑。
     * <p>
     * 仅当插入对象是 {@link FormBaseEntity} 且 id 为 null 时，用 {@link FormIdGenerator} 生成 UUID 填充。
     * 保留 Service 层显式 {@code entity.setId(idGenerator.generate())} 调用的兼容性（已设 id 的不覆盖）。
     * </p>
     */
    @Bean
    String registerFormIdFiller(CommonMetaObjectHandler handler, FormIdGenerator idGenerator) {
        handler.setFormIdFiller(meta -> {
            Object original = meta.getOriginalObject();
            if (original instanceof FormBaseEntity f && f.getId() == null) {
                f.setId(idGenerator.generate());
            }
        });
        return "formIdFiller-registered";
    }
}

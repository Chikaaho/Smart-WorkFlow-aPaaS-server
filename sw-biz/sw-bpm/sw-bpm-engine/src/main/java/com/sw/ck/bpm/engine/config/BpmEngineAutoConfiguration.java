package com.sw.ck.bpm.engine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.sw.ck.bpm.api.node.BpmNodeRegistry;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverResolver;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverType;
import com.sw.ck.bpm.engine.datasource.ExternalDatasourceManager;
import com.sw.ck.bpm.engine.executor.SqlExecutor;
import com.sw.ck.bpm.engine.registry.BpmNodeRegistryImpl;
import com.sw.ck.bpm.engine.service.ExternalDatasourceService;
import com.sw.ck.bpm.engine.service.SqlExecutionAuditService;
import com.sw.ck.bpm.engine.translator.GraphToBpmnTranslator;
import com.sw.ck.bpm.engine.translator.NodeTypeTranslator;
import com.sw.ck.common.crypto.AesGcmCipher;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BPM 引擎自动配置（Flowable + 外部数据源执行引擎）。
 * <p>
 * 默认关闭，通过 sw.bpm.enabled=true 开启。
 * 运行时强制要求 sw.form.enabled=true，否则启动失败。
 * 本配置通过 AutoConfiguration.imports 注册；关闭 sw.bpm.enabled 时不参与运行时装配。
 * </p>
 */
@AutoConfiguration
@AutoConfigureAfter(name = "com.sw.ck.form.config.FormAutoConfiguration")
@ConditionalOnProperty(prefix = "sw.bpm", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({BpmProperties.class, ExternalDatasourceProperties.class})
public class BpmEngineAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BpmEngineAutoConfiguration.class);

    public BpmEngineAutoConfiguration(Environment environment) {
        String formEnabled = environment.getProperty("sw.form.enabled");
        if (!"true".equals(formEnabled)) {
            throw new IllegalStateException(
                    "BPM(sw.bpm.enabled=true)必须配合低代码表单使用，请同时设置 sw.form.enabled=true");
        }
        log.info("BPM engine auto-configuration started (Flowable process engine)");
    }

    /**
     * AES-256-GCM 密码加密器，密钥从 {@link ExternalDatasourceProperties#getCipherKey()} 注入。
     */
    @Bean
    @ConditionalOnMissingBean
    public AesGcmCipher aesGcmCipher(ExternalDatasourceProperties properties) {
        return new AesGcmCipher(properties.getCipherKey());
    }

    /**
     * 外部数据源连接池管理器（独立于主库 dynamic-datasource）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ExternalDatasourceManager externalDatasourceManager(AesGcmCipher cipher,
                                                                ExternalDatasourceProperties properties) {
        return new ExternalDatasourceManager(cipher, properties);
    }

    /**
     * SQL 执行引擎（独立 JDBC 通道，不复用主库 MP/SqlSessionFactory 拦截器）。
     */
    @Bean
    @ConditionalOnMissingBean
    public SqlExecutor sqlExecutor(ExternalDatasourceService datasourceService,
                                   ExternalDatasourceManager poolManager,
                                   SqlExecutionAuditService auditService,
                                   ExternalDatasourceProperties properties) {
        return new SqlExecutor(datasourceService, poolManager, auditService, properties);
    }

    /**
     * 当前应用的唯一 BPM 节点注册结果。节点实现由 Spring 自动发现，注册构造阶段完成
     * 类型、元数据、配置和能力完整性校验；失败直接阻止 BPM 引擎装配。
     */
    @Bean
    @ConditionalOnMissingBean
    public BpmNodeRegistry bpmNodeRegistry(List<NodeTypeTranslator> translators) {
        return new BpmNodeRegistryImpl(translators);
    }

    /**
     * 发布翻译统一消费注册结果，禁止发布链自行 new 出第二套节点分发表。
     */
    @Bean
    @ConditionalOnMissingBean
    public GraphToBpmnTranslator graphToBpmnTranslator(ObjectMapper objectMapper,
                                                        BpmNodeRegistry nodeRegistry) {
        return new GraphToBpmnTranslator(objectMapper, nodeRegistry);
    }

    /**
     * 审批人解析器注册表 —— 按 ApproverType 字符串 key 分发。
     * <p>
     * DESIGNATED → {@link com.sw.ck.bpm.engine.resolver.DesignatedApproverResolver}
     * <br>
     * SCRIPT → {@link com.sw.ck.bpm.engine.resolver.UnsupportedApproverResolver}（桩）
     * </p>
     * 禁 switch 分发，禁 FQCN 选实现。
     */
    @Bean
    public Map<String, NodeApproverResolver> approverResolverMap(
            List<NodeApproverResolver> resolvers) {
        Map<String, NodeApproverResolver> map = new HashMap<>();
        for (NodeApproverResolver resolver : resolvers) {
            if (resolver instanceof com.sw.ck.bpm.engine.resolver.DesignatedApproverResolver) {
                map.put(NodeApproverType.DESIGNATED, resolver);
            } else if (resolver instanceof com.sw.ck.bpm.engine.resolver.UnsupportedApproverResolver) {
                map.put(NodeApproverType.SCRIPT, resolver);
            }
        }
        log.info("ApproverResolverMap initialized with {} entries: {}",
                map.size(), map.keySet());
        return map;
    }

    /**
     * Flowable 引擎绑定主库 master DataSource。
     * <p>
     * 当 dynamic-datasource 启用时（主应用），从 {@link DynamicRoutingDataSource} 提取物理 master
     * DataSource 注入 Flowable，避免 {@code @DS} 线程上下文污染引擎连接。
     * 当 dynamic-datasource 不存在时（如单元测试使用独立 H2），保持 Flowable 默认 DataSource 不变。
     * </p>
     */
    @Bean
    public ProcessEngineConfigurationConfigurer masterDataSourceBinding(
            ObjectProvider<DataSource> dataSourceProvider) {
        return config -> {
            DataSource ds = dataSourceProvider.getIfUnique();
            if (ds instanceof DynamicRoutingDataSource drds) {
                DataSource masterDs = drds.getDataSource("master");
                if (masterDs != null) {
                    config.setDataSource(masterDs);
                    log.info("Flowable engine bound to master DataSource (extracted from DynamicRoutingDataSource)");
                }
            } else {
                log.info("Flowable engine using application DataSource directly: {}",
                        ds != null ? ds.getClass().getSimpleName() : "null");
            }
        };
    }
}

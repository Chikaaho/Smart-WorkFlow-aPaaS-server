package com.sw.ck.knowledge.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * pgvector 向量列补建（D022-07，F1S3-G3）。
 * <p>
 * 覆盖“先升级、后装扩展”的部署顺序：升级时扩展不可用 → V0.2.5 只建表；
 * 运维安装扩展并重启应用后，本初始化器检测到扩展存在而 embedding 列缺失，
 * 自动执行 {@code ALTER TABLE ... ADD COLUMN embedding vector}。
 * 扩展仍不可用时只记录明确警告，不阻断启动；首次向量写入/检索会给出可读错误，
 * 不做静默降级。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "sw.knowledge", name = "vector-store", havingValue = "pgvector", matchIfMissing = true)
public class KnowledgeVectorColumnInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorColumnInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeVectorColumnInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = 'sw_knowledge_chunk'",
                Integer.class);
        if (tableCount == null || tableCount == 0) {
            return;
        }
        Integer extensionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
        if (extensionCount == null || extensionCount == 0) {
            log.warn("pgvector 扩展未安装：知识库可建表但无法写入/检索向量；"
                    + "安装扩展并重启后自动补建 sw_knowledge_chunk.embedding 列");
            return;
        }
        Integer columnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'sw_knowledge_chunk' "
                        + "AND column_name = 'embedding'",
                Integer.class);
        if (columnCount == null || columnCount == 0) {
            jdbcTemplate.execute("ALTER TABLE sw_knowledge_chunk ADD COLUMN embedding vector");
            log.info("pgvector 已安装且 embedding 列缺失，已自动补建 sw_knowledge_chunk.embedding");
        }
    }
}

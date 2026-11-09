# KingbaseES V8 Migration Scripts

本目录由 `postgresql/` 复制，使用 `SwVendorMigrationRunner`（Flyway 无 Kingbase 模块）。
0.4.0 发布口径：**由 Owner 在真实 KingbaseES V8 上验证**；实库验证暂跳过。

## 与 PostgreSQL 脚本的差异

- 仅 `V0.2.5__f1_stage3_knowledge_rag.sql`：
  - 移除 pgvector 的 `DO` 块与 `sw_knowledge_chunk.embedding` 列；
  - 向量由 `V0.4.0` 的 `sw_knowledge_vector` 文本表持久化（`sw.knowledge.vector-store=database`）。
- 其余 29 个 `.sql` 与 PostgreSQL 逐字节一致（含 30 条脚本的版本/重命名身份）。

## 建议初始化

- 数据库编码 UTF-8；启动检查会读取 `server_encoding` 并要求 `UTF8`。
- **必须按 PostgreSQL 兼容模式初始化**（`database_mode=pg`，等价参数可能是
  `sql_compatibility`/`compatibility_mode`，随 KingbaseES 版本不同）。
  Oracle 模式下 `''` 等于 NULL，会破坏 PG 脚本的空串语义。
- `DatabaseCompatibilityGuard` 会依次尝试 `SHOW database_mode`、`SHOW sql_compatibility`、
  `SHOW compatibility_mode`；识别到值必须为 `pg`/`postgresql`，否则拒绝启动。
  若三个参数名都读不到，启动只记录告警，Owner 验证时必须人工确认实际参数名与值。
- 大小写/排序规则按 KingbaseES 默认可与 PG 对齐。
- JDBC URL 形如 `jdbc:kingbase8://host:54321/db`，驱动从官方安装包取得（未提交仓库）。

## Owner 验证清单

1. 空库启动：`sw_schema_history` 30 条、终点 `0.4.0`。
2. `SeedComparisonTest` 对 PG 基准库两层比对 0 差异。
3. 启动冒烟：MyBatis、中文、JSONB、分页、Flowable、动态宽表建表/读写、主流程业务。
4. 动态宽表 `DDL` 使用双引号标识符；确认 Kingbase 默认 `standard_conforming_strings` 与引用行为。
5. 可空唯一键与 `uk_sw_bpm_binding_active` 部分索引的实际判重语义（脚本保持 PG 原生定义）。
6. 确认 `database_mode`（或实际参数名）为 `pg`，并记录到验证回执。

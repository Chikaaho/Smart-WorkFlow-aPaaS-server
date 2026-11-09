# KingbaseES V8 与 PostgreSQL 脚本差异报告

- 文件数：30 个同名 `.sql`，一一对应。
- 29 个文件与 PostgreSQL 逐字节一致。
- 唯一差异 `V0.2.5__f1_stage3_knowledge_rag.sql`：
  - 删除 pgvector 的 `DO` 块（`CREATE EXTENSION vector`、`ADD COLUMN embedding vector`）。
  - 向量改由 `V0.4.0` 的 `sw_knowledge_vector` 文本表持久化。
- 可执行校验：`KingbasePostgresParityTest` 逐文件断言，并在全量门禁中运行。
- 实库验证由 Owner 按同目录 `README.md` 清单执行。

# SQL Server 2019/2022 Migration Scripts

SQL Server 首次支持（0.4.0）没有历史版本升级路径，采用**终态基线**：

- `V0.2.0__baseline_seed.sql`：由 PostgreSQL 0.4.0 终态 schema 生成的全部表、主键与索引。
- `R__0.2.0_sqlserver_seed.sql`：同一终态数据库的种子数据。
- 其余 `V0.2.2`—`V0.4.0` 与 19 个 `R__` 为占位脚本（`SELECT 1`），保持 30 条迁移身份。
- 生成工具：`scripts/db-compat/generate_sqlserver.py`，输入为 PG 18 `seed_base` 的
  `pg_dump --schema-only` 与 `--data-only --inserts --column-inserts`。

## 类型与语义

| PostgreSQL | SQL Server |
|---|---|
| `bigint/integer/smallint` | `BIGINT/INT/SMALLINT` |
| `character varying(n)` | `NVARCHAR(n)`（n>4000 用 `NVARCHAR(MAX)`） |
| `text/jsonb` | `NVARCHAR(MAX)` |
| `boolean` | `BIT` |
| `timestamp without time zone` | `DATETIME2` |
| `numeric(p,s)` | `DECIMAL(p,s)` |

- 所有对象以小写 `[dbo].[name]` / `[column]` 创建，数据库排序规则必须 `Chinese_PRC_CS_AS`。
- 可空唯一键改为筛选唯一索引，条件为该键全部可空列 `IS NOT NULL`；
  共 12 个，见 `V0.2.0` 中 `WHERE ... IS NOT NULL` 与 `uk_sw_bpm_binding_active`。
- `flyway_schema_history` 由 Flyway 自建，基线不包含。

## 验证

- 容器：`mcr.microsoft.com/mssql/server:2022-latest`，`MSSQL_COLLATION=Chinese_PRC_CS_AS`。
- `SqlServerSeedMigrationTest`：30 条迁移；`SeedComparisonTest` 对 PG 基准 0 差异。
- `SqlServerBootSmokeTest`：启动、MyBatis、中文、JSON、动态宽表、向量、登录/审批/Quartz。
- `FormKeyConstraintTest` 的 `formkey.sqlserver.url` 断言 `form_key` 唯一索引列顺序。

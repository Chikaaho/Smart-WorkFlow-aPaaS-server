# 多数据库部署前提（0.4.0）

P66 支持矩阵：H2（测试/开发代理）、PostgreSQL、MySQL 5.7/8.0、MariaDB 10.6、
Oracle 21c XE、SQL Server 2019/2022、openGauss 5.0、DM8、KingbaseES V8。
DM8 与 KingbaseES 由 Owner 实库验证；其余库已在本机容器完成迁移、种子、启动与业务冒烟。

## 1. 建库前提

| 库 | 字符集/排序规则/兼容模式 | 其他前提 |
|---|---|---|
| PostgreSQL | UTF8 | UAT/CI 权威版本为 PostgreSQL 17.5；迁移校验和以 UAT 回读为准 |
| MySQL 5.7/8.0 | `utf8mb4` + `utf8mb4_bin` | JDBC URL 必须带 `sessionVariables=explicit_defaults_for_timestamp=ON`；5.7 另需 `innodb_default_row_format=dynamic`、`innodb_large_prefix=ON` |
| MariaDB 10.6 | `utf8mb4` + `utf8mb4_bin` | JDBC URL 必须带 `sessionVariables=explicit_defaults_for_timestamp=ON`（否则 `TIMESTAMP` 隐式 NOT NULL，表结构与 PG 不一致） |
| Oracle 21c XE | `NLS_CHARACTERSET=AL32UTF8`/UTF8 | Oracle 空串=NULL；脚本已按此语义处理可选/必填文本 |
| SQL Server 2019/2022 | `Chinese_PRC_CS_AS` | 必须区分大小写；JDBC 建议 `encrypt=false;trustServerCertificate=true`（内网） |
| openGauss 5.0 | `ENCODING 'UTF8'` + `DBCOMPATIBILITY 'PG'` + `TEMPLATE=template0` | 默认 A 兼容会让空串变 NULL；启动检查强制 `sql_compatibility=PG` |
| DM8 | UTF-8；`CASE_SENSITIVE=Y`、`LENGTH_IN_CHAR=1`、`COMPATIBLE_MODE` 默认 | 由 Owner 验证；脚本与 Oracle 仅差 `VARCHAR2(n CHAR)` 的 CHAR 单位 |
| KingbaseES V8 | UTF-8；`database_mode=pg`（实际参数名随版本） | 由 Owner 验证；Oracle 模式空串=NULL，不兼容本脚本 |

启动时 `DatabaseCompatibilityGuard` 会在迁移前检查上述字符集、排序规则、
`explicit_defaults_for_timestamp`、openGauss 兼容模式与 Kingbase 兼容模式；
不满足直接拒绝启动，避免先按错误前提建表。

## 2. JDBC 示例

```properties
# PostgreSQL
spring.datasource.dynamic.datasource.master.url=jdbc:postgresql://127.0.0.1:5432/smart_workflow
spring.datasource.dynamic.datasource.master.driver-class-name=org.postgresql.Driver

# MySQL 5.7/8.0
...url=jdbc:mysql://127.0.0.1:3306/smart_workflow?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&sessionVariables=explicit_defaults_for_timestamp=ON
...driver-class-name=com.mysql.cj.jdbc.Driver

# MariaDB
...url=jdbc:mariadb://127.0.0.1:3306/smart_workflow?sessionVariables=explicit_defaults_for_timestamp=ON
...driver-class-name=org.mariadb.jdbc.Driver

# Oracle
...url=jdbc:oracle:thin:@//127.0.0.1:1521/XEPDB1
...driver-class-name=oracle.jdbc.OracleDriver

# SQL Server
...url=jdbc:sqlserver://127.0.0.1:1433;databaseName=smart_workflow;encrypt=false;trustServerCertificate=true
...driver-class-name=com.microsoft.sqlserver.jdbc.SQLServerDriver

# openGauss
...url=jdbc:opengauss://127.0.0.1:5432/smart_workflow
...driver-class-name=org.opengauss.Driver

# DM8（驱动由官方安装包提供）
...url=jdbc:dm://127.0.0.1:5236
...driver-class-name=dm.jdbc.driver.DmDriver

# KingbaseES（驱动由官方安装包提供）
...url=jdbc:kingbase8://127.0.0.1:54321/smart_workflow
...driver-class-name=com.kingbase8.Driver
```

## 3. 向量存储

- PostgreSQL 默认 `sw.knowledge.vector-store=pgvector`，使用 `sw_knowledge_chunk.embedding`
  与 pgvector 扩展。
- 其他数据库必须设置 `sw.knowledge.vector-store=database`，使用 `sw_knowledge_vector`
  文本表（`PersistentKnowledgeVectorStore`）。
- `memory` 仅用于测试，不得用于生产。

## 4. SQL Server 终态基线（方案偏离）

SQL Server 首次支持且无 0.3.x 升级路径，`db/migration/sqlserver/` 采用终态基线：

- `V0.2.0__baseline_seed.sql`：PG 0.4.0 终态 schema；
- `R__0.2.0_sqlserver_seed.sql`：终态种子数据；
- 其余 9 个 V 与 19 个 R 为 `SELECT 1` 占位脚本，保持 30 条迁移身份。

**这是对“同一版本序列逐版本增量”的显式偏离**：版本号相同，但占位脚本不代表历史增量。
部署/发布说明必须标注。

维护规则：修改任何 PG `R__` 或种子时，必须同步 SQL Server 增量脚本并更新
`db/migration/sqlserver/pg-repeatable-checksums.properties`；
`SqlServerRepeatableSyncTest` 会在未同步时失败。后续 SQL Server 版本按正常增量迁移。

SQL Server 基线由 `scripts/db-compat/generate_sqlserver.py` 从 PG 18.4 `pg_dump`
（仅迁移链产物，无手工数据）生成；UAT/CI 的 PG 权威版本为 17.5，差异已通过
U1 表结构一致性与 SQL Server 实库冒烟验证。

## 5. 验证责任

- DM8：Owner 按 `db/migration/dm/README.md` 正反例清单验证。
- KingbaseES：Owner 按 `db/migration/kingbase/README.md` 验证，重点确认 `database_mode=pg`。
- 其余库：CI/本机容器矩阵见 `product/multi-database-compat/receipts/execution-final-01.md`。

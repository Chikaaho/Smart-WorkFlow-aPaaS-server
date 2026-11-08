# 保留字清单来源

`check-reserved-words.py` 读取本目录下的清单，按“完整标识符、大小写不敏感”比对
迁移 DDL 与 MyBatis-Plus `@TableName` / `@TableField`。清单只收录各库**不能作为
非引用标识符**的词；非保留关键字不收录（达梦的“关键字”与“系统保留字”、
Kingbase/openGauss/PostgreSQL 的 non-reserved 均在此列）。

| 文件 | 数据库版本 | 官方来源 | 取值口径 |
|---|---|---|---|
| `mysql57.txt` | MySQL 5.7 | https://docs.oracle.com/cd/E17952_01/mysql-5.7-en/keywords.html | 手册中带 `(R)` 的词 |
| `mysql80.txt` | MySQL 8.0 | https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/keywords.html | 手册中带 `(R)` 的词 |
| `mariadb106.txt` | MariaDB 10.6 | https://mariadb.com/kb/en/reserved-words/ | 官方保留字表；排除 `(10.7+)`、`(10.10+)`、`(11.x+)`、`(12.x+)` 等 10.6 之后才保留的词，保留 `(before 12.0)` 等 10.6 时仍保留的词 |
| `oracle21c.txt` | Oracle 21c（19c 语法兼容） | https://docs.oracle.com/en/database/oracle/oracle-database/21/sqlrf/Oracle-SQL-Reserved-Words.html | 官方 Oracle SQL Reserved Words 全表（含 `COLUMN_VALUE`、`NESTED_TABLE_ID`、`ROWID`） |
| `sqlserver2019.txt` | SQL Server 2019 | https://learn.microsoft.com/en-us/sql/t-sql/language-elements/reserved-keywords-transact-sql?view=sql-server-ver16 | SQL Server / Azure Synapse 保留关键字表；不含 ODBC/ISO 附加表 |
| `postgresql.txt` | PostgreSQL 17 | https://www.postgresql.org/docs/current/sql-keywords-appendix.html | PostgreSQL 列标记为 `reserved` / `reserved (can be function or type name)` 的词 |
| `h2.txt` | H2 2.3 | https://www.h2database.com/html/advanced.html | 官方 “Keywords / Reserved Words” 表中 H2 列为 `+` 的词 |
| `dm8.txt` | 达梦 DM8 | https://eco.dameng.com/document/dm/zh-cn/pm/sql-appendix.html | 附录 1 中带 `*` 的**系统保留字**；不带 `*` 的关键字未收录 |
| `kingbaseesv8.txt` | KingbaseES V8R6 | https://help.kingbase.com.cn/v8/development/sql-plsql/sql/C_KES_SQL_Reserved_Words_and_Keywords.html | 表中 KingbaseES 列为“保留”的词 |
| `opengauss5.txt` | openGauss 5.0 | https://docs.opengauss.org/zh/docs/5.0.0/docs/SQLReference/关键字.html | 表中 openGauss 列为“保留”的词 |

## 口径说明

- **达梦 `TYPE`**：官方附录把它列为关键字（不带 `*`），不是系统保留字；`COMMENT` 是系统保留字。
  因此 `sw_bpm_ext_datasource.type` 不在保留字命中内，B2 在 DM8 上实测是否可作列名后再决定引用或改名。
- **KingbaseES / openGauss `TYPE`、`COMMENT`**：官方表均标为“非保留”，可作列名。
- **MySQL / MariaDB 非保留关键字**：如 `TYPE`、`STATUS`、`VALUE` 不在清单内。
- **`comment` 命中**：0.3.2 的 `sw_bpm_task_opinion_draft.comment` 由 `R__0.4.0_rename_bpm_opinion_comment.sql`
  改名为 `opinion_comment`，脚本把该历史命中记为已处理（`RENAMED`），不计为失败。

# openGauss 5 Migration Scripts

本目录由 `postgresql/` 转换，使用 `SwVendorMigrationRunner`（Flyway 无 openGauss 模块）。

## 数据库初始化要求

openGauss 官方镜像默认集群是 `SQL_ASCII` + `A`（Oracle）兼容库；本项目按 PG 语义实现，
必须在建库时显式指定：

```sql
CREATE DATABASE <db> WITH ENCODING 'UTF8' DBCOMPATIBILITY 'PG' TEMPLATE=template0;
```

`DatabaseCompatibilityGuard` 会在迁移前检查 `server_encoding=UTF8` 与 `sql_compatibility=PG`，
不满足直接拒绝启动，避免空串被当作 NULL、中文按错误编码落库。

## 与 PostgreSQL 脚本的差异

- 删除 pgvector 的 `DO` 块：openGauss 不提供 pgvector，向量由 `V0.4.0` 的
  `sw_knowledge_vector` 文本表持久化。
- `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT` 拆为三步：先加列并带默认值、回填 NULL、
  再 `SET NOT NULL`（openGauss 在 add 同句时先校验 NOT NULL）。
- 版本化脚本去掉 `ADD COLUMN IF NOT EXISTS`（openGauss 5 不支持）；基线内重复的 3 处加列已去重。
- `INSERT ... ON CONFLICT` 改为 `INSERT ... SELECT ... WHERE NOT EXISTS`。
- `SwVendorMigrationRunner` 增加 `DO $$ ... $$` 块支持（Spring `ScriptUtils` 不识别美元引用）。

## 验证结果（5.0.0 社区镜像）

- 30 条迁移、种子 4 租户/143 菜单、与 PG 基准库两层比对差异 0。
- 启动冒烟：MyBatis、中文、JSONB、动态宽表建表/读写、向量持久化、登录/菜单/审批/定时任务。

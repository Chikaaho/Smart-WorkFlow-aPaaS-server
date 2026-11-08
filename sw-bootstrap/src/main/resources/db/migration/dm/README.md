# DM8（达梦）迁移脚本说明

## 状态

- 本目录脚本由 Oracle 21c 脚本转换：去掉 `VARCHAR2(n CHAR)` 的 `CHAR` 长度单位，按
  `LENGTH_IN_CHAR=1` 初始化时长度按字符计。
- 0.4.0 发布口径：**DM8 由 Owner 在真实达梦上验证**；Executor 不持 DM8 授权，不使用社区镜像。
- 已通过静态检查：官方保留字清单、标识符长度、与 Oracle 脚本逐语句对照。
- `SwVendorMigrationRunnerDmProxyTest` 暂不启用：H2 Oracle 兼容模式不支持达梦的
  `ALTER TABLE ... MODIFY (col ...)` 与函数索引，无法作为有效代理；该测试保留原因说明。

## 建议初始化参数

| 参数 | 值 | 说明 |
|---|---|---|
| `CASE_SENSITIVE` | `Y` | 与 Oracle/PostgreSQL 引用小写标识符的行为一致 |
| `LENGTH_IN_CHAR` | `1` | `VARCHAR2(n)` 按字符计长，脚本已去掉 `CHAR` 单位 |
| `COMPATIBLE_MODE` | 默认/Oracle | 脚本使用 `NUMBER`、`VARCHAR2`、`CLOB`、`DUAL` 等 Oracle 兼容语法 |
| 字符集 | UTF-8 | 与固定表/种子中文一致 |

## 连接示例

```properties
spring.datasource.dynamic.datasource.master.driver-class-name=dm.jdbc.driver.DmDriver
spring.datasource.dynamic.datasource.master.url=jdbc:dm://127.0.0.1:5236
spring.datasource.dynamic.datasource.master.username=SWMDB
spring.datasource.dynamic.datasource.master.password=<secret>
```

达梦 JDBC 驱动需从官方安装包取得并放入运行时类路径（未提交到仓库）。

## 验证方式

1. 空库启动应用，`FlywayConfiguration` 识别 `jdbc:dm:` 后走 `SwVendorMigrationRunner`，
   在 `sw_schema_history` 记录 30 条脚本，终点 `0.4.0`。
2. 用 `SeedComparisonTest` 对 PG 基准库比对：

```bash
mvn -pl sw-bootstrap -am -Dtest=SeedComparisonTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dseed.base.url=jdbc:postgresql://127.0.0.1:5432/seed_base \
  -Dseed.base.user=postgres -Dseed.base.password=*** \
  -Dseed.target.url=jdbc:dm://127.0.0.1:5236 -Dseed.target.user=SWMDB -Dseed.target.password=*** test
```

3. 启动冒烟需覆盖：MyBatis、中文、CLOB、分页、Flowable、动态宽表建表/读写、主流程业务。

## 已知待 Owner 确认点

- **可空唯一键**：`uk_sw_notify_msg_idempotency`、`uk_sw_notify_msg_identity`、
  `uk_sw_form_trace_idem`、`uk_sw_agent_exec_req_idem`、`uk_sw_bpm_command_logical`、
  `uk_sw_bpm_comm_instance_receiver`、`uk_sw_bpm_dyn_branch`、`uk_sw_form_cfg_tname`、
  `uk_iot_device_product_device` 在 Oracle 上会因复合唯一索引“部分列为 NULL 仍判重”而阻塞多行；
  DM 脚本当前保持普通唯一索引（未使用函数索引，避免 H2 代理不可执行）。
  请 Owner 在 DM8 上确认其 NULL 判重语义；若与 Oracle 相同，需要单独补丁（函数索引或生成列）。
- **`uk_sw_bpm_binding_active`**：Oracle 用函数索引只约束 `active=1` 的行；DM 脚本改为
  `(tenant_id, form_key, active)` 普通唯一索引。若业务允许同一表单存在多条 `active=0` 历史绑定，
  Owner 验证时需重点检查。

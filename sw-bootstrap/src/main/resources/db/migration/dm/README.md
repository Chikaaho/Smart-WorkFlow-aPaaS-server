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

## Owner 验证清单

DM8 支持函数索引；脚本已与 Oracle 同口径：

- 9 个可空唯一键（`uk_sw_notify_msg_idempotency`、`uk_sw_notify_msg_identity`、
  `uk_sw_form_trace_idem`、`uk_sw_agent_exec_req_idem`、`uk_sw_bpm_command_logical`、
  `uk_sw_bpm_comm_instance_receiver`、`uk_sw_bpm_dyn_branch`、`uk_sw_form_cfg_tname`、
  `uk_iot_device_product_device`）用 `CASE WHEN 所有可空键列 IS NOT NULL THEN 列 END`
  函数索引：只有所有键列非空时才参与唯一判重，与 PG“任一键列为 NULL 即不判重”一致。
- `uk_sw_bpm_binding_active` 用 `CASE WHEN active = 1 THEN tenant_id/form_key END`
  函数索引：只约束启用中的绑定，多条 `active=0` 历史绑定可并存。

请 Owner 在真实 DM8 上验证以下正反例：

1. 同一租户两条 `idempotency_key IS NULL` 的通知可并存；相同非空 `idempotency_key`
   的第二条通知被唯一索引拒绝。
2. 同一租户两条 `biz_id IS NULL` 的系统通告可并存；相同非空通知身份的第二条被拒绝。
3. `sw_form_config.table_name IS NULL` 的多条草稿配置可并存；相同非空 `table_name`
   的重复配置被拒绝。
4. 同一表单多条 `active=0` 的绑定可并存；第二条 `active=1` 的绑定被拒绝。
5. 单列可空唯一键（`sw_bpm_urge_record.cooldown_key`、
   `sw_iot_device_command.idempotent_key`）允许多条 NULL，非空重复仍被拒绝。
6. `sw_bpm_dynamic_branch` 的 `round_no IS NULL` 行可多条并存；非空轮次身份重复被拒绝。


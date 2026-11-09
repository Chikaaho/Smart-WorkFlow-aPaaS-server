# DM8 与 Oracle 21c 迁移脚本差异报告（T5）

## 结论

- 文件数：Oracle 30 个 `.sql`，DM8 30 个同名 `.sql`，一一对应。
- 内容差异：**仅有** `VARCHAR2(n CHAR)` → `VARCHAR2(n)`，共 **703** 处。
  DM8 按 `LENGTH_IN_CHAR=1` 初始化，长度单位由数据库参数保证，不需要 `CHAR` 单位。
- 除此之外逐字节一致：表、索引、约束、函数索引、种子 SQL 全部相同。
- 因此语句数与 Oracle 版本一致；不需要单独列出语句级差异。
- 可执行校验：`DmOracleScriptParityTest` 逐文件断言上述规则，并在全量门禁中运行。

## 与 Oracle 同步的索引语义

- 9 个可空唯一键与 `uk_sw_bpm_binding_active` 均保留 Oracle 的 `CASE WHEN` 函数索引：
  只有所有可空键列非空（或 `active=1`）时才参与唯一判重。
- 正反例验证清单位于同目录 `README.md` 的“Owner 验证清单”。

## 未覆盖项

- H2 Oracle 兼容模式不支持 `ALTER TABLE ... MODIFY (col ...)` 与函数索引，
  `SwVendorMigrationRunnerDmProxyTest` 已停用并注明原因；DM8 实库由 Owner 验证。

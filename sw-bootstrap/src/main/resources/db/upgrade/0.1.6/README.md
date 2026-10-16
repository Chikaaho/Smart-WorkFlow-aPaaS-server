# 0.1.6 发布收敛升级通道（独立升级通道，不进 Flyway 主扫描）

> 本目录是 0.1.6 发版收敛（`V0.1.5__baseline_seed.sql` + `V20261015190000__application_center.sql`
> → 每方言单一 `V0.1.6__baseline_seed.sql`）的**独立升级通道**资产。Flyway 主扫描位置见
> `sw-bootstrap/src/main/resources/application.yml` `spring.flyway.locations`
> （`db/migration/{vendor}` 与各子域 `{vendor}` 目录），**不包含本目录**——
> 本目录文件不会被应用启动时的 Flyway 扫描或执行，仅由运维/迁移脚本显式使用。
> 依据：`product/0.1.6-release/ready/direction-release-016.md`。

## 目录内容与扫描边界

| 路径 | 内容 | 扫描边界 |
|---|---|---|
| `legacy-0.1.5/{h2,postgresql}/` | 收敛前的 versioned 迁移（`V0.1.5__baseline_seed.sql`、`V20261015190000__application_center.sql`，逐字节原文件） | 不扫描；供存量库增量升级、回溯比对与历史提交外的本地恢复 |
| `transition-history-to-0.1.6.{postgresql,h2}.sql` | 历史衔接：结构已达 0.1.6 终态的库，把 versioned 历史记录收敛为单条 0.1.6 成功记录 | 不扫描；人工/脚本显式执行，恰好一次 |
| `upgrade-to-0.1.6-from-0.1.5.{postgresql,h2}.sql` | 存量库升级：已衔接 0.1.5 但未执行应用中心增量的库 → 应用中心增量 DDL/承接 + 历史衔接 | 不扫描；人工/脚本显式执行，恰好一次 |

主链保留的 R__ 可重复迁移（10 支/方言）不在本目录：收敛后它们继续随主链运行，
checksum 未变，存量库既有 R__ 记录在衔接后仍有效。

## 受支持入口状态与选择

| 存量库状态（现场核实 flyway_schema_history 后判定） | 使用 |
|---|---|
| versioned 终点 = 单条 V0.1.5（已衔接 0.1.5、未应用应用中心增量） | `upgrade-to-0.1.6-from-0.1.5.<方言>.sql` |
| versioned 终点 = V0.1.5 + V20261015190000（已应用应用中心增量，如开发库） | `transition-history-to-0.1.6.<方言>.sql` |
| versioned 终点 = V0.1.5 + V20261015190000 之外的 0.1.5 后中间态 | 按实际终点点选 legacy 内缺失文件顺序执行，再套用 `upgrade-to-0.1.6-from-0.1.5`（或先补应用中心增量后 transition） |
| versioned 终点 = 旧链 V0.1.10 / V0.1.1（0.1.5 之前形态） | 先按 `db/upgrade/0.1.5/README.md` 到达 0.1.5 衔接态，再套用上表 |
| ≤0.1.2（V1—V104 旧链）及 V94 等历史终点 | 不支持原地升级（沿既有边界），仅全新建库 |

执行前后必须：备份（数据库 dump / 文件级） → 执行 → 启动应用验证
`flyway_schema_history` 恰 1 条 versioned 成功记录（0.1.6）且启动日志无迁移执行、无 validate 错误。

## checksum 依据

`V0.1.6__baseline_seed.sql` 的 Flyway checksum（按 Flyway CRC32 算法独立复算，并由全新库
实跑 `flyway_schema_history` 读回复核）：
- h2：`-1890939561`
- postgresql：`-1768731444`

等价性验证（收敛前终态 vs 新种子全新库终态）与升级通道受控验证见
`product/0.1.6-release/receipts/release-016-01.md`。

# 0.1.7 发布收敛升级通道（独立升级通道，不进 Flyway 主扫描）

> 本目录是 0.1.7 发版收敛（`V0.1.6__baseline_seed.sql` + `V0.1.7__bugfix017_menu_and_app_scope.sql`
> → 每方言单一 `V0.1.7__baseline_seed.sql`）的**独立升级通道**资产。Flyway 主扫描位置见
> `sw-bootstrap/src/main/resources/application.yml` `spring.flyway.locations`
> （`db/migration/{vendor}` 与各子域 `{vendor}` 目录），**不包含本目录**——
> 本目录文件不会被应用启动时的 Flyway 扫描或执行，仅由运维/迁移脚本显式使用。
> 依据：`product/0.1.7-bugfix/ready/direction-release-handoff-017.md`。

## 目录内容与扫描边界

| 路径 | 内容 | 扫描边界 |
|---|---|---|
| `legacy-0.1.6/{h2,postgresql}/` | 收敛前的 versioned 迁移（`V0.1.6__baseline_seed.sql`、`V0.1.7__bugfix017_menu_and_app_scope.sql`，逐字节原文件） | 不扫描；供存量库增量升级、回溯比对与历史提交外的本地恢复 |
| `transition-history-to-0.1.7.{postgresql,h2}.sql` | 历史衔接：结构已达 0.1.7 终态的库（历史为 0.1.6 + V0.1.7 两条 versioned 记录），把 versioned 历史记录收敛为单条 0.1.7 成功记录 | 不扫描；人工/脚本显式执行，恰好一次 |
| `upgrade-to-0.1.7-from-0.1.6.{postgresql,h2}.sql` | 存量库升级：已衔接 0.1.6 但未执行 0.1.7 期增量的库 → 菜单调整与应用可见范围多维度增量 DDL/承接 + 历史衔接 | 不扫描；人工/脚本显式执行，恰好一次 |

主链保留的 R__ 可重复迁移（12 支/方言）不在本目录：收敛后它们继续随主链运行，
checksum 未变，存量库既有 R__ 记录在衔接后仍有效。

## 受支持入口状态与选择

| 存量库状态（现场核实 flyway_schema_history 后判定） | 使用 |
|---|---|
| versioned 终点 = 单条 V0.1.6（已衔接 0.1.6、未应用 0.1.7 期增量；**UAT 当前形态**） | `upgrade-to-0.1.7-from-0.1.6.<方言>.sql` |
| versioned 终点 = V0.1.6 + V0.1.7（已随开发分支运行 0.1.7 期增量） | `transition-history-to-0.1.7.<方言>.sql` |
| versioned 终点 = 0.1.6 之外的 0.1.6 期中间态 | 按实际终点点选 legacy 内缺失文件顺序执行，再套用 `upgrade-to-0.1.7-from-0.1.6`（或先补增量后 transition） |
| versioned 终点 = 旧链 V0.1.5 / V0.1.10 / V0.1.1（0.1.6 之前形态） | 先按 `db/upgrade/0.1.6/README.md` 到达 0.1.6 衔接态，再套用上表 |
| ≤0.1.2（V1—V104 旧链）及 V94 等历史终点 | 不支持原地升级（沿既有边界），仅全新建库 |

执行前后必须：备份（数据库 dump / 文件级） → 执行 → 启动应用验证
`flyway_schema_history` 恰 1 条 versioned 成功记录（0.1.7）且启动日志无迁移执行、无 validate 错误。

## checksum 依据

`V0.1.7__baseline_seed.sql` 的 Flyway checksum（按 Flyway `ChecksumCalculator` 算法独立复算——
逐行读取、剥离行分隔符、按 UTF-8 字节累计 CRC32；并以 Flyway 11.3.4 实测值标定：同一算法对收敛前
`V0.1.6__baseline_seed.sql` 复算得 h2 `-1890939561`／postgresql `-1768731444`，与 0.1.6 发布记录及
UAT 库内实际记录一致）：
- h2：`-1195786777`
- postgresql：`2016046975`

# 0.1.5 发布收敛升级通道（独立升级通道，不进 Flyway 主扫描）

> 本目录是 0.1.5 发版收敛（`V0.1.0—V0.1.10` → 每方言单一 `V0.1.5__baseline_seed.sql`）的
> **独立升级通道**资产。Flyway 主扫描位置见 `application.yml` `spring.flyway.locations`
> （`db/migration/{vendor}` 与各子域 `{vendor}` 目录），**不包含本目录**——
> 本目录文件不会被应用启动时的 Flyway 扫描或执行，仅由运维/迁移脚本显式使用。
> 依据：`product/bugfix-20261013/ready/development-declaration.md` §2/§4/§5。

## 目录内容与扫描边界

| 路径 | 内容 | 扫描边界 |
|---|---|---|
| `legacy-0.1.x/{h2,postgresql}/` | 收敛前的旧 versioned 链 V0.1.0—V0.1.10（逐字节原文件） | 不扫描；供存量库增量升级与回溯比对 |
| `transition-history-to-0.1.5.{postgresql,h2}.sql` | 历史衔接：结构已达 0.1.5 终态的库，把 versioned 历史记录收敛为单条 0.1.5 成功记录 | 不扫描；人工/脚本显式执行，恰好一次 |
| `upgrade-to-0.1.5-from-0.1.1.{postgresql,h2}.sql` | 存量库升级：legacy 0.1.1 状态 → 增量 DDL（V0.1.2—V0.1.10）+ 历史衔接 | 不扫描；人工/脚本显式执行，恰好一次 |

主链保留的 R__ 可重复迁移（9 支/方言）不在本目录：收敛后它们继续随主链运行，
checksum 未变，存量库既有 R__ 记录在衔接后仍有效。

## 受支持入口状态与选择

| 存量库状态（现场核实 flyway_schema_history 后判定） | 使用 |
|---|---|
| versioned 终点 = V0.1.10（结构已达终态，如开发库） | `transition-history-to-0.1.5.<方言>.sql` |
| versioned 终点 = V0.1.1（如 UAT 2026-10-13 巡检所见） | `upgrade-to-0.1.5-from-0.1.1.<方言>.sql` |
| versioned 终点 = 0.1.2—0.1.9 中间状态 | 用 `legacy-0.1.x/<方言>/` 中大于该终点的 V 文件按版本序执行，再执行 transition 脚本 |
| ≤0.1.2（V1—V104 旧链） | 不支持（沿 0.1.3 发布边界：仅全新建库） |

执行前后必须：`pg_dump` 备份 → 执行 → 启动应用验证
`flyway_schema_history` 恰 1 条 versioned 成功记录（0.1.5）且启动日志无迁移执行、无 validate 错误。

## checksum 依据

`V0.1.5__baseline_seed.sql` 的 Flyway checksum（由全新库实跑 `flyway_schema_history` 读回）：
- h2：`1778504403`
- postgresql：`-454928747`

等价性验证（旧链 vs 新种子全新库终态，双方言逐字段一致）与升级通道受控验证见
`product/bugfix-20261013/receipts/release-015-01.md`。

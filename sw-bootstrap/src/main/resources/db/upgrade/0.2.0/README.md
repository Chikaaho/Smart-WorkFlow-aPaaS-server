# 0.2.0 发布收敛升级通道（独立升级通道，不进 Flyway 主扫描）

> 本目录是 0.2.0 发版收敛（`V0.1.7__baseline_seed.sql` + `V0.1.8__tenant_username_unique.sql`
> + `V0.1.9__tenant_admin_identity.sql` + `V0.1.10__tenant_status_audit.sql`
> → 每方言单一 `V0.2.0__baseline_seed.sql`）的**独立升级通道**资产。Flyway 主扫描位置见
> `sw-bootstrap/src/main/resources/application.yml` `spring.flyway.locations`
> （`db/migration/{vendor}` 与各子域 `{vendor}` 目录），**不包含本目录**——
> 本目录文件不会被应用启动时的 Flyway 扫描或执行，仅由运维/迁移脚本显式使用。
> 依据：`product/0.2.0/receipts/planning-execution-prompt-unified-020-36.md`。

## 目录内容与扫描边界

| 路径 | 内容 | 扫描边界 |
|---|---|---|
| `legacy-0.1.10/{h2,postgresql}/` | 收敛前的 versioned 迁移（`V0.1.7__baseline_seed.sql`、`V0.1.8__tenant_username_unique.sql`、`V0.1.9__tenant_admin_identity.sql`、`V0.1.10__tenant_status_audit.sql`，逐字节原文件） | 不扫描；供存量库增量升级、回溯比对与历史提交外的本地恢复 |
| `transition-history-to-0.2.0.{postgresql,h2}.sql` | 历史衔接：结构已达 0.2.0 终态的库（历史为 V0.1.7—V0.1.10 四条 versioned 记录），把 versioned 历史记录收敛为单条 0.2.0 成功记录 | 不扫描；人工/脚本显式执行，恰好一次 |
| `upgrade-to-0.2.0-from-0.1.7.{postgresql,h2}.sql` | 存量库升级：已衔接 0.1.7 但未执行 0.1.8/0.1.9/0.1.10 增量的库 → 租户用户名唯一、租户管理员身份、租户状态审计增量 DDL/承接 + 历史衔接 | 不扫描；人工/脚本显式执行，恰好一次 |

主链保留的 R__ 可重复迁移（18 支/方言）不在本目录：收敛后它们继续随主链运行，
checksum 未变，存量库既有 R__ 记录在衔接后仍有效。

## 受支持入口状态与选择

| 存量库状态（现场核实 flyway_schema_history 后判定） | 使用 |
|---|---|
| versioned 终点 = V0.1.7 + V0.1.8 + V0.1.9 + V0.1.10 四条记录（已随开发分支运行 0.2.0 期增量；**当前开发/UAT 形态**） | `transition-history-to-0.2.0.<方言>.sql` |
| versioned 终点 = 单条 V0.1.7（已衔接 0.1.7、未应用 0.1.8/0.1.9/0.1.10 增量） | `upgrade-to-0.2.0-from-0.1.7.<方言>.sql` |
| versioned 终点 = V0.1.7 + V0.1.8（已应用 0.1.8，未应用 0.1.9/0.1.10） | 依次执行 `legacy-0.1.10/<方言>/V0.1.9__*`、`V0.1.10__*`，再套用 `transition-history-to-0.2.0.<方言>.sql` |
| versioned 终点 = V0.1.7 + V0.1.8 + V0.1.9（已应用至 0.1.9） | 执行 `legacy-0.1.10/<方言>/V0.1.10__*`，再套用 `transition-history-to-0.2.0.<方言>.sql` |
| versioned 终点 = 0.1.7 之前的旧链（0.1.6 / 0.1.5 / V0.1.10 旧链等） | 先按 `db/upgrade/0.1.7/README.md` 到达 0.1.7 衔接态，再套用上表 |
| ≤0.1.2（V1—V104 旧链）及 V94 等历史终点 | 不支持原地升级（沿既有边界），仅全新建库 |

全新建库直接由主链执行 `V0.2.0__baseline_seed.sql`（外加 R__ 对账），终态与
0.1.7—0.1.10 原链逐字节等价。

执行前后必须：备份（数据库 dump / 文件级） → 执行 → 启动应用验证
`flyway_schema_history` 恰 1 条 versioned 成功记录（0.2.0）且启动日志无迁移执行、无 validate 错误。

## 等价性（逐字节拼接）

`V0.2.0__baseline_seed.sql` 的内容 = 上述四支原文件按版本序逐字节拼接，仅增加文件头说明与
四条段头注释（`-- ======== 来源: <file> ========`）；不含 R__ 可重复迁移。

| 方言 | 四支原文件直接拼接 sha256 | 重建拼接（含段间空行、剥离新增注释）sha256 | 新基线全文 sha256 |
|---|---|---|---|
| h2 | `7b33e01fccd709268c633d32a7081b7f87f5c750a585f1050e635afa0c0e853d`（317097 B） | `fb5eba74921ed1b2760c928800f63d247be0f39c8318c3edbe895c5e9d0c48b8`（317099 B） | `c603a00fed88ddd636ae9849e887e2cdb6db89ae3ea0e71f0d095afb0d1bb8c7`（318113 B） |
| postgresql | `7ee0d3cdbbb02fd73eca279e2380f1639073325c3d6ea0e6790f0977ace82d96`（340236 B） | `6577a094e178e16d6dfb5cfd31f3b5d9b0f93c55825d3f2ea3c5d224bc593fc0`（340238 B） | `5a93e7ee21dad18b616cc77d3190bcb284502112796e2d026de1a959b369ffc6`（341260 B） |

验证方法（`E:/code/Smart-WorkFlow-Agent-Evidence/0.2.0/tools/verify-020-baseline.py`）：
从 `legacy-0.1.10/<方言>/` 四支原文件重建"段间空行 + 段头"拼接，再剥离新基线中新增的
文件头与段头注释行，两者 sha256 完全相等（双方言 `BYTE-IDENTICAL: True`）。与"四支原文件
直接拼接"相比仅多 2 字节段间空行（V0.1.7→V0.1.8、V0.1.9→V0.1.10 各一行空行；
V0.1.8 原文已以空行结尾，无需补分隔）。

逐段 sha256（双方言 V0.1.8/V0.1.9/V0.1.10 内容相同）：

| 段 | sha256 |
|---|---|
| h2 `V0.1.7__baseline_seed.sql` | `3a67f36c5e619e13035e277ad9826744740e59a8066ce7e4d007e65c9626b844` |
| postgresql `V0.1.7__baseline_seed.sql` | `362b8e6fbac9553f15d43aa2fd24f77be72b8d208b0f3f8e8264b917ce71a820` |
| `V0.1.8__tenant_username_unique.sql` | `a1bad82e4369ce95a5a40f7cf6d2977d3fe160ea45587c6a00f9464088465e03` |
| `V0.1.9__tenant_admin_identity.sql` | `804675241275a480454ccf68782c04f025dcf5871335a4cc49231a670b936884` |
| `V0.1.10__tenant_status_audit.sql` | `00131c1b154178621b19c458d3a47477a575d485e3abaec1dea31cc55ca8dd40` |

## checksum 依据

`V0.2.0__baseline_seed.sql` 的 Flyway checksum（按 Flyway `ChecksumCalculator` 算法独立复算——
逐行读取、剥离行分隔符、按 UTF-8 字节累计 CRC32；并以 Flyway 11.3.4 实测值标定：同一算法对
0.1.7 种子复算得 h2 `-1195786777`／postgresql `2016046975`，与 0.1.7 发布记录一致）：
- h2：`-1974125500`
- postgresql：`1897854550`

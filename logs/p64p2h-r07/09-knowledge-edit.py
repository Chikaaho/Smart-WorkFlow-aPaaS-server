# -*- coding: utf-8 -*-
# P64 回执09 P2-07b-S：knowledge/memory/Server 清单当前字段精确更新（回执09 链 + Owner 指针授权）
import io

WS = 'E:/code/Smart-WorkFlow-Agent-Workspace'

def rd(p):
    return io.open(p, encoding='utf-8').read().split('\n')

def wr(p, lines):
    io.open(p, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))

NEW_TOP = u'''> **2026-10-11 P64 阶段Ⅱ回执09 提交，待规划独立验收（阶段Ⅰ=PASSED（验收08）、P64=IN_PROGRESS、阶段Ⅱ=VERIFYING、阶段Ⅲ未验收）**——Executor 按[复审08](product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-08.md)（含 Owner 补记 §5）与唯一执行入口[提示07](product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-07.md)收口剩余 T/S 两项，追加[回执09](product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-09.md)（证据树 [phase2-09](product/p64-mes-advanced-orchestration/receipts/evidence/phase2-09/INDEX.md)）。**处置要点**：**P2-06a-C-T 生产事务注解原文（含注解区间提取）**——`TriggerExecutionService.java`（SHA `c3e87b5c…`，对应 3341de5 已过快照，本轮零改动）区间原文 `raw/source/09-T-tx-annotations.txt`：**L119 `@Transactional(propagation = Propagation.REQUIRED)` + L120 `public void onTaskActionCompleted(…)` 签名**、**L151 同注解 + L152 `onProcessCompleted(…)` 签名**（"事件入口（加入调用方事务）"javadoc 在案）；复审08 指出的方法边界漏注解问题以"含注解+签名的最小区间"修正，附件核对确含 @Transactional 行；锁 SQL（mapper L100-102）/拒绝登记边界（dispatch 冻结 L340→item 循环 L350-351→catch L358→诊断 L361→errorText L364-368）/竞争 1允1拒 sum1000/四 case 26/0 直接引用已过原件不重开。**P2-07b-L 关闭（Owner 裁量）**——[Owner 授权记录](product/p64-mes-advanced-orchestration/receipts/owner-gitlink-decision-20261011.md)（2026-10-11）：Owner 认可**保留 Server `7fffef9c…`/Web `4333145a…`** 两指针，并授权**后续同类常规 Git 指针调整由代理自行核实决定、直接报告**（覆盖实施授权旧固定指针要求；不改 system/角色文件、不扩展写权限）；现有两值无需修改。**P2-07b-S 完整字段回读**——knowledge/memory/Server 清单当前字段更新为回执09 链（本条目/session-handoff/features p64/architecture；**实际完整字段片段**回读附件 `raw/db/09-knowledge-full-fields.txt`：路径:行号+完整字段原文（按句切分不截断 UTF-8），覆盖：阶段状态句、P62 条件恢复句（功能 COMPLETED/性能待阶段Ⅱ经 Planner 确认 PASSED 后恢复/当前未启动未验证/策略 OFF）、v5 当前 v4 历史句、Owner 指针授权句（保留两值+自主授权）、唯一下一动作句）；memory/state.md L3 唯一下一动作→**Planner 验收回执09**；Server 功能清单焦点行滚动（docs 批次）。**本轮零业务代码改动、无服务启动、无测试重跑**（纯区间提取+文档收尾）。**唯一下一动作 = Planner 按提示07 客观矩阵独立验收回执09 与 phase2-09 证据包**；功能 47、46/22/22=90、ADV64、问题 57、P63=COMPLETED 不变；Executor 不写功能 PASSED/COMPLETED、不核销 P、不晋级基线。'''

# 1. current-status.md
p = WS + '/knowledge/current-status.md'
lines = rd(p)
assert lines[2].startswith(u'> **2026-10-11 P64 阶段Ⅱ回执08'), lines[2][:50]
lines[2] = u'> 【历史条目：2026-10-11 被上条回执09 条目覆盖入"当前值"部分；其 Owner 指针授权前的事实结论与 T 拒绝登记边界继续有效。】' + lines[2][2:]
lines.insert(2, NEW_TOP)
wr(p, lines)
print('current-status OK')

# 2. session-handoff.md L3
p = WS + '/knowledge/session-handoff.md'
lines = rd(p)
for i, l in enumerate(lines):
    if l.startswith(u'> **当前任务覆盖值'):
        lines[i] = (u'> **当前任务覆盖值（2026-10-11：P64=IN_PROGRESS·阶段Ⅰ PASSED（规划验收08）·阶段Ⅱ VERIFYING（回执09 提交待规划独立验收）；P63=COMPLETED（规划已确认，2026-10-08）；正式功能 47、项目总数统一 47）**：[复审08](../product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-08.md)关闭 COUNT 原文/拒绝路径/指针变更事实后，剩余 T/S 已按唯一执行入口[提示07](../product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-07.md)收口并提交[回执09](../product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-09.md)（证据树 [phase2-09](../product/p64-mes-advanced-orchestration/receipts/evidence/phase2-09/INDEX.md)）：**T** 生产事务注解原文以含注解+签名最小区间修正（`TriggerExecutionService` SHA c3e87b5c…：L119 `@Transactional(REQUIRED)`+onTaskActionCompleted 签名、L151+onProcessCompleted 签名；锁 SQL/拒绝登记边界/竞争与四 case 引用已过原件）；**S** knowledge/memory/Server 清单当前字段=回执09 链+完整字段片段回读（按句切分不截 UTF-8，覆盖状态/P62 条件恢复/v5 当前 v4 历史/指针授权/唯一下一动作）；**Owner 指针授权记录（2026-10-11）**：认可保留 Server 7fffef9c…/Web 4333145a…，并授权后续同类常规 Git 指针调整由代理自行核实决定、直接报告（覆盖旧固定指针要求；P2-07b-L 关闭）。门禁：本轮零业务代码改动、无服务启动、无测试重跑。功能 47、清单 ✅46/🟦22/⬜22、ADV64、问题 57、P63=COMPLETED 保持；P62 功能 COMPLETED、性能待阶段Ⅱ经 Planner 确认 PASSED 后恢复（当前未启动、策略 OFF）；**唯一下一动作 = Planner 按提示07 客观矩阵独立验收回执09 与 phase2-09 证据包**（Executor 不写功能 PASSED/COMPLETED、不核销 P、不晋级基线）。历史详情：`knowledge/current-status.md`（回执08 条目已历史化）、`knowledge/history/`。')
        print('session-handoff L%d OK' % (i + 1))
        break
else:
    raise AssertionError('handoff top not found')
wr(p, lines)

# 3. features p64 L17/L26
p = WS + '/knowledge/features/p64-mes-advanced-orchestration.md'
lines = rd(p)
i17 = next(i for i, l in enumerate(lines) if l.startswith(u'| 当前状态 |'))
lines[i17] = u'| 当前状态 | **VERIFYING（阶段Ⅱ回执09 提交待规划独立验收，2026-10-11）**——[复审08](../../product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-08.md)关闭 COUNT 原文/拒绝路径/指针变更事实后，剩余 T/S 按[提示07](../../product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-07.md)收口并提交[回执09](../../product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-09.md)（证据树 phase2-09）：**P2-06a-C-T** 生产事务注解原文——含注解+签名最小区间提取（`TriggerExecutionService` SHA c3e87b5c…：**L119 `@Transactional(propagation = Propagation.REQUIRED)`+L120 onTaskActionCompleted 签名**、**L151+L152 onProcessCompleted 签名**，"加入调用方事务"javadoc 在案；修正回执08 方法边界漏注解问题，附件核对确含 @Transactional 行）；锁 SQL（mapper L100-102）/拒绝登记边界（dispatch 冻结 L340→item 循环 L350-351→catch L358→诊断 L361→errorText L364-368）/竞争 1允1拒 sum1000/四 case 26/0 引用已过原件。**P2-07b-S** knowledge/memory/Server 清单当前字段=回执09 链+完整字段片段回读（按句切分不截 UTF-8）。**P2-07b-L 关闭（Owner 裁量 2026-10-11）**：认可保留 Server 7fffef9c…/Web 4333145a…，并授权后续同类常规 Git 指针调整由代理自行核实决定、直接报告（覆盖旧固定指针要求）。本轮零业务代码改动、无服务启动、无测试重跑。原回执08 轮描述见下段历史 |'
i26 = next(i for i, l in enumerate(lines) if l.startswith(u'| 当前规划复核 |'))
lines[i26] = u'| 当前规划复核 | [复审08](../../product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-08.md)（2026-10-11，关闭 COUNT/拒绝路径/指针事实；§5 Owner 补记认可保留两指针并授权常规指针自主调整）→ [提示07](../../product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-07.md)（唯一执行入口，替代提示06，仅 T/S）→ [回执09](../../product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-09.md)（已提交待独立验收，证据树 phase2-09）；更早轮次（复审07/提示06/回执08 及之前）全部追溯化 |'
wr(p, lines)
print('features L%d/L%d OK' % (i17 + 1, i26 + 1))

# 4. architecture.md L259
p = WS + '/knowledge/architecture.md'
lines = rd(p)
i = next(i for i, l in enumerate(lines) if u'当前活动业务功能=P64' in l)
old = lines[i]
lines[i] = lines[i].replace(
    u'阶段Ⅱ VERIFYING（2026-10-11 [复审07](product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-07.md) 后剩余 4 子ID按[提示06](product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-06.md)收口并提交[回执08](product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-08.md) 待独立验收：根锁生产事务组合证明（onTaskActionCompleted L119 @Transactional 调用方事务+mapper FOR UPDATE 锁SQL+dispatch 拒绝先于 item 登记→errorText 落库）、COUNT 三方法原文、gitlink 三层指针与值变链核实（9b9ed673 执行批次带入，非 Owner 提交）、当前权威原行回读；验证定义当前发布版=**v5**（v4 关闭新入口为历史事实）；证据树 phase2-08、ADR-002 生效',
    u'阶段Ⅱ VERIFYING（2026-10-11 [复审08](product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-08.md) 后剩余 T/S 按[提示07](product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-07.md)收口并提交[回执09](product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-09.md) 待独立验收：生产事务注解原文含注解区间提取（TriggerExecutionService L119/L151 @Transactional(REQUIRED)+签名，SHA c3e87b5c…）、完整字段片段回读；**Owner 指针授权（2026-10-11）**：保留根 gitlink Server 7fffef9c/Web 4333145a，后续同类常规指针调整由代理自行核实决定直接报告（P2-07b-L 关闭）；验证定义当前发布版=**v5**（v4 关闭新入口为历史事实）；证据树 phase2-09、ADR-002 生效')
assert lines[i] != old
print('architecture L%d OK' % (i + 1))
wr(p, lines)

# 5. memory/state.md L3 唯一下一动作
p = WS + '/memory/state.md'
lines = rd(p)
for i, l in enumerate(lines):
    if u'唯一下一动作：Planner按[提示06]' in l:
        lines[i] = l.replace(
            u'唯一下一动作：Planner按[提示06](../product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-06.md)四子项（T/C/L/S）客观矩阵独立验收[回执08](../product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-08.md)与phase2-08证据包（Executor已收口并提交回执08）；',
            u'唯一下一动作：Planner按[提示07](../product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-07.md)客观矩阵独立验收[回执09](../product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-09.md)与phase2-09证据包（T/S已收口提交回执09，Owner已认可保留两指针并授权常规指针自主调整）；')
        print('state L%d next-action OK' % (i + 1))
        break
else:
    raise AssertionError('state next-action not found')
wr(p, lines)
print('ALL EDIT DONE')

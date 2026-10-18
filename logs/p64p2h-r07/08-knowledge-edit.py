# -*- coding: utf-8 -*-
# P64 回执08 P2-07b-S：knowledge/memory/Server 清单当前字段精确更新（回执08 链）
import io

WS = 'E:/code/Smart-WorkFlow-Agent-Workspace'

def rd(p):
    with io.open(p, 'r', encoding='utf-8') as f:
        return f.read().split('\n')

def wr(p, lines):
    with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines))

NEW_TOP = u'''> **2026-10-11 P64 阶段Ⅱ回执08 提交，待规划独立验收（阶段Ⅰ=PASSED（验收08）、P64=IN_PROGRESS、阶段Ⅱ=VERIFYING、阶段Ⅲ未验收）**——Executor 按[复审07](product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-07.md)与唯一执行入口[提示06](product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-06.md)收口 4 个子ID（T/C/L/S），追加[回执08](product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-08.md)（证据树 [phase2-08](product/p64-mes-advanced-orchestration/receipts/evidence/phase2-08/INDEX.md)）。**处置要点**：**P2-06a-C-T 根锁生产事务与拒绝登记边界（组合证明）**——工具提取生产事务入口 `TriggerExecutionService.onTaskActionCompleted` **L119 `@Transactional(REQUIRED)`**（"加入调用方事务"；freezeBatch 无方法级事务注解，在调用方事务内持有至提交）、实际锁 SQL `BpmInstanceMapper` **L101-102 `SELECT id FROM sw_bpm_instance WHERE process_instance_id=#{…} AND deleted=0 FOR UPDATE`**、拒绝路径 `dispatch` **L340 freezeBatch 先于 L347 item 登记循环 → `ChildOrchestrationService` L159 抛 CHILD_CHAIN_OVER_LIMIT → catch L358 登记 `exec.errorText`（派发失败+消息）→ L367 updateById 落库（可诊断留痕，被拒动作的 item/intent 登记结构性不发生）**——直接断言=四 case `never insert(batch/item)、never signalWaitNode`（26/0）+竞争 1允1拒/sum1000/batchCount2（1/0）；组合证明=生产源边界（抛点先于登记+异常落 errorText 不静默），按提示06 允许的"已有运行结果+源内异常先于登记、异常不被吞"口径收口，未夸称 item/intent 直接计数断言。**P2-07b-G-C COUNT 三方法原文**——`countPolicy_belowKDoesNotSettleOrSignal` L442-453、`countPolicy_failureDoesNotCountTowardK` L457-468、`countPolicy_reachesKSettlesOnceAndLateNeverRetouchesUsedResults` L472-512 + `childAction` helper L946-966 工具导出（源 SHA af054384…与回执07 快照一致），关联本轮 process XML `TEST-…ChildOrchestrationServiceTest.xml` 26/0 同快照，未重跑；waitingBatch 直接引用回执07 原件。**P2-07b-L gitlink 三层指针与来源**（只读）——HEAD tree=index：Server `7fffef9c`/Web `4333145a`（已提交）；worktree：Server `683116d`（本批次 docs 后未暂存）/Web `4333145a`；值变链：Web `7af86f24` 由 a3bc61b9（10-08 20:30）引入、Server `78495dc` 由 8e9d0ac7（10-08 21:13）引入，**首次离开旧固定值=9b9ed673（10-11 15:58，回执06 执行批次提交）Server→7fffef9c、Web→4333145a**（与回执06 文本"保持不修改"不一致，属执行侧批次带入，带入值为当时两子仓已推送 HEAD=事实正确指针；memory"可能涉及Owner此前提交"经 git 全历史核查不成立）；处置=不切指针、不改历史、摘要按事实纠偏（本条目+回执08），无需 Owner 新裁量。**P2-07b-S 当前权威与许可覆盖**——knowledge 四入口当前字段更新为回执08 链（本条目/session-handoff/features p64/architecture，均含**实际原行回读**附件）；**Owner 条件恢复安排准确入字段**：P62 功能 COMPLETED、性能就绪探索**待阶段Ⅱ经 Planner 确认 PASSED 后恢复**（当前未启动、未验证、策略默认 OFF）；**验证定义 v5 当前事实**：P64B 父流程定义当前发布版=v5（=v3 图原样恢复派发触发器，回执07 最小使能），v4"关闭新入口"为历史验证事实（不再描述"目前入口关闭"）；gitlink 结论如上。Server 功能清单焦点行滚动（docs 批次）；memory 受影响字段（唯一下一动作→Planner 验收回执08、gitlink 冲突段按事实纠正、P62 条件表述同步）。**本轮零业务代码改动、无服务启动、无对象重建、无测试重跑**（纯原文提取+文档收尾+只读 Git 核查）。**唯一下一动作 = Planner 按提示06 四子项客观矩阵独立验收回执08 与 phase2-08 证据包**；功能 47、46/22/22=90、ADV64、问题 57、P63=COMPLETED 不变；Executor 不写功能 PASSED/COMPLETED、不核销 P、不晋级基线。'''

# 1. current-status.md
p = WS + '/knowledge/current-status.md'
lines = rd(p)
assert lines[2].startswith(u'> **2026-10-11 P64 阶段Ⅱ回执07'), lines[2][:50]
lines[2] = u'> 【历史条目：2026-10-11 被上条回执08 条目覆盖入"当前值"部分；其 v5 使能与 384/77 更正继续有效。】' + lines[2][2:]
lines.insert(2, NEW_TOP)
wr(p, lines)
print('current-status OK')

# 2. session-handoff.md L3
p = WS + '/knowledge/session-handoff.md'
lines = rd(p)
for i, l in enumerate(lines):
    if l.startswith(u'> **当前任务覆盖值'):
        lines[i] = (u'> **当前任务覆盖值（2026-10-11：P64=IN_PROGRESS·阶段Ⅰ PASSED（规划验收08）·阶段Ⅱ VERIFYING（回执08 提交待规划独立验收）；P63=COMPLETED（规划已确认，2026-10-08）；正式功能 47、项目总数统一 47）**：[复审07](../product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-07.md)关闭乙本人办理/委托原摘录/最终process报告/自身父进程观察后，剩余 4 子ID已按唯一执行入口[提示06](../product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-06.md)收口并提交[回执08](../product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-08.md)（证据树 [phase2-08](../product/p64-mes-advanced-orchestration/receipts/evidence/phase2-08/INDEX.md)）：**T** 根锁生产事务组合证明（onTaskActionCompleted L119 @Transactional REQUIRED=调用方事务、BpmInstanceMapper L101-102 FOR UPDATE 锁SQL、dispatch L340 freezeBatch 先于 L347 item 循环→L159 抛 2447→catch L358 登记 exec.errorText 落库不静默；直接断言=四case never insert(batch/item)/signal（26/0）+竞争1允1拒/sum1000/batchCount2（1/0），未夸称 item/intent 直接计数）；**C** COUNT 三方法+childAction helper 原文导出（源 af054384…，L442-453/L457-468/L472-512/L946-966）↔本轮 XML 26/0 同快照，未重跑；**L** gitlink 三层只读核实：HEAD tree=index=Server 7fffef9c/Web 4333145a（已提交）、worktree Server 683116d 未暂存；首次离开旧固定值 78495dc/7af86f24=9b9ed673（10-11 15:58 回执06 执行批次带入，值为当时已推送 HEAD=事实正确；Owner 手动提交经查不成立）；不切指针、不改历史，摘要按事实纠偏；**S** 本行/current-status/features/architecture 当前字段=回执08 链+实际原行回读附件；Owner 条件恢复=P62 功能 COMPLETED、性能就绪探索**待阶段Ⅱ Planner 确认 PASSED 后**恢复（当前未启动、策略 OFF）；验证定义当前=**v5**（v4 关闭新入口为历史事实）；Server 功能清单焦点滚动（docs 批次）。门禁：本轮零业务代码改动、无服务启动、无测试重跑（纯原文+文档+只读 Git）。功能 47、清单 ✅46/🟦22/⬜22、ADV64、问题 57、P63=COMPLETED 保持；**唯一下一动作 = Planner 按提示06 四子项矩阵独立验收回执08 与 phase2-08 证据包**（Executor 不写功能 PASSED/COMPLETED、不核销 P、不晋级基线）。历史详情：`knowledge/current-status.md`（回执07 条目已历史化）、`knowledge/history/`。')
        print('session-handoff L%d OK' % (i + 1))
        break
else:
    raise AssertionError('handoff top line not found')
wr(p, lines)

# 3. features p64 L17 当前状态行 + L26 当前规划复核行
p = WS + '/knowledge/features/p64-mes-advanced-orchestration.md'
lines = rd(p)
i17 = next(i for i, l in enumerate(lines) if l.startswith(u'| 当前状态 |'))
lines[i17] = u'| 当前状态 | **VERIFYING（阶段Ⅱ回执08 提交待规划独立验收，2026-10-11）**——[复审07](../../product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-07.md)关闭乙本人办理/委托原摘录/最终process报告/自身父进程观察后，剩余 4 子ID按[提示06](../../product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-06.md)收口并提交[回执08](../../product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-08.md)（证据树 phase2-08）：**P2-06a-C-T** 根锁生产事务与拒绝登记边界（组合证明）——生产事务入口 `TriggerExecutionService.onTaskActionCompleted` L119 `@Transactional(REQUIRED)`（freezeBatch 无方法级事务注解，锁与检查-插入在调用方事务内持有至提交）、实际锁 SQL `BpmInstanceMapper` L101-102 `SELECT … FOR UPDATE`、拒绝路径 `dispatch` L340 freezeBatch 先于 L347 item 登记循环 → `ChildOrchestrationService` L159 抛 CHILD_CHAIN_OVER_LIMIT → catch L358 登记 `exec.errorText` → updateById 落库（可诊断留痕；被拒动作 item/intent 登记结构性不发生）；直接断言=四 case never insert(batch/item)/never signalWaitNode（26/0）+竞争 1允1拒/sum1000/batchCount2（1/0），未夸称 item/intent 直接计数断言（提示06 允许组合证明口径）。**P2-07b-G-C** COUNT 三方法+childAction helper 原文导出（L442-453/L457-468/L472-512/L946-966，源 SHA af054384…）↔ 本轮 process XML 26/0 同快照，未重跑；waitingBatch 引用回执07 原件。**P2-07b-L** gitlink 三层指针只读核实（HEAD tree=index=Server 7fffef9c/Web 4333145a 已提交；worktree Server 683116d 未暂存）+值变链（7af86f24←a3bc61b9 10-08、78495dc←8e9d0ac7 10-08、**9b9ed673 10-11 15:58 回执06 执行批次首次离开旧固定值**，带入值=当时已推送 HEAD；Owner 手动提交经查不成立）；不切指针、不改历史、摘要按事实纠偏。**P2-07b-S** knowledge 四入口当前字段=回执08 链+实际原行回读；**Owner 条件恢复安排**：P62 功能 COMPLETED、性能就绪探索待阶段Ⅱ经 Planner 确认 PASSED 后恢复（当前未启动、策略 OFF）；**验证定义当前=v5**（=v3 图原样恢复派发触发器；v4"关闭新入口"为历史验证事实）；Server 功能清单焦点滚动（docs 批次）。本轮零业务代码改动、无服务启动、无测试重跑。原回执07 轮描述见下段历史 |'
i26 = next(i for i, l in enumerate(lines) if l.startswith(u'| 当前规划复核 |'))
lines[i26] = u'| 当前规划复核 | [复审07](../../product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-07.md)（2026-10-11，关闭四项、剩余4子ID：T/C/L/S）→ [提示06](../../product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-06.md)（唯一执行入口，替代提示05）→ [回执08](../../product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-08.md)（已提交待独立验收，证据树 phase2-08）；更早轮次（复审06/提示05/回执07、复审05/提示04/回执06 及之前）全部追溯化 |'
wr(p, lines)
print('features L%d/L%d OK' % (i17 + 1, i26 + 1))

# 4. architecture.md L259 P64 段
p = WS + '/knowledge/architecture.md'
lines = rd(p)
i = next(i for i, l in enumerate(lines) if u'当前活动业务功能=P64' in l)
old = lines[i]
lines[i] = lines[i].replace(
    u'阶段Ⅱ VERIFYING（2026-10-11 [复审06](product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-06.md) 裁决 5 个父ID精确剩余已按[提示05](product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-05.md) 逐项收敛并提交[回执07](product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-07.md) 待独立验收：乙本人成功办理+无权反向原件（v5=v3 图原样最小使能，v4 历史关闭事实不动）、委托链/根链源原文提取、最终 process 实测 384/77（回执06 386/78 残留件污染更正）；证据树 phase2-07、ADR-002 生效',
    u'阶段Ⅱ VERIFYING（2026-10-11 [复审07](product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-07.md) 后剩余 4 子ID按[提示06](product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-06.md)收口并提交[回执08](product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-08.md) 待独立验收：根锁生产事务组合证明（onTaskActionCompleted L119 @Transactional 调用方事务+mapper FOR UPDATE 锁SQL+dispatch 拒绝先于 item 登记→errorText 落库）、COUNT 三方法原文、gitlink 三层指针与值变链核实（9b9ed673 执行批次带入，非 Owner 提交）、当前权威原行回读；验证定义当前发布版=**v5**（v4 关闭新入口为历史事实）；证据树 phase2-08、ADR-002 生效')
assert lines[i] != old
print('architecture L%d OK' % (i + 1))
wr(p, lines)
print('ALL KNOWLEDGE EDIT DONE')

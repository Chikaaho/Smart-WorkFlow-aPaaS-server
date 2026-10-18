# -*- coding: utf-8 -*-
# P64 回执07 P2-07b：knowledge/memory/Server 清单当前字段精确更新
import io, re

WS = 'E:/code/Smart-WorkFlow-Agent-Workspace'

def rd(p):
    with io.open(p, 'r', encoding='utf-8') as f:
        return f.read().split('\n')

def wr(p, lines):
    with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines))

NEW_TOP_STATUS = u'''> **2026-10-11 P64 阶段Ⅱ回执07 提交，待规划独立验收（阶段Ⅰ=PASSED（验收08）、P64=IN_PROGRESS、阶段Ⅱ=VERIFYING、阶段Ⅲ未验收）**——Executor 按[复审06](product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-06.md)裁决与唯一执行入口[提示05](product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-05.md)收口 5 个父ID精确剩余，追加[回执07](product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-07.md)（证据树 [phase2-07](product/p64-mes-advanced-orchestration/receipts/evidence/phase2-07/INDEX.md)）。**处置要点**：**P2-04a 乙本人真实成功办理原件**——新最小派发对（父 def 发布 **v5=v3 图原样**恢复 trg_child 触发器为最小使能，v4 历史关闭事实/零意图原证不动，v4 中途实例按 v4 原义复核 APPROVED）：乙（2108817587316060161）以自身会话读自己被派发记录（create_by=update_by=owner_id=乙，SQL 原件）→ 授权来源行合法完整载荷 → `POST /workflow/commands/tasks/8a6709e7/complete` **HTTP 200 code0** → 命令 COMPLETED → 子实例 APPROVED、批次项 WRITTEN、批次 SETTLED 2/2（`raw/live/07-yi-success.json`；2426 失败原件保留未由甲替代）；**干净反向**：乙对甲**活跃**任务命令执行 **FAILED「无权处理该任务」**、甲任务保持活跃、甲随后自身完成 code0（无业务效果；前两次尝试因命令并发/分发延迟"节点已被处理"失败均留痕不当权限判别）；本轮无浏览器会话（无新 UI 主张，像素为回执06 锁定项）。**P2-05b-A** 委托链 5 目标方法+4 配置 case+2 helper **源原文工具导出**落 product（路径:行号/SHA/提取命令/时点 ↔ 锁定 XML testcase 9/0+5/0 关联，不重跑）。**P2-06a-C** 四计数 case/rootBatch/waitingBatch/竞争方法/根锁事务边界（freezeBatch L145 FOR UPDATE 同一事务）**源原文提取** ↔ 3341de5 当前源 SHA ↔ 修复前后竞争 XML 原证 + 本轮 process 新鲜报告身份（不重跑竞争实验）。**P2-07b-G** 最终 process **实测复算 exit0/BUILD SUCCESS/384/0/0/0（77 XML，HEAD 7fffef9，2026-10-11T16:30+0800）**——回执06"386/78"系旧残留件 TaskActionCommandHandlerSerializationTest（2 用例，10-09）污染求和，**更正 384/77、382→384=+2**；锚 8 类 56/0 逐类 XML 身份/SHA/时点（r04 原件幸存）关联、Nested 27/0 新鲜报告关联、COUNT helper 原行；转录更正表（证据树 72 文件/71 条目全 MATCH/MANIFEST 实含 INDEX、d594663 实为 **11 文件**、像素03 对象=d08fa98e/033878c2）；回执06 已登记 mvn 父 40132/后端 42176/vite 6608/3924 当前**已结束** tasklist 实际观察（只查不杀）+8080/5173 零 LISTENING；本轮自身收尾 taskkill 实际输出+零 LISTENING+Owner PG 未动。**P2-07b** 本条目/session-handoff/features/architecture 权威字段更新+Server 功能清单焦点行滚动（docs 批次）+memory 受影响字段（唯一下一动作→Planner 验收回执07）+三仓批次 Git/远端回读/gitlinks 保持。**门禁（本轮实跑）**：process **77 XML/384/0 exit0**（唯一受影响模块补证；engine 114/0、system-biz 365/0、form-biz 178/0、Web 四门为回执06 锁定不重跑；**本轮零业务代码改动**，Server 仅 docs 批次）。**限制（如实）**：v5 发布后当前定义恢复派发触发器语义（P2-04a 最小使能，登记状态变化）；设计内同批写回版本冲突（甲先写回）出现 3 次均经 admin retry→SETTLED 收敛，为 r04/r06 同款机制非新缺陷。**唯一下一动作 = Planner 按提示05 客观矩阵独立验收回执07 与 phase2-07 证据包**；功能 47、46/22/22=90、ADV64、问题 57、P63=COMPLETED、P62 延期/策略 OFF 不变；Executor 不写功能 PASSED/COMPLETED、不核销 P、不晋级基线。'''

# 1. current-status.md
p = WS + '/knowledge/current-status.md'
lines = rd(p)
assert lines[2].startswith(u'> **2026-10-11 P64 阶段Ⅱ回执06'), lines[2][:60]
lines[2] = u'> 【历史条目：2026-10-11 被上条回执07 条目覆盖入"当前值"部分。其中 process 386/78 与"净+5"表述经回执07 实测更正为 384/77（+2），以新条为准。】' + lines[2][2:]
lines.insert(2, NEW_TOP_STATUS)
wr(p, lines)
print('current-status OK')

# 2. session-handoff.md（L3 当前任务覆盖值整行替换）
p = WS + '/knowledge/session-handoff.md'
lines = rd(p)
for i, l in enumerate(lines):
    if l.startswith(u'> **当前任务覆盖值'):
        old = l
        lines[i] = (u'> **当前任务覆盖值（2026-10-11：P64=IN_PROGRESS·阶段Ⅰ PASSED（规划验收08）·阶段Ⅱ VERIFYING（回执07 提交待规划独立验收）；P63=COMPLETED（规划已确认，2026-10-08）；正式功能 47、项目总数统一 47）**：[复审06](../product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-06.md)裁决 5 个父ID精确剩余已按唯一执行入口[提示05](../product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-05.md)逐项收敛并提交[回执07](../product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-07.md)（证据树 [phase2-07](../product/p64-mes-advanced-orchestration/receipts/evidence/phase2-07/INDEX.md)）：**P2-04a** 乙本人真实成功办理原件（新最小派发对+v5=v3 图原样发布恢复派发触发器为最小使能，v4 历史关闭事实不动；record create_by=update_by=owner_id=乙、合法载荷 code0→命令 COMPLETED→子实例 APPROVED/批次 SETTLED 2/2；2426 失败原件保留未由甲替代）+干净反向（乙对甲活任务命令 FAILED「无权处理该任务」、甲保持活跃、甲随后自身 code0；两次"节点已被处理"先试留痕不当权限判别）；**P2-05b-A** 5 目标方法+4 配置 case+2 helper 源原文工具导出落 product（路径:行号/SHA/命令/时点↔锁定 XML 9/0+5/0，不重跑）；**P2-06a-C** 四 case/rootBatch/waitingBatch/竞争方法/根锁事务边界源原文↔3341de5 源 SHA↔修复前后竞争 XML+本轮新鲜报告（不重跑竞争）；**P2-07b-G** 最终 process 实测 exit0/**384/0（77 XML）**（回执06"386/78"=旧残留件 2 用例污染求和，更正+2）+锚 8 类 56/0 逐类 XML 身份/SHA/时点+Nested 27/0 新鲜报告+COUNT helper 原行+转录更正表（72 文件/71 条目全 MATCH 含 INDEX、d594663=11 文件、像素03=d08fa98e/033878c2）+回执06 进程 40132/42176/6608/3924 已结束 tasklist 实际观察+零 LISTENING；**P2-07b** 本行/current-status/features/architecture 纠偏+Server 功能清单焦点滚动+memory 唯一下一动作→Planner 验收回执07+三仓 Git/远端回读/gitlinks 保持。门禁：process 77XML/384/0 exit0（唯一受影响模块补证；其余回执06 锁定不重跑；本轮零业务代码改动）。限制（如实）：v5 后当前定义恢复触发器语义（登记）；设计内同批冲突×3 经 retry→SETTLED（r04/r06 同款）；本轮无浏览器会话（无新 UI 主张）。门禁、Git 与功能 47、清单 ✅46/🟦22/⬜22、ADV64、问题 57、P63/P62 值保持；**唯一下一动作 = Planner 按提示05 客观矩阵独立验收回执07 与 phase2-07 证据包**（Executor 不写功能 PASSED/COMPLETED、不核销 P、不晋级基线）。历史详情：`knowledge/current-status.md`（回执06 条目已历史化）、`knowledge/history/`。')
        print('session-handoff L%d OK (old %d chars)' % (i + 1, len(old)))
        break
else:
    raise AssertionError('handoff top line not found')
wr(p, lines)

# 3. features/p64 L17 当前状态行替换
p = WS + '/knowledge/features/p64-mes-advanced-orchestration.md'
lines = rd(p)
newrow = u'| 当前状态 | **VERIFYING（阶段Ⅱ回执07 提交待规划独立验收，2026-10-11）**——[复审06](../../product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-06.md)裁决 5 个父ID精确剩余已按[提示05](../../product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-05.md)逐项收敛并提交[回执07](../../product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-07.md)（证据树 phase2-07）：**P2-04a** 乙本人真实成功办理原件——父流程 def 发布 v5（=v3 图原样恢复 trg_child 触发器，P2-04a 最小使能；v4 历史关闭事实/零意图原证/v3 冻结行不动，v4 中途实例按 v4 原义复核 APPROVED）；新派发对乙记录 create_by=update_by=owner_id=乙（SQL 原件）、乙以合法完整载荷（本人授权来源行）code0→命令 COMPLETED→子实例 APPROVED、批次 SETTLED 2/2、回执06 的 2426 失败原件保留未由甲替代；干净反向=乙对甲**活跃**任务命令 FAILED「无权处理该任务」+甲任务保持活跃+甲随后自身 code0（两次先试因命令并发/分发延迟"节点已被处理"留痕，不当权限判别）；本轮无浏览器会话（无新 UI 主张）。**P2-05b-A** 委托链 5 目标方法+4 配置 case+2 helper **源原文工具导出**落 product（路径:行号/SHA/提取命令/时点 ↔ 锁定 XML testcase 9/0+5/0，不重跑、源文件 SHA 与回执05 运行身份一致）。**P2-06a-C** 四计数 case/rootBatch/waitingBatch/竞争方法/根锁事务边界（freezeBatch L145 FOR UPDATE 同一事务持有至提交）源原文 ↔ 3341de5 当前源 SHA ↔ 修复前(1001)/修复后(1允1拒/总额1000)竞争 XML 原证 + 本轮 process 新鲜报告身份（四 case 26/0、竞争 1/0；不重跑竞争实验、H2 层级如实）。**P2-07b-G** 最终 process 实测复算 exit0/BUILD SUCCESS/**384/0/0/0（77 XML，HEAD 7fffef9）**——回执06"386/78 净+5"系旧残留件 TaskActionCommandHandlerSerializationTest（2 用例，10-09 mtime）污染求和，**更正为 384/77、382→384=+2**；锚 8 类 56/0 逐类 XML 身份/SHA/时点关联（r04 幸存原件）、Nested 27/0 新鲜报告、COUNT helper 原行；转录更正表（证据树 72 文件/71 条目全 MATCH/MANIFEST 实含 INDEX、d594663 实为 11 文件、像素03 对象=d08fa98e/033878c2）；回执06 登记的 mvn 父 40132/后端 42176/vite 6608/3924 当前已结束 tasklist 实际观察+8080/5173 零 LISTENING+本轮自身收尾（taskkill 实际输出/零监听/Owner PG 未动）。**P2-07b** knowledge 四入口纠偏+Server 功能清单焦点行滚动+memory 唯一下一动作更新+三仓批次 Git/远端回读/gitlinks 保持。门禁（本轮实跑）：process 77XML/384/0 exit0（唯一受影响模块补证；engine 114/0、system-biz 365/0、form-biz 178/0、Web 四门为回执06 锁定不重跑；本轮零业务代码改动）。原回执06 轮描述见下段历史 |'
assert lines[16].startswith(u'| 当前状态 |'), lines[16][:40]
lines[16] = newrow
wr(p, lines)
print('features OK')

# 4. architecture.md L259
p = WS + '/knowledge/architecture.md'
lines = rd(p)
for i, l in enumerate(lines):
    if u'当前活动业务功能=P64' in l:
        old = l
        lines[i] = l.replace(
            u'阶段Ⅱ VERIFYING（2026-10-11 [复审05](product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-05.md) 七项精确剩余账本已按[提示04](product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-04.md) 逐项独立证据包收敛并提交[回执06](product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-06.md) 待独立验收；证据树 phase2-06、ADR-002 生效',
            u'阶段Ⅱ VERIFYING（2026-10-11 [复审06](product/p64-mes-advanced-orchestration/receipts/planning-review-phase-2-06.md) 裁决 5 个父ID精确剩余已按[提示05](product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-05.md) 逐项收敛并提交[回执07](product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-07.md) 待独立验收：乙本人成功办理+无权反向原件（v5=v3 图原样最小使能，v4 历史关闭事实不动）、委托链/根链源原文提取、最终 process 实测 384/77（回执06 386/78 残留件污染更正）；证据树 phase2-07、ADR-002 生效')
        assert lines[i] != old
        print('architecture L%d OK' % (i + 1))
        break
else:
    raise AssertionError('architecture line not found')
wr(p, lines)

# 5. memory/state.md L5 唯一下一动作
p = WS + '/memory/state.md'
lines = rd(p)
for i, l in enumerate(lines):
    if u'唯一下一动作：Executor按[提示05]' in l:
        lines[i] = l.replace(
            u'唯一下一动作：Executor按[提示05](../product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-05.md)收口5个父ID精确剩余，追加回执07。',
            u'唯一下一动作：Planner按[提示05](../product/p64-mes-advanced-orchestration/receipts/planning-execution-prompt-p64-phase2-05.md)客观矩阵独立验收[回执07](../product/p64-mes-advanced-orchestration/receipts/phase-2-completion-receipt-07.md)与phase2-07证据包。')
        print('state L%d OK' % (i + 1))
        break
else:
    raise AssertionError('state next-action line not found')
wr(p, lines)
print('ALL EDIT DONE')

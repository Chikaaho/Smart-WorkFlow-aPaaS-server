// P64 回执04：方法边界摘录（花括号配平；源文件路径:行区间）
const fs = require('fs');
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-04/raw';

function extract(file, methods) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  const out = [];
  for (const name of methods) {
    const re = new RegExp('\\b' + name + '\\s*\\(');
    let idx = -1;
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i];
      if (re.test(l) && (l.includes('void ') || l.includes('Optional') || l.includes('private ') || l.includes('public ') || l.includes('record '))) {
        idx = i;
        break;
      }
    }
    if (idx < 0) { out.push('// MISSING: ' + name + ' @ ' + file); continue; }
    let depth = 0; let started = false; let end = idx;
    for (let i = idx; i < lines.length; i++) {
      for (const ch of lines[i]) { if (ch === '{') { depth++; started = true; } else if (ch === '}') { depth--; } }
      if (started && depth === 0) { end = i; break; }
    }
    out.push('// ' + file + ':' + (idx + 1) + '-' + (end + 1) + '\n' + lines.slice(idx, end + 1).join('\n'));
  }
  return out.join('\n\n');
}

const parts = [];
parts.push('# P64 回执04 新增/关键用例方法边界摘录（花括号配平；源文件路径:行区间）\n');
parts.push(extract('sw-biz/sw-bpm/sw-bpm-engine/src/main/java/com/sw/ck/bpm/engine/facade/BpmTaskFacadeImpl.java', ['claimTask']));
parts.push(extract('sw-biz/sw-bpm/sw-bpm-process/src/main/java/com/sw/ck/bpm/process/service/ChildOrchestrationService.java', ['onWaitNodeArrival', 'signalIfNoOpenBatches']));
parts.push(extract('sw-biz/sw-bpm/sw-bpm-api/src/main/java/com/sw/ck/bpm/api/orchestration/SubflowWaitPort.java', ['onWaitNodeArrival']));
parts.push(extract('sw-biz/sw-bpm/sw-bpm-process/src/test/java/com/sw/ck/bpm/process/service/ChildOrchestrationServiceTest.java', [
  'countPolicy_belowKDoesNotSettleOrSignal',
  'countPolicy_failureDoesNotCountTowardK',
  'countPolicy_reachesKSettlesOnceAndLateNeverRetouchesUsedResults',
  'nonePolicy_settleChannelThroughRealServiceAfterItemsRegistered',
  'nonePolicy_lateFeedbackVersionedTraceOnly',
  'cancelCapturesRealTerminalStateValues',
  'terminalOnCancelledBatchLosesRightsAndNewRoundUnaffected',
  'rowWritebackFollowsStableRowIdentityRegardlessOfSubmissionOrder',
  'duplicateRowInResultWritesBackOncePerRow',
  'staleRoundResultDoesNotOverwriteCurrentRoundWriteback',
  'duplicateTerminalCallbackDoesNotDoubleWriteback',
  'rootChainOverLimitRejectsFreezeWithoutBatchOrIntents',
  'hardNestingCapEightBlocksEvenWithRaisedConfig',
]));
parts.push(extract('sw-biz/sw-bpm/sw-bpm-process/src/test/java/com/sw/ck/bpm/process/service/TriggerExecutionServiceChildDispatchTest.java', ['childDispatchOverCapRejectedWithoutFreezeOrIntents']));
parts.push(extract('sw-biz/sw-biz-system/sw-biz-system-biz/src/test/java/com/sw/ck/system/service/impl/PositionDelegateFacadeImplTest.java', ['chainOverFourHopsRejectedAtRuntime', 'crossTenantTargetPostRejectedAtRuntime', 'runtimeCycleDetectedInWalkChain']));
parts.push(extract('sw-biz/sw-biz-system/sw-biz-system-biz/src/test/java/com/sw/ck/system/service/impl/PositionDelegateServiceImplTest.java', ['chainOverFourHopsRejectedAtConfig']));
parts.push(extract('sw-biz/sw-bpm/sw-bpm-engine/src/test/java/com/sw/ck/bpm/engine/participant/PostDelegateParticipantResolverTest.java', ['facadeFailurePropagatesSoNodeDoesNotAdvance']));
parts.push(extract('sw-bootstrap/src/test/java/com/sw/ck/bootstrap/p64/P64RollbackBoundaryPostgresTest.java', ['rollbackToLegacyMigrationSetFailsValidateNotSilentlyPass']));
fs.writeFileSync(EV + '/unit/04-assert-new-key-methods.txt', parts.join('\n'), 'utf8');
console.log('excerpts bytes=' + fs.statSync(EV + '/unit/04-assert-new-key-methods.txt').size);

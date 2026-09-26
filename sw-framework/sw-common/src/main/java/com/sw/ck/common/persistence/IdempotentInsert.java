package com.sw.ck.common.persistence;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 幂等插入的保存点包装。
 *
 * <p>背景（真实 PostgreSQL 行为）：唯一键冲突的语句失败会**中止整个事务**
 * （{@code 25P02 current transaction is aborted}），此后同一事务内的任何语句都会被拒绝。
 * 因此"捕获 DuplicateKeyException 后继续走降级分支"在 PostgreSQL 上并不成立——
 * 捕获之后的事务已经不可用。本包装把插入放进 SAVEPOINT（{@code PROPAGATION_NESTED}）：
 * 冲突只回滚到保存点，外层业务事务保持可用，幂等降级分支才真正可执行。</p>
 *
 * <p>语义约束：嵌套事务仅提供"失败隔离"，提交仍由外层事务决定——业务回滚时插入同样回滚，
 * 不产生孤儿记录。</p>
 */
public final class IdempotentInsert {

    private IdempotentInsert() {
    }

    /**
     * 在保存点内执行插入。
     *
     * @param transactionManager 事务管理器；缺失时退化为直接执行（无事务上下文场景）
     * @param insert             插入动作
     */
    public static void execute(PlatformTransactionManager transactionManager, Runnable insert) {
        if (transactionManager == null || !TransactionSynchronizationManager.isActualTransactionActive()) {
            // 无事务上下文：语句级失败不会污染其他语句，直接执行
            insert.run();
            return;
        }
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        nested.executeWithoutResult(status -> insert.run());
    }
}

package com.sw.ck.bpm.process.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.bpm.process.entity.BpmResourceUsage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 资源占用计数 Mapper（原子条件更新；并发受理竞争安全的额度真源加速器）。
 * <p>
 * 条件更新（{@code outstanding + units <= cap}）在行锁粒度（语句级）内完成
 * 「检查+占位」，不以读取-判断-写回扩大竞争窗口；失败返回 0 行由调用方补偿或拒绝。
 * 权威事实为命令/批次项/引擎队列持久行，本计数漂移由资源对账按事实修复。
 * </p>
 */
public interface BpmResourceUsageMapper extends BaseMapper<BpmResourceUsage> {

    /**
     * 原子占用：不超过容量上限时占用并返回 1，超限或行不存在返回 0。
     */
    @Update("UPDATE sw_bpm_resource_usage "
            + "SET outstanding = outstanding + #{units}, version = version + 1 "
            + "WHERE scope = #{scope} AND scope_key = #{scopeKey} AND segment = #{segment} "
            + "AND outstanding + #{units} <= #{cap}")
    int incrementWithinCap(@Param("scope") String scope, @Param("scopeKey") long scopeKey,
                           @Param("segment") String segment, @Param("units") int units,
                           @Param("cap") long cap);

    /**
     * 释放/补偿：按单位数扣减，钳制在 0（计数只能因缺陷漂移，不允许负值掩盖漂移）。
     */
    @Update("UPDATE sw_bpm_resource_usage "
            + "SET outstanding = CASE WHEN outstanding >= #{units} THEN outstanding - #{units} ELSE 0 END, "
            + "version = version + 1 "
            + "WHERE scope = #{scope} AND scope_key = #{scopeKey} AND segment = #{segment}")
    int decrement(@Param("scope") String scope, @Param("scopeKey") long scopeKey,
                  @Param("segment") String segment, @Param("units") long units);

    /**
     * 对账修复（乐观 CAS）：仅当计数仍等于读取值时改写为事实值；
     * 并发受理使读取值失效时放弃本轮修复（下一轮重估），不覆盖新鲜占用。
     */
    @Update("UPDATE sw_bpm_resource_usage "
            + "SET outstanding = #{fact}, version = version + 1 "
            + "WHERE scope = #{scope} AND scope_key = #{scopeKey} AND segment = #{segment} "
            + "AND outstanding = #{expected}")
    int casRepair(@Param("scope") String scope, @Param("scopeKey") long scopeKey,
                  @Param("segment") String segment, @Param("fact") long fact,
                  @Param("expected") long expected);

    /**
     * 空闲探测（借用对方保留段前调用）：行被并发事务持有时返回 null（SKIP LOCKED 跳过，
     * 不等待、不污染调用方事务——PG 的 nowait 失败会中止事务，skip locked 不报错）；
     * 返回非空即表示本事务已持有该行锁，随后条件占用不再等待。
     * <p>「借用只在段空闲时发生，需求返回时停止新增借用」要求借用尝试不可阻塞：跨类别借用与
     * 对方在途事务互相等待会在共享段/总量饱和时成环（RA02a1 死锁），且让受保护类别排在
     * 借用队列之后。段内条件更新失败不取锁（PG 在 qual 不通过时不加行锁），跳过后无残留。</p>
     */
    @Select("SELECT id FROM sw_bpm_resource_usage WHERE scope = #{scope} AND scope_key = #{scopeKey} "
            + "AND segment = #{segment} FOR UPDATE SKIP LOCKED")
    Long probeIdleRow(@Param("scope") String scope, @Param("scopeKey") long scopeKey,
                      @Param("segment") String segment);

    /** 惰性建立计数行（幂等；唯一键 uk_sw_bpm_resource_usage_scope 保证并发安全，
     *  冲突时调用方按 DuplicateKeyException 忽略——PG/H2 双方言可移植写法）。 */
    @Insert("INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, segment, outstanding) "
            + "VALUES (#{id}, 0, #{scope}, #{scopeKey}, #{segment}, 0)")
    int insertNew(@Param("id") long id, @Param("scope") String scope,
                  @Param("scopeKey") long scopeKey, @Param("segment") String segment);
}

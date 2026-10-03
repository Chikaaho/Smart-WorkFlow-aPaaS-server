package com.sw.ck.bpm.process.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.bpm.process.entity.BpmResourceUsage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
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
     * 行锁（规范序预锁用）：锁定并返回计数行 id；行不存在时返回 null（不产生锁）。
     * <p>用途：段占位回退到保留段前按规范顺序取锁，消除「PROD 先锁生产保留 / OA 先锁 OA 保留」
     * 的交叉锁序（共享段饱和时并发受理死锁的根因）。</p>
     */
    @org.apache.ibatis.annotations.Select("SELECT id FROM sw_bpm_resource_usage "
            + "WHERE scope = #{scope} AND scope_key = #{scopeKey} AND segment = #{segment} "
            + "FOR UPDATE")
    Long lockRow(@Param("scope") String scope, @Param("scopeKey") long scopeKey,
                 @Param("segment") String segment);

    /** 惰性建立计数行（幂等；唯一键 uk_sw_bpm_resource_usage_scope 保证并发安全，
     *  冲突时调用方按 DuplicateKeyException 忽略——PG/H2 双方言可移植写法）。 */
    @Insert("INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, segment, outstanding) "
            + "VALUES (#{id}, 0, #{scope}, #{scopeKey}, #{segment}, 0)")
    int insertNew(@Param("id") long id, @Param("scope") String scope,
                  @Param("scopeKey") long scopeKey, @Param("segment") String segment);
}

package com.sw.ck.bpm.process.mapper;

import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.common.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 流程业务命令受理 Mapper。
 */
@Mapper
public interface BpmCommandMapper extends BaseMapperX<BpmCommand> {

    /**
     * 行锁读取命令领取权字段（P62 分级执行：业务事务内校验执行权，防止旧执行者提交效果）。
     * 事务内 FOR UPDATE：与 stale 回收/新领取的 UPDATE 互斥，效果提交与领取权校验同事务生效。
     */
    @Select("SELECT id, status, claim_token, tenant_id FROM sw_bpm_command WHERE id = #{id} FOR UPDATE")
    BpmCommand lockLeaseById(@Param("id") Long id);
}

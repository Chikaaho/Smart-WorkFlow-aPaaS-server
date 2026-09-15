package com.sw.ck.bpm.process.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.bpm.process.entity.BpmProcessDefVersion;
import org.apache.ibatis.annotations.Mapper;

/** 流程定义发布版本 Mapper。PUBLISHED 行任何 UPDATE 仅为状态迁移，不覆盖冻结内容。 */
@Mapper
public interface BpmProcessDefVersionMapper extends BaseMapper<BpmProcessDefVersion> {
}

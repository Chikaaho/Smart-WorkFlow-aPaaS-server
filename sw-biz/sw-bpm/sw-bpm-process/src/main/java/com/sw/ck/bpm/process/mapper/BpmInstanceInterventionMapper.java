package com.sw.ck.bpm.process.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.bpm.process.entity.BpmInstanceIntervention;
import org.apache.ibatis.annotations.Mapper;

/** BpmInstanceIntervention Mapper（I4 实例级干预审计）。 */
@Mapper
public interface BpmInstanceInterventionMapper extends BaseMapper<BpmInstanceIntervention> {
}

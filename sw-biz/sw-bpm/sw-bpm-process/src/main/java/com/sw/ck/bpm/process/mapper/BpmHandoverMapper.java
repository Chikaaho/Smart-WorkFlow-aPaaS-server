package com.sw.ck.bpm.process.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.bpm.process.entity.BpmHandover;
import org.apache.ibatis.annotations.Mapper;

/** BpmHandover Mapper（I4 流程交接）。 */
@Mapper
public interface BpmHandoverMapper extends BaseMapper<BpmHandover> {
}

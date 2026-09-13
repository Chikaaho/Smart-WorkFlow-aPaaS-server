package com.sw.ck.bpm.process.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.bpm.process.entity.BpmHandoverItem;
import org.apache.ibatis.annotations.Mapper;

/** BpmHandoverItem Mapper（I4 流程交接明细）。 */
@Mapper
public interface BpmHandoverItemMapper extends BaseMapper<BpmHandoverItem> {
}

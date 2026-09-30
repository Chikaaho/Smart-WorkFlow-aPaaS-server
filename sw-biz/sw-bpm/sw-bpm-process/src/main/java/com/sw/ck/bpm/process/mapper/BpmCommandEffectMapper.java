package com.sw.ck.bpm.process.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.bpm.process.entity.BpmCommandEffect;
import org.apache.ibatis.annotations.Mapper;

/**
 * 命令效果权威账本 Mapper（P62 分级执行）。
 */
@Mapper
public interface BpmCommandEffectMapper extends BaseMapper<BpmCommandEffect> {
}

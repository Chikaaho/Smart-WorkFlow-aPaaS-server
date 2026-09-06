package com.sw.ck.bpm.process.mapper;

import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.common.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

/**
 * 流程业务命令受理 Mapper。
 */
@Mapper
public interface BpmCommandMapper extends BaseMapperX<BpmCommand> {
}

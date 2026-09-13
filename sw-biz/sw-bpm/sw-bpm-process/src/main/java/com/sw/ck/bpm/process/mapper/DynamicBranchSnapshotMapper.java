package com.sw.ck.bpm.process.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.bpm.process.entity.DynamicBranchSnapshot;
import org.apache.ibatis.annotations.Mapper;

/** DynamicBranchSnapshot Mapper（I4 动态并行分支冻结快照）。 */
@Mapper
public interface DynamicBranchSnapshotMapper extends BaseMapper<DynamicBranchSnapshot> {
}

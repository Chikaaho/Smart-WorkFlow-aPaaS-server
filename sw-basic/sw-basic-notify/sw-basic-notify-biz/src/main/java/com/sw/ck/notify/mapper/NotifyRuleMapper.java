package com.sw.ck.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.notify.entity.NotifyRule;
import org.apache.ibatis.annotations.Mapper;

/** NotifyRule Mapper。租户条件由 TenantLineHandler 自动注入。 */
@Mapper
public interface NotifyRuleMapper extends BaseMapper<NotifyRule> {
}

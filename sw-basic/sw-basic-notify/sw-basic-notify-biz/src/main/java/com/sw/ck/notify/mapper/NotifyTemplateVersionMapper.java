package com.sw.ck.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.notify.entity.NotifyTemplateVersion;
import org.apache.ibatis.annotations.Mapper;

/** NotifyTemplateVersion Mapper。租户条件由 TenantLineHandler 自动注入。 */
@Mapper
public interface NotifyTemplateVersionMapper extends BaseMapper<NotifyTemplateVersion> {
}

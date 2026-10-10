package com.sw.ck.system.service;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.system.entity.SysPostDelegate;

/**
 * P64 阶段Ⅱ（A07）岗位委托配置服务。
 * <p>
 * 配置管理员在授权组织/租户范围维护源岗位→受托岗位关系；自委托、循环、跨租户、
 * 越权范围、受托岗位无效与同优先级重叠在配置/生效时拒绝（可诊断）。
 * </p>
 */
public interface PositionDelegateService {

    /**
     * 分页查询委托关系。
     */
    PageResult<SysPostDelegate> page(PageParam pageParam, SysPostDelegate query);

    /**
     * 创建委托关系（含全量校验与生效链校验）。
     *
     * @return 新建关系 ID
     */
    Long create(SysPostDelegate delegate);

    /**
     * 更新委托关系（含全量校验与生效链校验）。
     */
    void update(SysPostDelegate delegate);

    /**
     * 启停委托关系（启用时重新执行同优先级重叠与循环校验）。
     */
    void changeStatus(Long id, String status);

    /**
     * 删除委托关系（逻辑删除）。
     */
    void delete(Long id);
}

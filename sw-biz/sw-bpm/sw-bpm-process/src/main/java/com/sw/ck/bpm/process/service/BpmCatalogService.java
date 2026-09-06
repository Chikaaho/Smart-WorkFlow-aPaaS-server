package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.dto.CatalogItemDTO;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;

import java.util.Map;

/** 流程中心事项目录服务（v0.0.2）。 */
public interface BpmCatalogService {

    /**
     * 普通视角：本人可见且已发布、存在 active 绑定且表单已发布的事项。
     * 分类聚合与搜索结果不泄漏不可见事项。
     *
     * @param categoryId 分类过滤（null=全部；0=未分类）
     */
    PageResult<CatalogItemDTO> listPortalItems(String keyword, Long categoryId, PageParam pageParam);

    /** 普通视角分类聚合数量（仅统计本人可见事项，不泄漏不可见集合）。 */
    Map<Long, Long> portalCategoryCounts();

    /** 管理视角：全部定义（含未发布），附表单发布状态与绑定状态。 */
    PageResult<CatalogItemDTO> listAdminItems(String keyword, Long categoryId, PageParam pageParam);

    /** 调整事项分类归属（categoryId=null 移入未分类）。 */
    void assignCategory(String processKey, Long categoryId);

    /** 普通视角事项详情：校验可见/发布/绑定后返回关联表单 formKey。 */
    CatalogItemDTO getPortalItem(String processKey);
}

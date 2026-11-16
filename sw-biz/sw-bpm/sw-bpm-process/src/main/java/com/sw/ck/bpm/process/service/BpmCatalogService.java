package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.vo.CatalogItemVO;
import com.sw.ck.bpm.process.vo.CategoryCountsVO;
import com.sw.ck.bpm.process.vo.CategoryVO;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;

import java.util.List;

/** 流程中心事项目录服务（v0.0.2）。 */
public interface BpmCatalogService {

    /**
     * 普通视角：本人可见且已发布、存在 active 绑定且表单已发布的事项。
     * 分类聚合与搜索结果不泄漏不可见事项。
     *
     * @param categoryId 分类过滤（null=全部；0=未分类）
     */
    PageResult<CatalogItemVO> listPortalItems(String keyword, Long categoryId, PageParam pageParam);

    /** 普通视角分类聚合数量（仅统计本人可见事项，不泄漏不可见集合）。 */
    CategoryCountsVO portalCategoryCounts();
    /** 普通视角分类列表（仅名称/排序，登录即可用；ID 以字符串下发避免 JS 精度丢失）。 */
    List<CategoryVO> portalCategories();


    /** 管理视角：全部定义（含未发布），附表单发布状态与绑定状态。 */
    PageResult<CatalogItemVO> listAdminItems(String keyword, Long categoryId, PageParam pageParam);

    /** 调整事项分类归属（categoryId=null 移入未分类）。 */
    void assignCategory(String processKey, Long categoryId);

    /** 普通视角事项详情：校验可见/发布/绑定后返回关联表单 formKey。 */
    CatalogItemVO getPortalItem(String processKey);
}

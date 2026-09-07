package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.dto.CategoryDTO;
import com.sw.ck.bpm.process.dto.CategorySaveReq;
import com.sw.ck.bpm.process.entity.BpmCategory;

import java.util.List;

/** 流程分类服务（v0.0.2 流程中心，单层）。 */
public interface BpmCategoryService {

    /** 分类列表（按 sortNo 升序、createTime 升序），附事项归属计数。 */
    List<CategoryDTO> listCategories();

    BpmCategory createCategory(CategorySaveReq req);

    BpmCategory updateCategory(Long id, CategorySaveReq req);

    /**
     * 删除分类。已有事项归属时拒绝（须先解除归属），幂等：不存在/已删返回成功。
     */
    void deleteCategory(Long id);
}

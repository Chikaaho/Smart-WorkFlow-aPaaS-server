package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.BpmProcessTemplate;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;

/**
 * 流程模板中心服务（I4 §3.2）。
 */
public interface BpmProcessTemplateService {

    /** 分页查询当前身份可见模板（scopeType=GLOBAL 或 scopeDeptId=本人部门；DISABLED 仅管理可见）。 */
    PageResult<BpmProcessTemplate> page(PageParam pageParam, String keyword,
                                        String category, String status);

    BpmProcessTemplate get(Long id);

    BpmProcessTemplate create(String name, String category, String description, String formKey,
                              String graphJson, String scopeType, Long scopeDeptId,
                              Long sourceDefId, Integer sourceDefVersion);

    BpmProcessTemplate update(Long id, String name, String category, String description,
                              String graphJson, String scopeType, Long scopeDeptId);

    /** 启用/停用（停用后不可再复制创建定义，历史派生定义不受影响）。 */
    BpmProcessTemplate changeStatus(Long id, boolean enabled);

    /**
     * 复制后编辑并发布路径的起点：以模板为受控来源创建真实流程定义（DRAFT），
     * 模板图全量覆盖定义草稿图，并在定义上登记溯源关系。
     */
    BpmProcessDef copyToDefinition(Long templateId, String name);

    void delete(Long id);
}

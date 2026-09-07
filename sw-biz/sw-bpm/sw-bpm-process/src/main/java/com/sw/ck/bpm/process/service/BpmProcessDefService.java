package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;

import java.util.List;

/**
 * 流程定义服务。
 */
public interface BpmProcessDefService {

    /**
     * 根据流程 key 查询流程定义。
     *
     * @param processKey 流程 key
     * @return 流程定义实体（可能为 null）
     */
    BpmProcessDef findByProcessKey(String processKey);

    /**
     * 创建流程定义（DRAFT 状态）。
     *
     * @param name    流程名称
     * @param formKey 绑定表单 formKey
     * @return 持久化后的实体（含生成的 process_key 与初始图）
     */
    BpmProcessDef createDef(String name, String formKey);

    /**
     * 修改流程定义基础字段（仅 DRAFT 状态允许；PUBLISHED 抛业务异常）。
     * <p>
     * name / formKey 均可选（null 表示不修改）；formKey 变更时校验表单存在，
     * 并同步更新 graph_json 中的 name / formKey，保持图与元数据一致。
     *
     * @param id        流程定义 ID
     * @param name      新名称（可 null）
     * @param formKey   新表单 formKey（可 null）
     * @return 更新后的实体
     */
    BpmProcessDef updateDef(Long id, String name, String formKey);

    /**
     * 保存草稿图 —— 无条件全量覆盖 graph_json，status 保持 DRAFT，不跑校验。
     *
     * @param id        流程定义 ID
     * @param graphJson 图 JSON 字符串
     */
    void saveDraftGraph(Long id, String graphJson);

    /**
     * 校验图（按 ID 读取定义中的图进行校验）。
     *
     * @param id 流程定义 ID
     * @return 校验错误列表，空列表表示通过
     */
    List<GraphValidationError> validateGraph(Long id);

    /**
     * 校验图（直接传入 ProcessGraph 进行校验）。
     *
     * @param graph 图对象
     * @return 校验错误列表，空列表表示通过
     */
    List<GraphValidationError> validateGraph(ProcessGraph graph);

    /**
     * 读取流程定义 + 图。
     *
     * @param id 流程定义 ID
     * @return 实体（含 graph_json）
     */
    BpmProcessDef getDef(Long id);

    /**
     * 分页查询流程定义列表（不含 graph_json 大字段）。
     *
     * @param pageParam 分页参数
     * @param formKey   可选，按绑定表单 formKey 精确过滤（为空不过滤）
     * @return 分页结果
     */
    PageResult<BpmProcessDef> listDefs(PageParam pageParam, String formKey);

    /**
     * 软删流程定义。
     *
     * @param id 流程定义 ID
     */
    void deleteDef(Long id);

    /**
     * 返回流程定义已部署的 BPMN XML 字符串。
     * <p>
     * 仅 {@code PUBLISHED} 状态且 {@code processDefinitionId} 非空的定义
     * 才允许获取；否则抛业务异常。
     * </p>
     *
     * @param id 流程定义 ID（主键）
     * @return 原始 BPMN XML 字符串
     */
    String getBpmnXml(Long id);

    /**
     * 发布流程定义（DRAFT → PUBLISHED）。
     * <p>
     * 发布流水线：
     * <ol>
     *   <li>读取定义 + 图</li>
     *   <li>发布门校验（图校验 + 表单已发布 + process_key 冻结）</li>
     *   <li>翻译为 BPMN（经 {@code BpmDeployFacade.translateToBpmn}）</li>
     *   <li>部署（经 {@code BpmDeployFacade.deployModel}）</li>
     *   <li>回填 deployment_id / process_definition_id</li>
     *   <li>状态转为 PUBLISHED</li>
     * </ol>
     * </p>
     * <p>
     * 事务一致性：部署与回填在同一 {@code @Transactional} 内，
     * 使用 Spring 事务管理器（Flowable + MyBatis-Plus 共用同一 DataSource）。
     * </p>
     *
     * @param id 流程定义 ID
     * @return 发布后的实体（含回填的 deployment_id / process_definition_id）
     */
    BpmProcessDef publish(Long id);
}

package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.dto.InstanceFilterDTO;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.service.BaseService;

import java.util.Optional;

/**
 * 流程实例记录 Service。
 *
 * <h3>状态变更</h3>
 * <ul>
 *   <li>{@link #updateStatus(String, String)} 仅更新 status 字段，不触及其他列。
 *       MyBatis-Plus 拦截器自动注入 {@code update_time / update_by}。</li>
     *   <li>调用方负责保证 status 值合法（见 {@link com.sw.ck.bpm.process.entity.InstanceStatusEnum}）。</li>
 * </ul>
 */
public interface BpmInstanceService extends BaseService<BpmInstance> {

    /**
     * 根据 Flowable 实例 ID 查询我方记录。
     *
     * @param processInstanceId Flowable 流程实例 ID
     * @return 流程实例记录（可能为空）
     */
    Optional<BpmInstance> findByProcessInstanceId(String processInstanceId);

    /**
     * 根据业务键（表单 recordId）查询我方记录。
     *
     * @param businessKey 业务键
     * @return 流程实例记录（可能为空）
     */
    Optional<BpmInstance> findByBusinessKey(String businessKey);

    /**
     * 更新流程实例状态。
     * <p>
     * 使用 MyBatis-Plus {@code lambdaUpdate()} 按 processInstanceId 精确匹配更新 status 列，
     * CommonMetaObjectHandler 自动注入 {@code update_time / update_by}。
     * </p>
     *
     * @param processInstanceId Flowable 流程实例 ID，不可为空
     * @param status            目标状态（{@link com.sw.ck.bpm.process.entity.InstanceStatusEnum#getCode()}）
     */
    void updateStatus(String processInstanceId, String status);

    /**
     * 分页查询流程实例列表。
     * <p>
     * 支持可选过滤条件：状态、流程定义 key、发起人。
     * 按创建时间倒序排列（最新的实例在最前）。
     * 只查当前租户（MyBatis-Plus 拦截器自动注入 tenant_id）。
     * </p>
     *
     * @param pageParam 分页参数（pageNum 从 1 开始，pageSize 为每页条数）
     * @param filter    过滤条件（所有字段可选，null = 不过滤对应字段）
     * @return 分页结果（records 为 BpmInstance 列表）
     */
    PageResult<BpmInstance> pageInstances(PageParam pageParam, InstanceFilterDTO filter);
}

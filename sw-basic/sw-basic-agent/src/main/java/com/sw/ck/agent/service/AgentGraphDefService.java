package com.sw.ck.agent.service;

import com.sw.ck.agent.dto.AgentGraphDefDTO;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;

/**
 * Agent 图定义 Service（CRUD + 草稿保存 + 发布，M07-F02 Step7）。
 * <p>
 * 纯存储+管理骨架，无任何执行语义；发布仅做最小发布门（图可解析 + elements 非空 +
 * graph_key 冻结检查），完整拓扑校验与解释执行留 Step8。
 * </p>
 */
public interface AgentGraphDefService {

    /**
     * 创建图定义（DRAFT 状态）。
     * <p>
     * 服务端生成 {@code agent_} 前缀 graphKey（租户内唯一）+ 初始图（START→END）+
     * defVersion=1 + status=DRAFT。
     * </p>
     *
     * @param name 图名称（必填，空白抛 PARAM_ERROR）
     * @return 新图定义 id
     */
    Long create(String name);

    /**
     * 保存草稿图 —— 无条件全量覆盖 graph_json，status 保持 DRAFT，不跑校验（允许存残图）。
     *
     * @param id    图定义 ID
     * @param graph 图对象（ProcessGraph），序列化后落库
     */
    void saveDraftGraph(Long id, ProcessGraph graph);

    /**
     * 读取图定义并解析为图对象（设计器回显用）。
     *
     * @param id 图定义 ID
     * @return 解析后的 ProcessGraph；graph_json 为空/损坏时返回 null
     */
    ProcessGraph getGraph(Long id);

    /**
     * 分页查询图定义列表（DTO 不含 graph_json 大字段），按 update_time 倒序。
     *
     * @param pageParam 分页参数
     * @return 分页结果
     */
    PageResult<AgentGraphDefDTO> pageDefs(PageParam pageParam);

    /**
     * 逻辑删除图定义（@TableLogic，幂等）。
     *
     * @param id 图定义 ID
     */
    void delete(Long id);

    /**
     * 发布图定义（DRAFT → PUBLISHED，允许重复发布迭代版本）。
     * <p>
     * 发布门：①图可解析且 elements 非空 ②已 PUBLISHED 后 graphKey 冻结检查
     * （图内携带的 key 必须与实体一致）。通过后 defVersion + 1、status 置 PUBLISHED，
     * 并固化一份不可变版本快照（D022-06）。
     * </p>
     *
     * @param id 图定义 ID
     * @return 发布后的元数据 DTO（含新版本号与状态）
     */
    AgentGraphDefDTO publish(Long id);

    /**
     * 历史发布版本列表（新版本在前，不含 graph_json）。
     *
     * @param id 图定义 ID
     * @return 版本快照摘要列表
     */
    java.util.List<com.sw.ck.agent.dto.AgentGraphSnapshotDTO> listVersions(Long id);

    /**
     * 更新图级设置（当前支持执行超时秒数）。
     *
     * @param id                   图定义 ID
     * @param executionTimeoutSecs 执行超时（秒，1–3600）
     * @return 更新后的图元数据
     */
    AgentGraphDefDTO updateSettings(Long id, Integer executionTimeoutSecs);
}

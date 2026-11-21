package com.sw.ck.bpm.api.facade;

import com.sw.ck.bpm.api.dto.BpmDeployResult;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.result.MutationOutcome;

import java.util.Optional;

/**
 * BPM 部署门面 —— 封装流程引擎 RepositoryService。
 * <p>
 * 定义流程定义部署操作契约。
 * 实现类位于 sw-bpm-engine（闭源），由 Spring 注入。
 * </p>
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：empty 只表达"目标定义不存在"，
 * 部署失败、参数非法与引擎异常继续抛明确异常。
 * </p>
 *
 * @since 1.0.0
 */
public interface BpmDeployFacade {

    /**
     * 从 classpath 部署 BPMN 文件。
     *
     * @param resourcePath    classpath 下的 BPMN 资源路径
     * @param deploymentName  部署名称
     * @return present = 部署 ID；当前契约恒 present，部署失败抛异常
     */
    Optional<String> deployClasspathBpmn(String resourcePath, String deploymentName);

    /**
     * 将 {@link ProcessGraph} 翻译为 BPMN XML 字节数组。
     * <p>
     * 使用 BpmnModel API + BpmnXMLConverter 生成标准 BPMN 2.0 XML，
     * 禁手拼 XML 字符串。
     * </p>
     *
     * @param graph 流程设计器图模型
     * @return present = BPMN 2.0 XML 字节数组；当前契约恒 present，图非法抛异常
     */
    Optional<byte[]> translateToBpmn(ProcessGraph graph);

    /**
     * 部署内存中的 BPMN XML。
     * <p>
     * 经 Flowable RepositoryService 部署，返回部署结果。
     * 不破坏 {@link #deployClasspathBpmn(String, String)}（Walking Skeleton 仍用）。
     * </p>
     *
     * @param bpmnXml        BPMN 2.0 XML 字节数组
     * @param deploymentName 部署名称
     * @return present = 部署结果（含 deploymentId + processDefinitionId）；当前契约恒 present，
     *         部署失败抛异常
     */
    Optional<BpmDeployResult> deployModel(byte[] bpmnXml, String deploymentName);

    /**
     * 返回 Flowable 已部署流程定义对应的原始 BPMN XML 字符串。
     * <p>
     * 使用 {@code repositoryService.getResourceAsStream} 取回部署时存档的原始 XML，
     * 保真度高于通过 {@code BpmnModel} 重新序列化。
     * </p>
     *
     * @param processDefinitionId Flowable 流程定义 ID
     * @return present = 原始 BPMN XML 字符串；empty = 该流程定义不存在（原 IllegalStateException
     *         的缺失路径改由 empty 表达）；资源读取失败继续抛异常
     */
    Optional<String> getBpmnXml(String processDefinitionId);

    /**
     * 挂起 Flowable 流程定义：只禁止新实例发起，保持既有实例可解释（I3 §4.3）。
     *
     * @return present = {@link MutationOutcome#APPLIED} 本次已挂起 /
     *         {@link MutationOutcome#ALREADY_APPLIED} 定义已处于挂起态（合法幂等）；
     *         定义不存在抛明确异常
     */
    Optional<MutationOutcome> suspendProcessDefinition(String processDefinitionId);

    /**
     * 激活 Flowable 流程定义：恢复同一已发布版本的发起能力（I3 §4.3）。
     *
     * @return present = {@link MutationOutcome#APPLIED} 本次已激活 /
     *         {@link MutationOutcome#ALREADY_APPLIED} 定义已处于激活态（合法幂等）；
     *         定义不存在抛明确异常
     */
    Optional<MutationOutcome> activateProcessDefinition(String processDefinitionId);
}

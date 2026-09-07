package com.sw.ck.bpm.api.node;

/**
 * 节点能力标识。能力集合属于 system 级节点契约，不暴露 Flowable 类型。
 */
public enum BpmNodeCapability {

    /** 可出现在流程设计器能力清单中。 */
    DESIGN,

    /** 能够被翻译为引擎模型。 */
    TRANSLATE,

    /** 能够进入流程运行链。 */
    RUNTIME,

    /** 节点拥有统一配置校验实现。 */
    CONFIG_VALIDATE
}

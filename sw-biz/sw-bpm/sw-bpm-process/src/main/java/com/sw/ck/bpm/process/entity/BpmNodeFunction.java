package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 受控节点函数注册表（I3 §4.9）：稳定 func_key + 单调版本；发布期校验并冻结。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_node_function")
public class BpmNodeFunction extends BaseEntity {

    /** 稳定函数标识（产品契约，不是类名/Bean 名）。 */
    @TableField("func_key")
    private String funcKey;

    /** 函数版本（单调递增；运行实例冻结实际版本）。 */
    @TableField("func_version")
    private Integer funcVersion;

    /** 类型：RESOLVE_PARTICIPANTS / HANDLE_RESULT。 */
    @TableField("func_type")
    private String funcType;

    /** 受控实现 Bean 名（内置注册，非用户上传脚本）。 */
    @TableField("impl_bean")
    private String implBean;

    /** 输入/输出 schema JSON。 */
    @TableField("config")
    private String config;

    /** 允许节点类型 JSON 白名单。 */
    @TableField("allowed_nodes")
    private String allowedNodes;

    /** 执行超时（毫秒）。 */
    @TableField("timeout_ms")
    private Long timeoutMs;

    /** 失败策略：BLOCK / FALLBACK / CONTINUE。 */
    @TableField("failure_strategy")
    private String failureStrategy;

    /** 是否启用。 */
    @TableField("enabled")
    private Integer enabled;
}

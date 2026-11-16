package com.sw.ck.bpm.process.vo;

import lombok.Data;

import com.sw.ck.common.value.DynamicData;

/**
 * 流程脚本模拟运行结果。
 * <p>
 * {@code value} 为脚本返回值（任意 JSON 值），{@code samples} 为按变量名生成的示例值；
 * durationMs 保持 {@code long}，沿用全局 Long→String 序列化口径。
 * </p>
 */
@Data
public class ScriptPreviewVO {

    private String kind;

    private DynamicData value;

    private String typeName;

    private String errorMessage;

    private long durationMs;

    private DynamicData samples;
}

package com.sw.ck.bpm.process.vo;

import lombok.Data;

/**
 * 提交后实例探测结果（本人发起人口径）。
 * <p>
 * {@code count} 保持 {@code long} 原始类型，沿用全局 Long→String 序列化口径，
 * 避免前端 Number 精度截断；{@code formKey}/{@code businessKey} 为请求回显。
 * </p>
 */
@Data
public class MyInstanceCountVO {

    private long count;

    private String formKey;

    private String businessKey;
}

package com.sw.ck.bpm.process.vo;

import lombok.Data;

/**
 * 流程模板删除结果（回显被删除模板 ID）。
 * <p>
 * {@code id} 保持 {@link Long}，沿用全局 Long→String 序列化口径。
 * </p>
 */
@Data
public class TemplateDeleteResultVO {

    private Long id;
}

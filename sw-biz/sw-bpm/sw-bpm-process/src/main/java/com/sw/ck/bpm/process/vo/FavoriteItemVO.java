package com.sw.ck.bpm.process.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 收藏 / 常用 / 最近使用条目（V012-BUG-009）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteItemVO implements Serializable {

    /** 流程标识（processDefKey） */
    private String processKey;

    /** 展示名（定义名；缺失时回退收藏快照/键名） */
    private String name;
}

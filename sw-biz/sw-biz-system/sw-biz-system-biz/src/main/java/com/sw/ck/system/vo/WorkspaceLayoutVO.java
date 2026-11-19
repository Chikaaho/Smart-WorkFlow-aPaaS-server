package com.sw.ck.system.vo;

import com.sw.ck.system.entity.SysWorkspaceCardType;
import lombok.Data;


import java.util.List;
import java.util.Map;
import com.sw.ck.common.value.DynamicData;

/**
 * 当前用户工作台布局：是否自定义、可用卡片类型与布局内容。
 */
@Data
public class WorkspaceLayoutVO {

    private boolean custom;

    private List<SysWorkspaceCardType> cardTypes;

    private DynamicData layout;
}

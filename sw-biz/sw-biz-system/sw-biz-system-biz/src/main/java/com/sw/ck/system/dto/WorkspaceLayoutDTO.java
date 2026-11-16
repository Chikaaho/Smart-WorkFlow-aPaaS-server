package com.sw.ck.system.dto;

import com.sw.ck.system.entity.SysWorkspaceCardType;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 当前用户工作台布局：是否自定义、可用卡片类型与布局内容。
 */
@Data
public class WorkspaceLayoutDTO {

    private boolean custom;

    private List<SysWorkspaceCardType> cardTypes;

    private Map<String, Object> layout;
}

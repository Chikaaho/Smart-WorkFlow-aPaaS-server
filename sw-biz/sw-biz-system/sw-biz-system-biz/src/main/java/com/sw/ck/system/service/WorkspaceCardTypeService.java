package com.sw.ck.system.service;

import com.sw.ck.system.entity.SysWorkspaceCardType;

import java.util.List;

/** 工作台卡片类型维护与读取服务。 */
public interface WorkspaceCardTypeService {

    /** 当前租户可供工作台渲染的启用卡片类型。 */
    List<SysWorkspaceCardType> listAvailable();

    /** 当前租户后台维护列表，包含停用项。 */
    List<SysWorkspaceCardType> listAll();

    Long create(SysWorkspaceCardType cardType);

    void update(SysWorkspaceCardType cardType);

    void delete(Long id);
}

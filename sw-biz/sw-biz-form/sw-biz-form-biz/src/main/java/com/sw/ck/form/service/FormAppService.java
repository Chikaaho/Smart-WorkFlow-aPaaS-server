package com.sw.ck.form.service;

import com.sw.ck.form.api.dto.FormAppContentDTO;
import com.sw.ck.form.api.dto.FormAppDTO;
import com.sw.ck.form.api.dto.FormAppDirDTO;
import com.sw.ck.form.api.dto.FormAppDirSaveReq;
import com.sw.ck.form.api.dto.FormAppMembershipReq;
import com.sw.ck.form.api.dto.FormAppSaveReq;

import java.util.List;

/**
 * 业务应用服务（应用中心与表单分类统一）。
 * <p>
 * 应用（一级分类）→ 目录（二级分类）→ 表单；应用承担原表单一级分类的管理职责。
 * 管理端维护应用/目录与表单归属；用户端按可见范围与既有表单权限过滤后展示。
 * </p>
 */
public interface FormAppService {

    // ==================== 管理端（form:app:manage） ====================

    /** 管理列表（含停用应用与承接应用），按 sortNo、createTime 升序；keyword 对名称模糊过滤。 */
    List<FormAppDTO> listApps(String keyword);

    /** 应用详情（含计数与指定用户集合）。 */
    FormAppDTO getApp(String id);

    /** 新建应用（重名校验；SPECIFIED 集合空=无人可见）。 */
    FormAppDTO createApp(FormAppSaveReq req);

    /** 更新应用（名称/图标/颜色/排序/可见范围；可见范围集合整体重配）。 */
    FormAppDTO updateApp(String id, FormAppSaveReq req);

    /** 停用应用（阻止新增入口，保留数据与历史；已有待办/记录经既有入口继续）。 */
    void disableApp(String id);

    /** 恢复应用（按当前权限重新展示）。 */
    void enableApp(String id);

    /** 删除应用（有目录或表单引用时拒绝，明确剩余引用）。 */
    void deleteApp(String id);

    /** 应用内容（管理视角：全部业务表单，目录+直接归属分组）。 */
    FormAppContentDTO getAppContent(String appId);

    /** 新建目录（应用内二级分类）。 */
    FormAppDirDTO createDir(String appId, FormAppDirSaveReq req);

    /** 更新目录名称/排序。 */
    FormAppDirDTO updateDir(String dirId, FormAppDirSaveReq req);

    /** 删除目录（目录下有表单时拒绝）。 */
    void deleteDir(String dirId);

    /** 表单归属调整（显式操作；目标须为租户内启用应用，目录须属于该应用；失败保持原归属）。 */
    void assignForm(String formId, FormAppMembershipReq req);

    // ==================== 用户端（登录即可） ====================

    /** 当前用户可见的应用（启用 + 可见范围命中），计数只含当前用户可用表单。 */
    List<FormAppDTO> listVisibleAppsForCurrentUser();

    /** 可见应用内容（未发布/停用/不可见表单不下发；应用不可见按不存在处理）。 */
    FormAppContentDTO getVisibleAppContent(String appId);
}

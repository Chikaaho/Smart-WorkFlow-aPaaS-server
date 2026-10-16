package com.sw.ck.form.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.form.api.dto.FormAppContentDTO;
import com.sw.ck.form.api.dto.FormAppDTO;
import com.sw.ck.form.api.dto.FormAppDirDTO;
import com.sw.ck.form.api.dto.FormAppDirSaveReq;
import com.sw.ck.form.api.dto.FormAppFormItemDTO;
import com.sw.ck.form.api.dto.FormAppMembershipReq;
import com.sw.ck.form.api.dto.FormAppSaveReq;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.entity.FormAppDirEntity;
import com.sw.ck.form.entity.FormAppEntity;
import com.sw.ck.form.entity.FormAppScopeEntity;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.mapper.FormAppDirMapper;
import com.sw.ck.form.mapper.FormAppMapper;
import com.sw.ck.form.mapper.FormAppScopeMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.service.FormAppMembershipValidator;
import com.sw.ck.form.service.FormAppScopeMatcher;
import com.sw.ck.form.service.FormAppScopeTypes;
import com.sw.ck.form.service.FormAppService;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业务应用服务实现（应用中心与表单分类统一）。
 */
@Service
public class FormAppServiceImpl implements FormAppService {

    private static final Logger log = LoggerFactory.getLogger(FormAppServiceImpl.class);

    private static final String SCOPE_ALL = "ALL";
    private static final String SCOPE_SPECIFIED = "SPECIFIED";
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String FORM_TYPE_BUSINESS = "BUSINESS";
    private static final String FORM_STATUS_PUBLISHED = "PUBLISHED";
    private static final String SOURCE_MANUAL = "MANUAL";

    private final FormAppMapper appMapper;
    private final FormAppDirMapper dirMapper;
    private final FormAppScopeMapper appScopeMapper;
    private final FormAppScopeMatcher scopeMatcher;
    private final FormDefMapper formDefMapper;
    private final FormDefService formDefService;
    private final FormAppMembershipValidator membershipValidator;
    private final FormIdGenerator idGenerator;

    public FormAppServiceImpl(FormAppMapper appMapper,
                              FormAppDirMapper dirMapper,
                              FormAppScopeMapper appScopeMapper,
                              FormDefMapper formDefMapper,
                              FormDefService formDefService,
                              FormAppMembershipValidator membershipValidator,
                              FormAppScopeMatcher scopeMatcher,
                              FormIdGenerator idGenerator) {
        this.appMapper = appMapper;
        this.dirMapper = dirMapper;
        this.appScopeMapper = appScopeMapper;
        this.formDefMapper = formDefMapper;
        this.formDefService = formDefService;
        this.membershipValidator = membershipValidator;
        this.scopeMatcher = scopeMatcher;
        this.idGenerator = idGenerator;
    }

    // ==================== 管理端 ====================

    @Override
    public List<FormAppDTO> listApps(String keyword) {
        LambdaQueryWrapper<FormAppEntity> query = Wrappers.<FormAppEntity>lambdaQuery()
                .orderByAsc(FormAppEntity::getSortNo)
                .orderByAsc(FormAppEntity::getCreateTime);
        String kw = trimToNull(keyword);
        if (kw != null) {
            query.like(FormAppEntity::getName, kw);
        }
        List<FormAppEntity> apps = appMapper.selectList(query);
        if (apps.isEmpty()) {
            return List.of();
        }
        Map<String, Long> dirCounts = countDirsByApp();
        Map<String, Long> formCounts = countBusinessFormsByApp();
        Map<String, List<FormAppScopeEntity>> scopes = loadScopeRows();
        return apps.stream()
                .map(app -> toDTO(app, formCounts.getOrDefault(app.getId(), 0L),
                        dirCounts.getOrDefault(app.getId(), 0L), scopes.get(app.getId())))
                .toList();
    }

    @Override
    public FormAppDTO getApp(String id) {
        FormAppEntity app = requireApp(id);
        Long dirCount = dirMapper.selectCount(Wrappers.<FormAppDirEntity>lambdaQuery()
                .eq(FormAppDirEntity::getAppId, id));
        Long formCount = formDefMapper.selectCount(Wrappers.<FormDefEntity>lambdaQuery()
                .eq(FormDefEntity::getAppId, id)
                .eq(FormDefEntity::getFormType, FORM_TYPE_BUSINESS));
        List<FormAppScopeEntity> scopes = SCOPE_SPECIFIED.equals(app.getVisibleScopeType())
                ? loadScopeRowsOf(id) : null;
        return toDTO(app, formCount, dirCount, scopes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FormAppDTO createApp(FormAppSaveReq req) {
        String name = requireName(req == null ? null : req.getName(), "应用名称");
        String scope = normalizeScope(req == null ? null : req.getVisibleScopeType());
        AppScopeSets scopeSets = readScopeSets(req);
        requireNameAvailable(name, null);

        FormAppEntity app = new FormAppEntity();
        app.setId(idGenerator.generate());
        app.setName(name);
        app.setIcon(trimToNull(req.getIcon()));
        app.setColor(trimToNull(req.getColor()));
        app.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        app.setVisibleScopeType(scope);
        app.setStatus(STATUS_ENABLED);
        app.setSource(SOURCE_MANUAL);
        appMapper.insert(app);
        if (SCOPE_SPECIFIED.equals(scope)) {
            insertScopes(app.getId(), scopeSets);
        }
        log.info("Created form app: id={}, name={}, scope={}, scopeSize={}", app.getId(), name, scope, scopeSets.size());
        return toDTO(app, 0L, 0L,
                SCOPE_SPECIFIED.equals(scope) ? loadScopeRowsOf(app.getId()) : null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FormAppDTO updateApp(String id, FormAppSaveReq req) {
        FormAppEntity app = requireApp(id);
        String name = requireName(req == null ? null : req.getName(), "应用名称");
        requireNameAvailable(name, id);
        String scope = normalizeScope(req == null ? null : req.getVisibleScopeType());
        AppScopeSets scopeSets = readScopeSets(req);

        app.setName(name);
        app.setIcon(trimToNull(req.getIcon()));
        app.setColor(trimToNull(req.getColor()));
        if (req.getSortNo() != null) {
            app.setSortNo(req.getSortNo());
        }
        app.setVisibleScopeType(scope);
        app.setUpdateTime(LocalDateTime.now());
        appMapper.updateById(app);

        // 可见范围集合整体重配：物理删除重建（SPECIFIED→ALL 时清空残留集合）。
        appScopeMapper.deletePhysicallyByAppId(id);
        if (SCOPE_SPECIFIED.equals(scope)) {
            insertScopes(id, scopeSets);
        }
        log.info("Updated form app: id={}, name={}, scope={}, scopeSize={}", id, name, scope, scopeSets.size());
        Long dirCount = dirMapper.selectCount(Wrappers.<FormAppDirEntity>lambdaQuery()
                .eq(FormAppDirEntity::getAppId, id));
        Long formCount = formDefMapper.selectCount(Wrappers.<FormDefEntity>lambdaQuery()
                .eq(FormDefEntity::getAppId, id)
                .eq(FormDefEntity::getFormType, FORM_TYPE_BUSINESS));
        return toDTO(app, formCount, dirCount,
                SCOPE_SPECIFIED.equals(scope) ? loadScopeRowsOf(id) : null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disableApp(String id) {
        FormAppEntity app = requireApp(id);
        if (STATUS_DISABLED.equals(app.getStatus())) {
            return;
        }
        app.setStatus(STATUS_DISABLED);
        app.setUpdateTime(LocalDateTime.now());
        appMapper.updateById(app);
        log.info("Disabled form app: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enableApp(String id) {
        FormAppEntity app = requireApp(id);
        if (STATUS_ENABLED.equals(app.getStatus())) {
            return;
        }
        app.setStatus(STATUS_ENABLED);
        app.setUpdateTime(LocalDateTime.now());
        appMapper.updateById(app);
        log.info("Enabled form app: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteApp(String id) {
        requireApp(id);
        Long formCount = formDefMapper.selectCount(Wrappers.<FormDefEntity>lambdaQuery()
                .eq(FormDefEntity::getAppId, id)
                .eq(FormDefEntity::getFormType, FORM_TYPE_BUSINESS));
        Long dirCount = dirMapper.selectCount(Wrappers.<FormAppDirEntity>lambdaQuery()
                .eq(FormAppDirEntity::getAppId, id));
        if (formCount > 0 || dirCount > 0) {
            throw new BaseException(FormErrorCode.APP_DELETE_RESTRICTED,
                    "应用下仍有 " + formCount + " 个表单、" + dirCount + " 个目录，须先处理归属再删除");
        }
        appMapper.deleteById(id);
        appScopeMapper.deletePhysicallyByAppId(id);
        log.info("Deleted form app: id={}", id);
    }

    @Override
    public FormAppContentDTO getAppContent(String appId) {
        FormAppEntity app = requireApp(appId);
        List<FormAppDirEntity> dirs = listDirs(appId);
        List<FormDefEntity> forms = listBusinessForms(appId);
        return buildContent(app, dirs, forms, (long) forms.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FormAppDirDTO createDir(String appId, FormAppDirSaveReq req) {
        FormAppEntity app = requireApp(appId);
        if (!STATUS_ENABLED.equals(app.getStatus())) {
            throw new BaseException(FormErrorCode.APP_DISABLED, "应用已停用，不能新建目录");
        }
        String name = requireName(req == null ? null : req.getName(), "目录名称");
        requireDirNameAvailable(appId, name, null);
        FormAppDirEntity dir = new FormAppDirEntity();
        dir.setId(idGenerator.generate());
        dir.setAppId(appId);
        dir.setName(name);
        dir.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        dirMapper.insert(dir);
        log.info("Created app dir: id={}, appId={}, name={}", dir.getId(), appId, name);
        return toDirDTO(dir, 0L, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FormAppDirDTO updateDir(String dirId, FormAppDirSaveReq req) {
        FormAppDirEntity dir = requireDir(dirId);
        String name = requireName(req == null ? null : req.getName(), "目录名称");
        requireDirNameAvailable(dir.getAppId(), name, dirId);
        dir.setName(name);
        if (req.getSortNo() != null) {
            dir.setSortNo(req.getSortNo());
        }
        dir.setUpdateTime(LocalDateTime.now());
        dirMapper.updateById(dir);
        Long formCount = formDefMapper.selectCount(Wrappers.<FormDefEntity>lambdaQuery()
                .eq(FormDefEntity::getDirId, dirId)
                .eq(FormDefEntity::getFormType, FORM_TYPE_BUSINESS));
        log.info("Updated app dir: id={}, name={}", dirId, name);
        return toDirDTO(dir, formCount, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDir(String dirId) {
        requireDir(dirId);
        Long formCount = formDefMapper.selectCount(Wrappers.<FormDefEntity>lambdaQuery()
                .eq(FormDefEntity::getDirId, dirId)
                .eq(FormDefEntity::getFormType, FORM_TYPE_BUSINESS));
        if (formCount > 0) {
            throw new BaseException(FormErrorCode.APP_DIR_DELETE_RESTRICTED,
                    "目录下仍有 " + formCount + " 个表单，须先处理归属再删除");
        }
        dirMapper.deleteById(dirId);
        log.info("Deleted app dir: id={}", dirId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignForm(String formId, FormAppMembershipReq req) {
        FormDefEntity form = formDefMapper.selectById(formId);
        if (form == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        if (!FORM_TYPE_BUSINESS.equals(form.getFormType())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "仅业务表单可归属应用");
        }
        String appId = trimToNull(req == null ? null : req.getAppId());
        String dirId = trimToNull(req == null ? null : req.getDirId());
        membershipValidator.validateTarget(appId, dirId);
        form.setAppId(appId);
        form.setDirId(dirId);
        form.setUpdateTime(LocalDateTime.now());
        formDefMapper.updateById(form);
        log.info("Form membership updated: formId={}, appId={}, dirId={}", formId, appId, dirId);
    }

    // ==================== 用户端 ====================

    @Override
    public List<FormAppDTO> listVisibleAppsForCurrentUser() {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null || loginUser.getUserId() == null) {
            return List.of();
        }
        List<FormAppEntity> apps = appMapper.selectList(Wrappers.<FormAppEntity>lambdaQuery()
                .eq(FormAppEntity::getStatus, STATUS_ENABLED)
                .orderByAsc(FormAppEntity::getSortNo)
                .orderByAsc(FormAppEntity::getCreateTime));
        if (apps.isEmpty()) {
            return List.of();
        }
        Map<String, List<FormAppScopeEntity>> scopes = loadScopeRows();
        List<FormAppEntity> visible = apps.stream()
                .filter(app -> scopeMatcher.matches(app, loginUser,
                        scopes.getOrDefault(app.getId(), List.of())))
                .toList();
        if (visible.isEmpty()) {
            return List.of();
        }
        // 可用表单计数：已发布 + 当前用户可见（不暴露受限表单名称或数量）。
        List<FormDefEntity> publishedForms = formDefMapper.selectList(Wrappers.<FormDefEntity>lambdaQuery()
                .eq(FormDefEntity::getFormType, FORM_TYPE_BUSINESS)
                .eq(FormDefEntity::getStatus, FORM_STATUS_PUBLISHED)
                .isNotNull(FormDefEntity::getAppId));
        Set<String> visibleKeys = formDefService.filterVisibleFormKeys(
                publishedForms.stream().map(FormDefEntity::getFormKey).collect(Collectors.toSet()));
        Map<String, Long> usableCounts = publishedForms.stream()
                .filter(form -> visibleKeys.contains(form.getFormKey()))
                .collect(Collectors.groupingBy(FormDefEntity::getAppId, Collectors.counting()));
        Map<String, Long> dirCounts = countDirsByApp();
        return visible.stream()
                .map(app -> toDTO(app, usableCounts.getOrDefault(app.getId(), 0L),
                        dirCounts.getOrDefault(app.getId(), 0L), null))
                .toList();
    }

    @Override
    public FormAppContentDTO getVisibleAppContent(String appId) {
        FormAppEntity app = appMapper.selectById(appId);
        if (app == null || !STATUS_ENABLED.equals(app.getStatus())) {
            throw new BaseException(FormErrorCode.APP_NOT_FOUND);
        }
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null || loginUser.getUserId() == null
                || !scopeMatcher.matches(app, loginUser, loadScopeRowsOf(app.getId()))) {
            throw new BaseException(FormErrorCode.APP_NOT_FOUND);
        }
        List<FormAppDirEntity> dirs = listDirs(appId);
        List<FormDefEntity> usableForms = listBusinessForms(appId).stream()
                .filter(form -> FORM_STATUS_PUBLISHED.equals(form.getStatus()))
                .filter(form -> formDefService.isCurrentUserVisible(form.getFormKey()))
                .toList();
        return buildContent(app, dirs, usableForms, (long) usableForms.size());
    }

    // ==================== 内部方法 ====================

    private FormAppEntity requireApp(String id) {
        String normalized = trimToNull(id);
        FormAppEntity app = normalized == null ? null : appMapper.selectById(normalized);
        if (app == null) {
            throw new BaseException(FormErrorCode.APP_NOT_FOUND);
        }
        return app;
    }

    private FormAppDirEntity requireDir(String dirId) {
        String normalized = trimToNull(dirId);
        FormAppDirEntity dir = normalized == null ? null : dirMapper.selectById(normalized);
        if (dir == null) {
            throw new BaseException(FormErrorCode.APP_DIR_NOT_FOUND);
        }
        return dir;
    }

    private String requireName(String value, String label) {
        String name = trimToNull(value);
        if (name == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), label + "不能为空");
        }
        if (name.length() > 100) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), label + "不能超过 100 字");
        }
        return name;
    }

    private void requireNameAvailable(String name, String excludeId) {
        LambdaQueryWrapper<FormAppEntity> query = Wrappers.<FormAppEntity>lambdaQuery()
                .eq(FormAppEntity::getName, name);
        if (excludeId != null) {
            query.ne(FormAppEntity::getId, excludeId);
        }
        if (appMapper.selectCount(query) > 0) {
            throw new BaseException(FormErrorCode.APP_NAME_DUPLICATE);
        }
    }

    private void requireDirNameAvailable(String appId, String name, String excludeId) {
        LambdaQueryWrapper<FormAppDirEntity> query = Wrappers.<FormAppDirEntity>lambdaQuery()
                .eq(FormAppDirEntity::getAppId, appId)
                .eq(FormAppDirEntity::getName, name);
        if (excludeId != null) {
            query.ne(FormAppDirEntity::getId, excludeId);
        }
        if (dirMapper.selectCount(query) > 0) {
            throw new BaseException(FormErrorCode.APP_DIR_NAME_DUPLICATE);
        }
    }

    private String normalizeScope(String value) {
        String scope = trimToNull(value);
        if (scope == null) {
            // 缺省安全值：指定用户（集合空=无人可见），不得把空值误作全员授权
            return SCOPE_SPECIFIED;
        }
        if (SCOPE_ALL.equalsIgnoreCase(scope)) {
            return SCOPE_ALL;
        }
        if (SCOPE_SPECIFIED.equalsIgnoreCase(scope)) {
            return SCOPE_SPECIFIED;
        }
        throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "可见范围类型不合法");
    }

    private List<Long> normalizeIds(List<Long> ids, String dimension) {
        if (ids == null) {
            return List.of();
        }
        List<Long> normalized = ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (normalized.stream().anyMatch(id -> id <= 0)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "可见范围" + dimension + " ID 无效");
        }
        return normalized;
    }

    private List<String> normalizeCodes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        return codes.stream()
                .map(FormAppServiceImpl::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /** 读取请求中全部可见范围维度（0.1.7 #1：用户/部门/角色/岗位/租户）。 */
    private AppScopeSets readScopeSets(FormAppSaveReq req) {
        return new AppScopeSets(
                normalizeIds(req == null ? null : req.getUserIds(), "用户"),
                normalizeIds(req == null ? null : req.getDeptIds(), "部门"),
                normalizeCodes(req == null ? null : req.getRoleCodes()),
                normalizeIds(req == null ? null : req.getPostIds(), "岗位"),
                normalizeIds(req == null ? null : req.getTenantIds(), "租户"));
    }

    /** 各维度可见范围集合（落库专用载体）。 */
    private record AppScopeSets(List<Long> userIds, List<Long> deptIds, List<String> roleCodes,
                                List<Long> postIds, List<Long> tenantIds) {
        private int size() {
            return userIds.size() + deptIds.size() + roleCodes.size() + postIds.size()
                    + tenantIds.size();
        }
    }

    private void insertScopes(String appId, AppScopeSets sets) {
        insertScope(appId, FormAppScopeTypes.USER, sets.userIds());
        insertScope(appId, FormAppScopeTypes.DEPT, sets.deptIds());
        insertScope(appId, FormAppScopeTypes.POST, sets.postIds());
        insertScope(appId, FormAppScopeTypes.TENANT, sets.tenantIds());
        for (String roleCode : sets.roleCodes()) {
            FormAppScopeEntity relation = new FormAppScopeEntity();
            relation.setId(idGenerator.generate());
            relation.setAppId(appId);
            relation.setScopeType(FormAppScopeTypes.ROLE);
            relation.setScopeId(roleCode);
            appScopeMapper.insert(relation);
        }
    }

    private void insertScope(String appId, String scopeType, List<Long> ids) {
        for (Long id : ids) {
            FormAppScopeEntity relation = new FormAppScopeEntity();
            relation.setId(idGenerator.generate());
            relation.setAppId(appId);
            relation.setScopeType(scopeType);
            relation.setScopeId(String.valueOf(id));
            appScopeMapper.insert(relation);
        }
    }

    private List<FormAppDirEntity> listDirs(String appId) {
        return dirMapper.selectList(Wrappers.<FormAppDirEntity>lambdaQuery()
                .eq(FormAppDirEntity::getAppId, appId)
                .orderByAsc(FormAppDirEntity::getSortNo)
                .orderByAsc(FormAppDirEntity::getCreateTime));
    }

    private List<FormDefEntity> listBusinessForms(String appId) {
        return formDefMapper.selectList(Wrappers.<FormDefEntity>lambdaQuery()
                .eq(FormDefEntity::getAppId, appId)
                .eq(FormDefEntity::getFormType, FORM_TYPE_BUSINESS)
                .orderByAsc(FormDefEntity::getCreateTime));
    }

    private Map<String, Long> countDirsByApp() {
        return dirMapper.selectList(Wrappers.<FormAppDirEntity>lambdaQuery()).stream()
                .collect(Collectors.groupingBy(FormAppDirEntity::getAppId, Collectors.counting()));
    }

    private Map<String, Long> countBusinessFormsByApp() {
        return formDefMapper.selectList(Wrappers.<FormDefEntity>lambdaQuery()
                        .eq(FormDefEntity::getFormType, FORM_TYPE_BUSINESS)
                        .isNotNull(FormDefEntity::getAppId)).stream()
                .collect(Collectors.groupingBy(FormDefEntity::getAppId, Collectors.counting()));
    }

    private Map<String, List<FormAppScopeEntity>> loadScopeRows() {
        return appScopeMapper.selectList(Wrappers.<FormAppScopeEntity>lambdaQuery()).stream()
                .collect(Collectors.groupingBy(FormAppScopeEntity::getAppId));
    }

    private List<FormAppScopeEntity> loadScopeRowsOf(String appId) {
        return appScopeMapper.selectList(Wrappers.<FormAppScopeEntity>lambdaQuery()
                .eq(FormAppScopeEntity::getAppId, appId));
    }

    private FormAppContentDTO buildContent(FormAppEntity app, List<FormAppDirEntity> dirs,
                                           List<FormDefEntity> forms, long formCount) {
        Map<String, List<FormDefEntity>> byDir = forms.stream()
                .filter(form -> form.getDirId() != null)
                .collect(Collectors.groupingBy(FormDefEntity::getDirId));
        List<FormAppDirDTO> dirDtos = dirs.stream()
                .map(dir -> {
                    List<FormDefEntity> dirForms = byDir.getOrDefault(dir.getId(), List.of());
                    return toDirDTO(dir, (long) dirForms.size(), toFormItems(dirForms));
                })
                .toList();
        List<FormAppFormItemDTO> directForms = toFormItems(forms.stream()
                .filter(form -> form.getDirId() == null)
                .toList());
        return FormAppContentDTO.builder()
                .app(toDTO(app, formCount, (long) dirs.size(), null))
                .dirs(dirDtos)
                .directForms(directForms)
                .build();
    }

    private List<FormAppFormItemDTO> toFormItems(List<FormDefEntity> forms) {
        return forms.stream()
                .map(form -> FormAppFormItemDTO.builder()
                        .formId(form.getId())
                        .formKey(form.getFormKey())
                        .name(form.getName())
                        .status(form.getStatus())
                        .dirId(form.getDirId())
                        .formVersion(form.getFormVersion())
                        .build())
                .toList();
    }

    private FormAppDirDTO toDirDTO(FormAppDirEntity dir, Long formCount, List<FormAppFormItemDTO> forms) {
        return FormAppDirDTO.builder()
                .id(dir.getId())
                .appId(dir.getAppId())
                .name(dir.getName())
                .sortNo(dir.getSortNo())
                .formCount(formCount)
                .forms(forms)
                .build();
    }

    private FormAppDTO toDTO(FormAppEntity app, Long formCount, Long dirCount,
                             List<FormAppScopeEntity> scopeRows) {
        List<Long> visibleUserIds = null;
        List<Long> visibleDeptIds = null;
        List<String> visibleRoleCodes = null;
        List<Long> visiblePostIds = null;
        List<Long> visibleTenantIds = null;
        if (scopeRows != null) {
            visibleUserIds = scopeRowsOf(scopeRows, FormAppScopeTypes.USER)
                    .map(Long::valueOf).sorted().toList();
            visibleDeptIds = scopeRowsOf(scopeRows, FormAppScopeTypes.DEPT)
                    .map(Long::valueOf).sorted().toList();
            visibleRoleCodes = scopeRowsOf(scopeRows, FormAppScopeTypes.ROLE).sorted().toList();
            visiblePostIds = scopeRowsOf(scopeRows, FormAppScopeTypes.POST)
                    .map(Long::valueOf).sorted().toList();
            visibleTenantIds = scopeRowsOf(scopeRows, FormAppScopeTypes.TENANT)
                    .map(Long::valueOf).sorted().toList();
        }
        return FormAppDTO.builder()
                .id(app.getId())
                .name(app.getName())
                .icon(app.getIcon())
                .color(app.getColor())
                .sortNo(app.getSortNo())
                .visibleScopeType(app.getVisibleScopeType())
                .visibleUserIds(visibleUserIds)
                .visibleDeptIds(visibleDeptIds)
                .visibleRoleCodes(visibleRoleCodes)
                .visiblePostIds(visiblePostIds)
                .visibleTenantIds(visibleTenantIds)
                .status(app.getStatus())
                .source(app.getSource())
                .legacyCategoryId(app.getLegacyCategoryId())
                .formCount(formCount)
                .dirCount(dirCount)
                .createTime(app.getCreateTime())
                .updateTime(app.getUpdateTime())
                .build();
    }

    private static java.util.stream.Stream<String> scopeRowsOf(List<FormAppScopeEntity> rows,
                                                               String scopeType) {
        return rows.stream()
                .filter(row -> scopeType.equals(row.getScopeType()))
                .map(FormAppScopeEntity::getScopeId)
                .filter(Objects::nonNull);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

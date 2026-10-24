package com.sw.ck.bpm.engine.controller;

import com.sw.ck.bpm.api.dto.ExternalDatasourceRequest;
import com.sw.ck.bpm.api.dto.SqlExecutionRequest;
import com.sw.ck.bpm.api.dto.SqlExecutionResult;
import com.sw.ck.bpm.engine.entity.ExternalDatasource;
import com.sw.ck.bpm.engine.executor.SqlExecutor;
import com.sw.ck.bpm.engine.service.ExternalDatasourceService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.form.api.exception.ExternalDatasourceResultLimitExceededException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * 外部数据源管理 + SQL 执行控制器。
 */
@RestController
@RequestMapping("/api/workflow/external-datasource")
public class ExternalDatasourceController {

    private static final Logger log = LoggerFactory.getLogger(ExternalDatasourceController.class);

    private final ExternalDatasourceService datasourceService;
    private final SqlExecutor sqlExecutor;
    private final com.sw.ck.bpm.engine.service.ExternalDatasourceGrantService grantService;

    private static final Map<String, String> DRIVER_TYPES = Map.of(
            "postgresql", "org.postgresql.Driver",
            "mysql", "com.mysql.cj.jdbc.Driver",
            "h2", "org.h2.Driver",
            "oracle", "oracle.jdbc.OracleDriver");

    public ExternalDatasourceController(ExternalDatasourceService datasourceService,
                                        SqlExecutor sqlExecutor,
                                        com.sw.ck.bpm.engine.service.ExternalDatasourceGrantService grantService) {
        this.datasourceService = datasourceService;
        this.sqlExecutor = sqlExecutor;
        this.grantService = grantService;
    }

    /** 执行只读 SQL（需 workflow:datasource:execute 权限） */
    @PostMapping("/execute")
    @PreAuthorize("@ss.hasPermi('workflow:datasource:execute')")
    public R<SqlExecutionResult> execute(@Valid @RequestBody SqlExecutionRequest request) {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            return R.fail(401, "未登录");
        }
        log.info("SQL execute requested by {} (id={}), datasourceId={}",
                loginUser.getUsername(), loginUser.getUserId(), request.getDatasourceId());

        try {
            SqlExecutionResult result = sqlExecutor.execute(
                    request.getDatasourceId(), request.getSql(),
                    loginUser.getUserId(), loginUser.getUsername());
            return R.ok(result);
        } catch (ExternalDatasourceResultLimitExceededException e) {
            return R.fail(FormErrorCode.EXT_RESULT_LIMIT_EXCEEDED.getCode(),
                    FormErrorCode.EXT_RESULT_LIMIT_EXCEEDED.getMessage());
        }
    }

    /** 创建外部数据源 */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('workflow:datasource:manage')")
    public R<Long> create(@Valid @RequestBody ExternalDatasourceRequest request) {
        validateConnection(request);
        ExternalDatasource entity = toEntity(request);
        datasourceService.saveWithEncryption(entity, request.getPassword());
        return R.ok(entity.getId());
    }

    /** 更新外部数据源 */
    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('workflow:datasource:manage')")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ExternalDatasourceRequest request) {
        validateConnection(request);
        ExternalDatasource entity = toEntity(request);
        entity.setId(id);
        datasourceService.updateWithEncryption(entity, request.getPassword());
        return R.ok();
    }

    /** 删除外部数据源（逻辑删除） */
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('workflow:datasource:manage')")
    public R<Void> delete(@PathVariable Long id) {
        datasourceService.removeById(id);
        return R.ok();
    }

    /** 查询外部数据源详情（不返回密码） */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('workflow:datasource:manage')")
    public R<ExternalDatasource> getById(@PathVariable Long id) {
        ExternalDatasource entity = datasourceService.getById(id);
        return R.ok(entity);
    }

    /** 当前租户的数据源清单，密码和密文不进入响应。 */
    @GetMapping("/page")
    @PreAuthorize("@ss.hasPermi('workflow:datasource:manage')")
    public R<PageResult<ExternalDatasource>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer enabled) {
        long current = Math.max(1, pageNum);
        long size = Math.min(Math.max(1, pageSize), 100);
        var page = datasourceService.page(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size),
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ExternalDatasource>lambdaQuery()
                        .like(name != null && !name.isBlank(), ExternalDatasource::getName,
                                name == null ? null : name.trim())
                        .eq(enabled != null, ExternalDatasource::getEnabled, enabled)
                        .orderByDesc(ExternalDatasource::getUpdateTime));
        PageResult<ExternalDatasource> result = new PageResult<>();
        result.setRecords(page.getRecords());
        result.setTotal(page.getTotal());
        result.setPageNum(page.getCurrent());
        result.setPageSize(page.getSize());
        return R.ok(result);
    }

    /** 指定数据源的授权主体清单（F020：设计人员仅能引用已授权数据源）。 */
    @GetMapping("/{id}/grants")
    @PreAuthorize("@ss.hasPermi('workflow:datasource:manage')")
    public R<List<GrantView>> grants(@PathVariable Long id) {
        requireExisting(id);
        return R.ok(grantService.listGrants(id).stream()
                .map(row -> new GrantView(row.getSubjectType(), row.getSubjectKey()))
                .toList());
    }

    /** 整集合替换数据源授权主体（空集合 = 全部撤销）。 */
    @PutMapping("/{id}/grants")
    @PreAuthorize("@ss.hasPermi('workflow:datasource:manage')")
    public R<Void> replaceGrants(@PathVariable Long id, @RequestBody GrantsRequest request) {
        requireExisting(id);
        grantService.replaceGrants(id, request.grants() == null ? List.of() : request.grants().stream()
                .map(item -> new com.sw.ck.bpm.engine.service.ExternalDatasourceGrantService.GrantItem(
                        item.subjectType(), item.subjectKey()))
                .toList());
        log.info("Datasource grants replaced: id={}, count={}", id,
                request.grants() == null ? 0 : request.grants().size());
        return R.ok();
    }

    /** 授权主体响应视图（不含租户/审计字段）。 */
    public record GrantView(String subjectType, String subjectKey) {
    }

    /** 授权主体替换请求：整集合语义，空集合撤销全部授权。 */
    public record GrantsRequest(List<GrantItem> grants) {
    }

    /** 授权主体请求项。 */
    public record GrantItem(String subjectType, String subjectKey) {
    }

    private void requireExisting(Long id) {
        if (datasourceService.getById(id) == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "外部数据源不存在: id=" + id);
        }
    }

    private ExternalDatasource toEntity(ExternalDatasourceRequest request) {
        ExternalDatasource entity = new ExternalDatasource();
        entity.setName(request.getName());
        entity.setType(request.getType());
        entity.setJdbcUrl(request.getJdbcUrl());
        entity.setDriverClass(request.getDriverClass());
        entity.setUsername(request.getUsername());
        entity.setReadOnly(request.getReadOnly());
        entity.setEnabled(request.getEnabled());
        return entity;
    }

    private void validateConnection(ExternalDatasourceRequest request) {
        String type = request.getType() == null ? "" : request.getType().trim().toLowerCase(Locale.ROOT);
        String expectedDriver = DRIVER_TYPES.get(type);
        if (expectedDriver == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "不支持的数据源类型");
        }
        if (request.getDriverClass() != null && !request.getDriverClass().isBlank()
                && !expectedDriver.equals(request.getDriverClass().trim())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "驱动类与数据源类型不匹配");
        }
        String url = request.getJdbcUrl() == null ? "" : request.getJdbcUrl().trim();
        String expectedPrefix = "jdbc:" + type + ":";
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(expectedPrefix) || lower.contains("password=")
                || lower.contains("user=")) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "数据源地址与类型不匹配，用户名和密码必须填写在独立字段");
        }
    }
}

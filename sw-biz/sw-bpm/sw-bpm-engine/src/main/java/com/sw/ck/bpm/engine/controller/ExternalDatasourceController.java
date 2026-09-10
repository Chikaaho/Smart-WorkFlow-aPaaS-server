package com.sw.ck.bpm.engine.controller;

import com.sw.ck.bpm.api.dto.ExternalDatasourceRequest;
import com.sw.ck.bpm.api.dto.SqlExecutionRequest;
import com.sw.ck.bpm.api.dto.SqlExecutionResult;
import com.sw.ck.bpm.engine.entity.ExternalDatasource;
import com.sw.ck.bpm.engine.executor.SqlExecutor;
import com.sw.ck.bpm.engine.service.ExternalDatasourceService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部数据源管理 + SQL 执行控制器。
 */
@RestController
@RequestMapping("/api/workflow/external-datasource")
public class ExternalDatasourceController {

    private static final Logger log = LoggerFactory.getLogger(ExternalDatasourceController.class);

    private final ExternalDatasourceService datasourceService;
    private final SqlExecutor sqlExecutor;

    public ExternalDatasourceController(ExternalDatasourceService datasourceService,
                                        SqlExecutor sqlExecutor) {
        this.datasourceService = datasourceService;
        this.sqlExecutor = sqlExecutor;
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
        ExternalDatasource entity = toEntity(request);
        datasourceService.saveWithEncryption(entity, request.getPassword());
        return R.ok(entity.getId());
    }

    /** 更新外部数据源 */
    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('workflow:datasource:manage')")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ExternalDatasourceRequest request) {
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
}

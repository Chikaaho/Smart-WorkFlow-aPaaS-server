package com.sw.ck.form.controller;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.response.R;
import com.sw.ck.form.api.dto.ExtQueryResult;
import com.sw.ck.form.entity.FormExtQueryEntity;
import com.sw.ck.form.service.FormExtDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 受控外部数据源表单链路接口（I2，方向 §4.3）。
 * <p>
 * 设计预览与运行读取使用同一服务端入口 {@code /form/ext/query}；
 * SQL/密钥/连接串只存服务端，绝不出现在请求、响应或日志中。
 * </p>
 */
@RestController
@RequestMapping("/form/ext")
public class FormExtDataController {

    private static final Logger log = LoggerFactory.getLogger(FormExtDataController.class);

    private final FormExtDataService extDataService;

    public FormExtDataController(FormExtDataService extDataService) {
        this.extDataService = extDataService;
    }

    /** 登记或升版本查询契约（管理员）。 */
    public record RegisterReq(Long datasourceId, String queryKey, String sql,
                              List<Map<String, String>> outputFields) {
    }

    @PreAuthorize("@ss.hasPermi('form:design:save')")
    @PostMapping("/query-contract")
    public R<Map<String, Object>> registerQuery(@RequestBody RegisterReq req) {
        FormExtQueryEntity entity = extDataService.registerQuery(req.datasourceId(), req.queryKey(),
                req.sql(), req.outputFields());
        return R.ok(Map.of(
                "queryKey", entity.getQueryKey(),
                "version", entity.getQueryVersion()));
    }

    /**
     * 设计预览/运行读取（同一入口）：按 queryKey + version 执行受控查询。
     * 表单 dsBinding 引用的契约必须已启用；停用/未知契约返回可判定失败。
     */
    @PreAuthorize("@ss.hasPermi('form:design') or @ss.hasPermi('form:data:query')")
    @GetMapping("/query/{queryKey}")
    public R<ExtQueryResult> query(@PathVariable("queryKey") String queryKey,
                                   @RequestParam(required = false) Integer version) {
        try {
            return R.ok(extDataService.preview(queryKey, version));
        } catch (BaseException e) {
            log.warn("Ext query rejected: key={}, code={}", queryKey, e.getCode());
            throw e;
        }
    }
}

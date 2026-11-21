package com.sw.ck.system.controller;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.system.service.LogLevelService;
import com.sw.ck.system.vo.LogLevelVO;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 日志等级管理（1.2.0 P41 / E2）：查看与即时调整（独立权限点
 * {@code system:loglevel:manage}，查看与调整共用一点；超管短路默认可见可调，
 * 其他角色默认不授，前端无菜单、后端此处拒绝——E2-3）。
 */
@RestController
@RequestMapping("/system/log-levels")
public class LogLevelController {

    @Autowired
    private LogLevelService logLevelService;

    /** logger 列表（名称/配置等级/生效等级；keyword 按名称模糊过滤）。 */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('system:loglevel:manage')")
    public R<List<LogLevelVO>> list(@RequestParam(required = false) String keyword) {
        return R.ok(logLevelService.list(keyword).stream().map(LogLevelVO::of).toList());
    }

    /**
     * 调整等级（立即生效；level 缺省 = 恢复配置文件默认）。
     * 重启后恢复配置文件中的等级（E2-4 语义，页面已写明）。
     */
    @PutMapping("/{loggerName}/level")
    @PreAuthorize("@ss.hasPermi('system:loglevel:manage')")
    public R<LogLevelVO> setLevel(@PathVariable String loggerName,
                                   @RequestParam(required = false) String level) {
        try {
            var after = logLevelService.setLevel(loggerName, level);
            // 返回生效等级（ROOT 在 Log4j2 下 configuredLevel 恒 null）
            return R.ok(new LogLevelVO(loggerName, after == null ? null : after.name(),
                    after == null ? null : after.name()));
        } catch (IllegalArgumentException invalid) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, invalid.getMessage());
        }
    }
}

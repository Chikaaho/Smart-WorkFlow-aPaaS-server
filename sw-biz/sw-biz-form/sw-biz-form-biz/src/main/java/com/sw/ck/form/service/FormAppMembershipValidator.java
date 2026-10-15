package com.sw.ck.form.service;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.entity.FormAppDirEntity;
import com.sw.ck.form.entity.FormAppEntity;
import com.sw.ck.form.mapper.FormAppDirMapper;
import com.sw.ck.form.mapper.FormAppMapper;
import org.springframework.stereotype.Component;

/**
 * 表单归属目标校验（应用中心）。
 * <p>
 * 归属写入（新建表单带归属、归属调整）共用同一校验：目标应用必须存在且启用；
 * 目录非空时必须属于该应用。校验失败保持原归属不变。
 * </p>
 */
@Component
public class FormAppMembershipValidator {

    /** 应用启用状态（停用应用不能接收新内容）。 */
    public static final String STATUS_ENABLED = "ENABLED";

    private final FormAppMapper appMapper;
    private final FormAppDirMapper dirMapper;

    public FormAppMembershipValidator(FormAppMapper appMapper, FormAppDirMapper dirMapper) {
        this.appMapper = appMapper;
        this.dirMapper = dirMapper;
    }

    /**
     * 校验归属目标合法性。
     *
     * @param appId 目标应用 ID（必填）
     * @param dirId 目标目录 ID（可空=直接归属应用）
     * @return 目标应用实体
     */
    public FormAppEntity validateTarget(String appId, String dirId) {
        String normalizedAppId = trimToNull(appId);
        if (normalizedAppId == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "应用 ID 不能为空");
        }
        FormAppEntity app = appMapper.selectById(normalizedAppId);
        if (app == null) {
            throw new BaseException(FormErrorCode.APP_NOT_FOUND);
        }
        if (!STATUS_ENABLED.equals(app.getStatus())) {
            throw new BaseException(FormErrorCode.APP_DISABLED);
        }
        String normalizedDirId = trimToNull(dirId);
        if (normalizedDirId != null) {
            FormAppDirEntity dir = dirMapper.selectById(normalizedDirId);
            if (dir == null || !normalizedAppId.equals(dir.getAppId())) {
                throw new BaseException(FormErrorCode.APP_DIR_NOT_FOUND);
            }
        }
        return app;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

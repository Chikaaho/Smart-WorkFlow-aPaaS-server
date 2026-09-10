package com.sw.ck.form.api.exception;

/**
 * 外部数据源结果超过服务端安全上限。
 *
 * <p>这是跨 form/bpm 适配边界传递的技术异常；form 业务入口会将其映射为稳定的
 * {@link FormErrorCode#EXT_RESULT_LIMIT_EXCEEDED}，不会把被截断的数据当成完整成功结果。</p>
 */
public class ExternalDatasourceResultLimitExceededException extends RuntimeException {

    private final int maxRows;

    public ExternalDatasourceResultLimitExceededException(int maxRows) {
        super("External datasource result exceeds maxRows=" + maxRows);
        this.maxRows = maxRows;
    }

    public int getMaxRows() {
        return maxRows;
    }
}

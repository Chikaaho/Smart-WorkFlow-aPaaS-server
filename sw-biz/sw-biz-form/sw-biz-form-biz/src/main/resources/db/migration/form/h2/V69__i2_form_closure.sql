-- ===================================================================
-- P60 I2 低代码表单收口 :: 列表配置 / 外部数据源查询契约 / 生命周期审计
-- ===================================================================

-- ==================== 1. 表单列表展示配置 ====================
CREATE TABLE sw_form_list_config (
    id           VARCHAR(36)  PRIMARY KEY,
    form_id      VARCHAR(36)  NOT NULL UNIQUE,
    config_json  CLOB         NOT NULL,
    tenant_id    BIGINT       NOT NULL DEFAULT 0,
    deleted      SMALLINT     NOT NULL DEFAULT 0,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by    BIGINT,
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by    BIGINT,
    version      BIGINT       NOT NULL DEFAULT 0
);

-- ==================== 2. 受控外部数据源查询契约（版本化） ====================
CREATE TABLE sw_form_ext_query (
    id             VARCHAR(36)  PRIMARY KEY,
    datasource_id  BIGINT       NOT NULL,
    query_key      VARCHAR(64)  NOT NULL,
    query_version  INT          NOT NULL DEFAULT 1,
    sql_text       CLOB         NOT NULL,
    output_fields  CLOB         NOT NULL,
    enabled        SMALLINT     NOT NULL DEFAULT 1,
    tenant_id      BIGINT       NOT NULL DEFAULT 0,
    deleted        SMALLINT     NOT NULL DEFAULT 0,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by      BIGINT,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by      BIGINT,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_sw_form_ext_query UNIQUE (tenant_id, query_key, query_version)
);

-- ==================== 3. 表单生命周期审计 ====================
CREATE TABLE sw_form_lifecycle_audit (
    id           VARCHAR(36)  PRIMARY KEY,
    form_id      VARCHAR(36)  NOT NULL,
    action       VARCHAR(20)  NOT NULL,
    reason       VARCHAR(500),
    operator_id  BIGINT,
    tenant_id    BIGINT       NOT NULL DEFAULT 0,
    deleted      SMALLINT     NOT NULL DEFAULT 0,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by    BIGINT,
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by    BIGINT,
    version      BIGINT       NOT NULL DEFAULT 0
);

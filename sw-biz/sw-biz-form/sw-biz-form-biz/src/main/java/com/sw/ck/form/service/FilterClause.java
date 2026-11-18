package com.sw.ck.form.service;

/**
 * SQL 过滤子句：SQL 片段 + 参数值。
 */
record FilterClause(String sql, Object value) {}

package com.sw.ck.form.service;

import com.sw.ck.form.dynamic.FieldType;

/**
 * 子表字段元数据。
 */
record SubFieldMeta(String name, FieldType type, String physicalCol) {}

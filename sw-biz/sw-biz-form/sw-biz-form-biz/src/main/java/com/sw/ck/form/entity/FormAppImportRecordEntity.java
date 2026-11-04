package com.sw.ck.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用导入记录实体 — 对应 {@code sw_form_app_import_record} 表（0.2.2 F2，D022-15）。
 * <p>
 * 每次成功导入留一条记录：操作人（create_by）、时间（create_time）、包名、
 * 新建应用与导入结果；{@code report_json} 保存完整导入报告（新建清单、复用对象、
 * 置空引用及位置），供导入后查看与下载。记录行与导入写入同事务，
 * 导入失败时随事务整体回滚，不留部分导入数据。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_form_app_import_record")
public class FormAppImportRecordEntity extends FormBaseEntity {

    /** 导入包内的应用名（包名） */
    private String packageName;

    /** 上传文件名 */
    private String fileName;

    /** 新建应用 ID（sw_form_app.id） */
    private String appId;

    /** 新建应用名（重名时带后缀） */
    private String appName;

    /** 导入结果：SUCCESS（失败随事务回滚，不落记录） */
    private String result;

    /** 导入报告 JSON（FormBundleImportReport 序列化） */
    private String reportJson;
}

package com.sw.ck.bpm.engine.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.bpm.engine.entity.ExternalDatasource;

/**
 * ExternalDatasource 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class ExternalDatasourceVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String name;
    private String type;
    private String jdbcUrl;
    private String driverClass;
    private String username;
    private Integer readOnly;
    private Integer enabled;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static ExternalDatasourceVO from(ExternalDatasource source) {
        if (source == null) {
            return null;
        }
        ExternalDatasourceVO target = new ExternalDatasourceVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}

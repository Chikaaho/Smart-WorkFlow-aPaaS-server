package com.sw.ck.iot.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.iot.entity.IotTopic;

/**
 * IotTopic 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class IotTopicVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private Long connId;
    private Long productId;
    private String topic;
    private String direction;
    private Integer qos;
    private Integer retain;
    private String payloadType;
    private String mappingJson;
    private Integer enabled;
    private String remark;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static IotTopicVO from(IotTopic source) {
        if (source == null) {
            return null;
        }
        IotTopicVO target = new IotTopicVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}

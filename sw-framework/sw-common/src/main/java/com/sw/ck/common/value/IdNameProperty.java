package com.sw.ck.common.value;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 最小 id-name 数据收敛封装。
 * <p>
 * 跨层数据传递中只需要「标识 + 展示名」的场景统一复用本类型：字段固定为
 * {@code id}/{@code name} 两个 String，可读可写（setter 供调用方修正展示名等场景），
 * 值语义（equals/hashCode 按双字段），序列化按 Jackson 既有口径（字段名不变）。
 * 不可外部实例化，统一经 {@link #of(String, String)} 构造，保持创建入口单一。
 * </p>
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
public class IdNameProperty implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标识（业务主键或编码）。 */
    private String id;

    /** 展示名。 */
    private String name;

    private IdNameProperty(String id, String name) {
        this.id = id;
        this.name = name;
    }

    /** 唯一构造入口。 */
    public static IdNameProperty of(String id, String name) {
        return new IdNameProperty(id, name);
    }
}

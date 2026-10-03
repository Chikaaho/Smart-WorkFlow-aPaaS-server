package com.sw.ck.bpm.process.entity;

import java.util.Optional;

/**
 * 资源容量段（受理时冻结的占用归属）。
 * <p>
 * PROD_RESERVED（生产保留 400）/ OA_RESERVED（普通 OA 保留 400）/
 * SHARED（共享 1200）合计即全局上限。段选择规则：PROD 优先 SHARED→生产保留→OA 保留；
 * OA 优先 SHARED→OA 保留→生产保留（借用）；BULK 仅 SHARED——保留容量不可被新增
 * 低等级工作占满，借用只在段空闲时发生，需求返回即停止新增借用。
 * </p>
 */
public enum ResourceSegmentEnum {

    PROD_RESERVED("PROD_RESERVED"),
    OA_RESERVED("OA_RESERVED"),
    SHARED("SHARED");

    private final String code;

    ResourceSegmentEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static Optional<ResourceSegmentEnum> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        for (ResourceSegmentEnum value : values()) {
            if (value.code.equals(code)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /**
     * 按类别给出段占用优先序（段满才尝试下一段）。
     * BULK 只允许 SHARED（不进入任何保留段）。
     */
    public static java.util.List<ResourceSegmentEnum> preference(ResourceClassEnum resourceClass) {
        return switch (resourceClass) {
            case PROD -> java.util.List.of(SHARED, PROD_RESERVED, OA_RESERVED);
            case OA -> java.util.List.of(SHARED, OA_RESERVED, PROD_RESERVED);
            case BULK -> java.util.List.of(SHARED);
        };
    }

    /**
     * 该段是否为该类别「借用」的对方保留段（PROD 借 OA_RESERVED / OA 借 PROD_RESERVED）。
     * 借用只在段空闲时发生：调用方对该段必须先做非阻塞空闲探测，被持有即放弃本次借用，
     * 不得等待（对方在途事务与借用方互等会成环，且借用会挤占对方类别的保留通道）。
     */
    public static boolean isBorrow(ResourceClassEnum resourceClass, ResourceSegmentEnum segment) {
        return switch (resourceClass) {
            case PROD -> segment == OA_RESERVED;
            case OA -> segment == PROD_RESERVED;
            case BULK -> false;
        };
    }
}

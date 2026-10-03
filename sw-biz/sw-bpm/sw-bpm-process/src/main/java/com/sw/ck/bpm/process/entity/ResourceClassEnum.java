package com.sw.ck.bpm.process.entity;

/**
 * 资源类别（受理时冻结到命令行）。
 * <p>
 * PROD：生产类持久工作（流程发起/生产轻流程，含 P0 通道审批受理）；
 * OA：普通 OA 审批受理（NORMAL 通道）；BULK：后台批量（按项计费）。
 * 实时动作不占持久排队额度，由实时并发预算单独约束。
 * </p>
 */
public enum ResourceClassEnum {

    PROD("PROD"),
    OA("OA"),
    BULK("BULK");

    private final String code;

    ResourceClassEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 由存储值解析；未知值返回 empty（旧数据/未来类别不猜语义）。 */
    public static java.util.Optional<ResourceClassEnum> of(String code) {
        if (code == null || code.isBlank()) {
            return java.util.Optional.empty();
        }
        for (ResourceClassEnum value : values()) {
            if (value.code.equals(code)) {
                return java.util.Optional.of(value);
            }
        }
        return java.util.Optional.empty();
    }
}

package com.sw.ck.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应包。
 *
 * <p><b>兼容契约（P61 §3.1）</b>：{@code code}、{@code msg}、{@code data} 三个既有字段
 * 不删除、不改名、不改语义，旧调用方可继续只读这三者。本轮不把原始诊断详情加入 {@code msg}。</p>
 *
 * <p><b>新增机器契约</b>：{@code errorKey} 是全局唯一的语义错误标识（数值 {@code code}
 * 存在跨模块重复，不能用于分流）；{@code eventRef} 是可供用户报出、并能在服务端诊断记录
 * 中定位同一事件的事件引用。两者仅失败时出现，成功响应保持与 0.1.0 完全一致的字节形状。</p>
 */
@Data
public class R<T> implements Serializable {

    public static final int SUCCESS_CODE = 0;
    public static final int FAIL_CODE = 1;

    private int code;
    private String msg;
    private T data;

    /** 全局唯一语义错误标识；仅失败时非空。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String errorKey;

    /** 事件引用；仅失败时非空，与服务端访问/诊断日志可关联。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String eventRef;

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(SUCCESS_CODE);
        r.setMsg("success");
        r.setData(data);
        return r;
    }

    public static <T> R<T> fail(String msg) {
        return fail(FAIL_CODE, msg);
    }

    /**
     * 失败响应。
     *
     * <p>P61：直接返回业务失败（{@code R.fail(code, msg)}）的路径此前不带 errorKey/eventRef，
     * 而抛异常的路径经 {@code GlobalExceptionHandler} 两者齐全——同一个产品里错误契约不一致。
     * 这里统一补齐：{@code eventRef} 取服务端权威引用；{@code errorKey} 按数值码查注册表，
     * 未登记或数值存在已登记冲突时保持 {@code null}（不猜测含义）。</p>
     */
    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>();
        r.setCode(code);
        String errorKey = com.sw.ck.common.exception.ErrorKeyRegistry.lookup(code);
        // P61：文案权威在目录；调用方传入的字面量只作兜底（未登记 key 时保持原文）。
        r.setMsg(com.sw.ck.common.i18n.LocalizedMessages.text(errorKey, msg));
        r.setEventRef(com.sw.ck.common.trace.EventRef.current());
        r.setErrorKey(errorKey);
        return r;
    }

    /** 携带语义标识与事件引用的失败响应；{@code errorKey}/{@code eventRef} 允许为 null。 */
    public static <T> R<T> fail(int code, String errorKey, String msg, String eventRef) {
        R<T> r = fail(code, msg);
        r.setErrorKey(errorKey);
        r.setEventRef(eventRef);
        return r;
    }

    /**
     * 调用方已完成目录+参数解析的失败响应（P61 R3）。
     *
     * <p>与 {@link #fail(int, String, String, String)} 的区别：不再按 {@code error.key}
     * 二次解析文案。异常处理器在抛出侧携带目录参数（字段显示名等业务细节）时已解析出最终
     * 用户文案，若这里再查一次目录，无参的通用条目会把刚拼好的细节整体覆盖掉——这正是
     * 「显隐规则拒绝看不到是哪个字段」问题的最后一环。</p>
     */
    public static <T> R<T> failResolved(int code, String errorKey, String msg, String eventRef) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setErrorKey(errorKey);
        r.setEventRef(eventRef);
        return r;
    }
}

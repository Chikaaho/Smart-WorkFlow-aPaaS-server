package com.sw.ck.iot.script.api;

import java.util.Map;

/**
 * Java 受控脚本入口契约。
 * <p>
 * 管理员提交的 Java 脚本必须提供实现本接口的公共类，
 * 在与主业务 JVM 隔离的子进程中执行，仅能经 {@link IotScriptApi} 访问平台能力。
 * </p>
 */
public interface IotJavaScript {

    /**
     * 脚本执行入口。
     *
     * @param api   宿主函数（fun_*）
     * @param input 触发输入（消息/事件/规则上下文）
     * @return 输出对象（JSON 序列化后落运行记录）
     */
    Object execute(IotScriptApi api, Map<String, Object> input);
}

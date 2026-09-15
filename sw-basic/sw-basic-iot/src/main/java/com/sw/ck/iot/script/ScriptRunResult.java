package com.sw.ck.iot.script;

import java.util.List;

/**
 * 受控脚本执行结果。
 */
public class ScriptRunResult {

    private String status;
    private String outputJson;
    private String error;
    private long durationMs;
    private List<String> logs;

    public static ScriptRunResult success(String outputJson, long durationMs, List<String> logs) {
        ScriptRunResult r = new ScriptRunResult();
        r.status = "SUCCESS";
        r.outputJson = outputJson;
        r.durationMs = durationMs;
        r.logs = logs;
        return r;
    }

    public static ScriptRunResult failure(String error, long durationMs, List<String> logs) {
        ScriptRunResult r = new ScriptRunResult();
        r.status = "FAILED";
        r.error = error;
        r.durationMs = durationMs;
        r.logs = logs;
        return r;
    }

    public static ScriptRunResult timeout(String error, long durationMs, List<String> logs) {
        ScriptRunResult r = new ScriptRunResult();
        r.status = "TIMEOUT";
        r.error = error;
        r.durationMs = durationMs;
        r.logs = logs;
        return r;
    }

    public String getStatus() {
        return status;
    }

    public String getOutputJson() {
        return outputJson;
    }

    public String getError() {
        return error;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public List<String> getLogs() {
        return logs;
    }
}

package com.contextfs.core.exception;

/** 链路不存在：按 traceId 未查询到任何 span / 检索日志 / 工具调用日志。 */
public class TraceNotFoundException extends ContextFsException {

    public TraceNotFoundException(String traceId) {
        super("Trace not found: " + traceId);
    }
}

package com.contextfs.core.exception;

/** 跨 scope 访问或越权操作。 */
public class ScopeViolationException extends ContextFsException {

    public ScopeViolationException(String message) {
        super(message);
    }
}

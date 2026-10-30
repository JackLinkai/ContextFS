package com.contextfs.core.exception;

/** ContextFS 领域异常基类。 */
public abstract class ContextFsException extends RuntimeException {

    protected ContextFsException(String message) {
        super(message);
    }

    protected ContextFsException(String message, Throwable cause) {
        super(message, cause);
    }
}

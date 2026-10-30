package com.contextfs.storage;

import com.contextfs.core.exception.ContextFsException;

/** 存储适配层异常：封装底层存储访问失败。 */
public final class StorageException extends ContextFsException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

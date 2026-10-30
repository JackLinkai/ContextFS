package com.contextfs.core.exception;

import com.contextfs.core.uri.CtxfsUri;

/** 节点不存在。 */
public class NodeNotFoundException extends ContextFsException {

    public NodeNotFoundException(CtxfsUri uri) {
        super("Node not found: " + uri);
    }

    public NodeNotFoundException(String message) {
        super(message);
    }
}

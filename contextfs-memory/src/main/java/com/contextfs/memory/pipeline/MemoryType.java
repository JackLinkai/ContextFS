package com.contextfs.memory.pipeline;

/** 记忆类型：偏好 / 经验 / 事实，对应三类提取策略。 */
public enum MemoryType {
    PREFERENCE("偏好"),
    EXPERIENCE("经验"),
    FACT("事实");

    private final String label;

    MemoryType(String label) {
        this.label = label;
    }

    /** 中文标签，用于记忆内容前缀与日志。 */
    public String label() {
        return label;
    }
}

package com.contextfs.memory.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 规则式记忆提取器：按关键词将 user 消息归入偏好 / 经验 / 事实三类。
 *
 * <p>一条消息命中多类关键词时按 偏好 → 经验 → 事实 优先级归入一类；过短消息（少于 6 字符） 忽略；每类候选最多 {@link #MAX_PER_TYPE} 条。后续接入 LLM
 * 后可替换为语义提取。
 */
@Component
public class MemoryExtractor {

    /** 每类记忆单次提交的最大提取条数。 */
    static final int MAX_PER_TYPE = 3;

    /** 低于该长度的消息不提取。 */
    static final int MIN_CONTENT_LENGTH = 6;

    private static final Map<MemoryType, List<String>> KEYWORDS =
            Map.of(
                    MemoryType.PREFERENCE,
                    List.of("偏好", "喜欢", "不喜欢", "以后", "尽量", "简洁", "详细", "风格", "主题"),
                    MemoryType.EXPERIENCE,
                    List.of("经验", "教训", "失败", "解决", "步骤", "报错", "修复", "排查", "踩坑"),
                    MemoryType.FACT,
                    List.of("项目", "使用", "架构", "版本", "部署", "数据库", "环境", "团队", "基于"));

    /**
     * 从会话消息中提取候选记忆。
     *
     * @param messages 会话消息（仅 user 消息参与提取）
     */
    public List<MemoryCandidate> extract(List<SessionMessage> messages) {
        List<MemoryCandidate> result = new ArrayList<>();
        int[] perType = new int[MemoryType.values().length];
        for (SessionMessage message : messages) {
            if (message == null || !"user".equalsIgnoreCase(message.role())) {
                continue;
            }
            String content = message.content() == null ? "" : message.content().trim();
            if (content.length() < MIN_CONTENT_LENGTH) {
                continue;
            }
            MemoryType type = classify(content.toLowerCase(Locale.ROOT));
            if (type == null) {
                continue;
            }
            if (perType[type.ordinal()] >= MAX_PER_TYPE) {
                continue;
            }
            perType[type.ordinal()]++;
            result.add(new MemoryCandidate(type, content));
        }
        return result;
    }

    /** 按 偏好 → 经验 → 事实 顺序分类，无命中返回 null。 */
    private MemoryType classify(String normalizedContent) {
        for (MemoryType type : MemoryType.values()) {
            for (String keyword : KEYWORDS.get(type)) {
                if (normalizedContent.contains(keyword)) {
                    return type;
                }
            }
        }
        return null;
    }
}

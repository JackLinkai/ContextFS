package com.contextfs.memory.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link MemoryExtractor} 规则提取单测。 */
@DisplayName("MemoryExtractor 规则提取")
class MemoryExtractorTest {

    private final MemoryExtractor extractor = new MemoryExtractor();

    @Test
    @DisplayName("偏好类关键词归入 PREFERENCE")
    void extractsPreference() {
        List<MemoryCandidate> candidates =
                extractor.extract(List.of(new SessionMessage("user", "以后回答尽量简洁，我喜欢简洁风格。")));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).type()).isEqualTo(MemoryType.PREFERENCE);
        assertThat(candidates.get(0).content()).contains("简洁");
    }

    @Test
    @DisplayName("经验类关键词归入 EXPERIENCE")
    void extractsExperience() {
        List<MemoryCandidate> candidates =
                extractor.extract(List.of(new SessionMessage("user", "这次排查报错的步骤是先看日志再复现。")));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).type()).isEqualTo(MemoryType.EXPERIENCE);
    }

    @Test
    @DisplayName("事实类关键词归入 FACT")
    void extractsFact() {
        List<MemoryCandidate> candidates =
                extractor.extract(List.of(new SessionMessage("user", "我们的项目数据库使用 PostgreSQL 16。")));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).type()).isEqualTo(MemoryType.FACT);
    }

    @Test
    @DisplayName("assistant 消息与过短消息不参与提取")
    void skipsAssistantAndShortMessages() {
        List<MemoryCandidate> candidates =
                extractor.extract(
                        List.of(
                                new SessionMessage("assistant", "好的，我会保持简洁风格。"),
                                new SessionMessage("user", "好的"),
                                new SessionMessage(null, "以后尽量详细一点。")));

        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("一条消息只归入一类，按 偏好→经验→事实 优先级")
    void classifiesSingleTypePerMessage() {
        List<MemoryCandidate> candidates =
                extractor.extract(List.of(new SessionMessage("user", "以后尽量简洁，我们的项目使用 Java。")));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).type()).isEqualTo(MemoryType.PREFERENCE);
    }

    @Test
    @DisplayName("每类候选最多 3 条")
    void capsPerType() {
        List<SessionMessage> messages =
                List.of(
                        new SessionMessage("user", "以后回答尽量简洁一些。"),
                        new SessionMessage("user", "我喜欢暗色主题风格。"),
                        new SessionMessage("user", "我不喜欢太啰嗦的回答方式。"),
                        new SessionMessage("user", "代码示例尽量使用最新版本语法。"));

        List<MemoryCandidate> candidates = extractor.extract(messages);

        long preferenceCount =
                candidates.stream().filter(c -> c.type() == MemoryType.PREFERENCE).count();
        assertThat(preferenceCount).isLessThanOrEqualTo(3);
    }
}

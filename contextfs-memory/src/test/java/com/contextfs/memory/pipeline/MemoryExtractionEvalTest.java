package com.contextfs.memory.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 记忆提取准确率评测：15 条标注 user 消息（偏好 / 经验 / 事实 各 5 条）+ 5 条干扰消息。
 *
 * <p>评测对象为规则式 {@link MemoryExtractor}：仅 user 角色、长度 ≥ 6 字符参与提取， 按 偏好 → 经验 → 事实
 * 优先级分类。标注样本按规则设计避免跨类关键词碰撞（经验样本不含偏好关键词， 事实样本不含偏好与经验关键词）；干扰样本覆盖过短、无关键词、非 user 角色、空白四类应被忽略的情形。
 * 逐条提取（规避每类单次最多 3 条的上限），准确率断言 ≥ 0.9，明细经 Jackson 序列化写入 {@code
 * target/memory-eval-accuracy.json}（保证引号与反斜杠正确转义），供接入 LLM 语义提取后对比。
 */
@DisplayName("记忆提取准确率评测集")
class MemoryExtractionEvalTest {

    private static final MemoryExtractor EXTRACTOR = new MemoryExtractor();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 标注样本：消息内容 → 期望类型。 */
    private record Labeled(String content, MemoryType expected) {}

    private static final List<Labeled> LABELED =
            List.of(
                    // 偏好 5 条
                    new Labeled("我偏好使用暗色主题的界面。", MemoryType.PREFERENCE),
                    new Labeled("以后回答尽量简洁一点。", MemoryType.PREFERENCE),
                    new Labeled("我喜欢用 Markdown 格式写文档。", MemoryType.PREFERENCE),
                    new Labeled("回复风格保持简明扼要，不要长篇大论。", MemoryType.PREFERENCE),
                    new Labeled("界面主题建议固定为浅色模式。", MemoryType.PREFERENCE),
                    // 经验 5 条（不含偏好关键词）
                    new Labeled("上次构建失败是因为依赖版本冲突，降级后解决了。", MemoryType.EXPERIENCE),
                    new Labeled("排查接口超时的经验：先看线程池是否打满。", MemoryType.EXPERIENCE),
                    new Labeled("遇到报错先查日志再定位堆栈，这次踩坑花了两个小时。", MemoryType.EXPERIENCE),
                    new Labeled("修复慢查询的办法是给过滤字段加索引。", MemoryType.EXPERIENCE),
                    new Labeled("部署失败的教训：发布前必须清空本地缓存。", MemoryType.EXPERIENCE),
                    // 事实 5 条（不含偏好与经验关键词）
                    new Labeled("本项目采用 Java 21 与 Spring Boot 架构。", MemoryType.FACT),
                    new Labeled("数据库当前使用 PostgreSQL 16 版本。", MemoryType.FACT),
                    new Labeled("团队目前有六名后端工程师。", MemoryType.FACT),
                    new Labeled("生产环境部署在华东区域的 Kubernetes 集群。", MemoryType.FACT),
                    new Labeled("网关基于 Spring Cloud Gateway 构建。", MemoryType.FACT));

    /** 干扰样本：过短 / 无关键词 / 非 user 角色 / 空白，均应被忽略。 */
    private static final List<SessionMessage> DISTRACTORS =
            List.of(
                    new SessionMessage("user", "好的"),
                    new SessionMessage("user", "收到，马上处理。"),
                    new SessionMessage("user", "今天天气怎么样？"),
                    new SessionMessage("assistant", "我偏好简洁回复。"),
                    new SessionMessage("user", "   "));

    @Test
    @DisplayName("评测：分类准确率 ≥ 0.9、干扰样本全部忽略，输出明细文件")
    void evaluate() throws Exception {
        int correct = 0;
        int[] totalPerType = new int[MemoryType.values().length];
        int[] correctPerType = new int[MemoryType.values().length];
        List<Map<String, Object>> perSample = new ArrayList<>();

        for (Labeled labeled : LABELED) {
            List<MemoryCandidate> candidates =
                    EXTRACTOR.extract(List.of(new SessionMessage("user", labeled.content())));
            MemoryType actual = candidates.isEmpty() ? null : candidates.get(0).type();
            boolean hit = actual == labeled.expected();
            if (hit) {
                correct++;
                correctPerType[labeled.expected().ordinal()]++;
            }
            totalPerType[labeled.expected().ordinal()]++;
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("content", labeled.content());
            sample.put("expected", labeled.expected().name());
            sample.put("actual", actual == null ? null : actual.name());
            sample.put("correct", hit);
            perSample.add(sample);
        }

        double accuracy = (double) correct / LABELED.size();
        int kept = EXTRACTOR.extract(DISTRACTORS).size();
        int ignored = DISTRACTORS.size() - kept;

        System.out.printf(
                "记忆提取评测：样本=%d，正确=%d，准确率=%.2f，干扰样本=%d（忽略=%d）%n",
                LABELED.size(), correct, accuracy, DISTRACTORS.size(), ignored);

        assertThat(accuracy).as("分类准确率应不低于 0.9").isGreaterThanOrEqualTo(0.9);
        assertThat(ignored).as("干扰样本应全部被忽略").isEqualTo(DISTRACTORS.size());

        List<Map<String, Object>> perType = new ArrayList<>();
        for (MemoryType type : MemoryType.values()) {
            Map<String, Object> typeStat = new LinkedHashMap<>();
            typeStat.put("type", type.name());
            typeStat.put("total", totalPerType[type.ordinal()]);
            typeStat.put("correct", correctPerType[type.ordinal()]);
            perType.add(typeStat);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("labeledSamples", LABELED.size());
        report.put("correct", correct);
        report.put("accuracy", accuracy);
        report.put("distractors", DISTRACTORS.size());
        report.put("distractorsIgnored", ignored);
        report.put("perType", perType);
        report.put("perSample", perSample);

        Path output = Path.of("target", "memory-eval-accuracy.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }
}

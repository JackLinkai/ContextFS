package com.contextfs.storage.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.contextfs.core.model.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LangChain4jLlmClient")
class LangChain4jLlmClientTest {

    private final LangChain4jLlmClient client =
            new LangChain4jLlmClient("http://localhost:9999/v1", "test-key", "test-model", null);

    @Test
    @DisplayName("summarize 仅支持 L0/L1，L2 直接拒绝")
    void rejectL2() {
        assertThatThrownBy(() -> client.summarize("内容", Level.L2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("提示词模板：L0 限制 100 token，L1 限制 2000 token")
    void promptTemplates() {
        assertThat(String.format(LangChain4jLlmClient.L0_PROMPT, "abc")).contains("100 token");
        assertThat(String.format(LangChain4jLlmClient.L1_PROMPT, "abc")).contains("2000 token");
    }

    @Test
    @DisplayName("构造参数缺失时快速失败")
    void requireArguments() {
        assertThatThrownBy(() -> new LangChain4jLlmClient(null, "k", "m", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LangChain4jLlmClient("http://x", null, "m", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LangChain4jLlmClient("http://x", "k", null, null))
                .isInstanceOf(NullPointerException.class);
    }
}

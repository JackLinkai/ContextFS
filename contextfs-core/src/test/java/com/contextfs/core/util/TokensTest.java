package com.contextfs.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link Tokens} 单元测试。 */
class TokensTest {

    @Test
    @DisplayName("null 与空串估算为 0")
    void estimateNullOrBlank() {
        assertThat(Tokens.estimate(null)).isZero();
        assertThat(Tokens.estimate("")).isZero();
    }

    @Test
    @DisplayName("CJK 字符按 1 token 计")
    void estimateCjk() {
        assertThat(Tokens.estimate("用户偏好")).isEqualTo(4);
    }

    @Test
    @DisplayName("非 CJK 字符按每 4 字符 1 token 计")
    void estimateAscii() {
        assertThat(Tokens.estimate("abcdefgh")).isEqualTo(2);
        assertThat(Tokens.estimate("abc")).isEqualTo(1);
    }

    @Test
    @DisplayName("混合文本按类别分别累计")
    void estimateMixed() {
        // 2 个 CJK + 8 个 ASCII = 2 + 2
        assertThat(Tokens.estimate("中文abcdefgh")).isEqualTo(4);
    }
}

package com.contextfs.core.util;

/**
 * 粗略 token 估算工具：CJK 字符按 1 token，其余按每 4 字符 1 token。
 *
 * <p>供层级内容生成与检索的 token 预算控制使用；后续接入真实分词器时替换实现即可。
 */
public final class Tokens {

    private Tokens() {}

    /** 估算文本 token 数，null 或空串返回 0。 */
    public static int estimate(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }
}

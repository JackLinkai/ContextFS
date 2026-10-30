package com.contextfs.storage.milvus;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextfs.core.model.Level;
import com.contextfs.core.uri.CtxfsUri;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MilvusVectorStore 静态辅助方法")
class MilvusVectorStoreTest {

    @Nested
    @DisplayName("分区名")
    class PartitionName {

        @Test
        @DisplayName("global scope 映射为 p_global")
        void globalScope() {
            assertThat(MilvusVectorStore.partitionName("global")).isEqualTo("p_global");
        }

        @Test
        @DisplayName("scope 中的 '/' 替换为 '_'")
        void slashReplaced() {
            assertThat(MilvusVectorStore.partitionName("user/10001")).isEqualTo("p_user_10001");
            assertThat(MilvusVectorStore.partitionName("session/abc-123"))
                    .isEqualTo("p_session_abc-123");
        }
    }

    @Nested
    @DisplayName("主键")
    class PrimaryKey {

        @Test
        @DisplayName("uri + '#' + 层级名")
        void composed() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://user/10001/docs/api.md");
            assertThat(MilvusVectorStore.primaryKey(uri, Level.L0))
                    .isEqualTo("ctxfs://user/10001/docs/api.md#L0");
        }
    }

    @Nested
    @DisplayName("过滤表达式")
    class FilterExpression {

        @Test
        @DisplayName("scope 与 level 均为空时返回空串")
        void empty() {
            assertThat(MilvusVectorStore.buildFilter(null, null)).isEmpty();
        }

        @Test
        @DisplayName("仅 scope")
        void scopeOnly() {
            assertThat(MilvusVectorStore.buildFilter("user/10001", null))
                    .isEqualTo("scope == \"user/10001\"");
        }

        @Test
        @DisplayName("仅 level")
        void levelOnly() {
            assertThat(MilvusVectorStore.buildFilter(null, Level.L0)).isEqualTo("level == \"L0\"");
        }

        @Test
        @DisplayName("scope 与 level 同时过滤")
        void combined() {
            assertThat(MilvusVectorStore.buildFilter("global", Level.L1))
                    .isEqualTo("scope == \"global\" && level == \"L1\"");
        }
    }

    @Nested
    @DisplayName("表达式转义")
    class Escape {

        @Test
        @DisplayName("双引号与反斜杠被转义")
        void escaped() {
            assertThat(MilvusVectorStore.escapeExpr("a\"b\\c")).isEqualTo("a\\\"b\\\\c");
        }
    }
}

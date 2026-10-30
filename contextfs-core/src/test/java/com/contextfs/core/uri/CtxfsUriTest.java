package com.contextfs.core.uri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.contextfs.core.exception.InvalidUriException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CtxfsUriTest {

    @Nested
    @DisplayName("parse 合法输入")
    class ParseValid {

        @Test
        void shouldParseGlobalRoot() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global");
            assertThat(uri.scope()).isEqualTo("global");
            assertThat(uri.path()).isEmpty();
            assertThat(uri.isRoot()).isTrue();
            assertThat(uri.depth()).isZero();
            assertThat(uri.name()).isEqualTo("global");
            assertThat(uri.parent()).isEmpty();
            assertThat(uri.toString()).isEqualTo("ctxfs://global");
        }

        @Test
        void shouldParseGlobalPath() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/skills/search/web_search.md");
            assertThat(uri.scope()).isEqualTo("global");
            assertThat(uri.path()).isEqualTo("skills/search/web_search.md");
            assertThat(uri.depth()).isEqualTo(3);
            assertThat(uri.name()).isEqualTo("web_search.md");
        }

        @Test
        void shouldParseUserScopeRoot() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://user/10001");
            assertThat(uri.scope()).isEqualTo("user/10001");
            assertThat(uri.path()).isEmpty();
            assertThat(uri.isRoot()).isTrue();
            assertThat(uri.name()).isEqualTo("10001");
            assertThat(uri.toString()).isEqualTo("ctxfs://user/10001");
        }

        @Test
        void shouldParseUserScopeWithPath() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://user/10001/memories/preferences.md");
            assertThat(uri.scope()).isEqualTo("user/10001");
            assertThat(uri.path()).isEqualTo("memories/preferences.md");
            assertThat(uri.parent()).hasValue(CtxfsUri.parse("ctxfs://user/10001/memories"));
        }

        @Test
        void shouldParseAgentAndSessionScopes() {
            assertThat(CtxfsUri.parse("ctxfs://agent/coder/resources/java/concurrency.md").scope())
                    .isEqualTo("agent/coder");
            assertThat(
                            CtxfsUri.parse(
                                            "ctxfs://session/sess_abc123/context/turns/0001.md")
                                    .scope())
                    .isEqualTo("session/sess_abc123");
        }

        @Test
        void shouldCollapseRedundantSeparators() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global//skills///search/");
            assertThat(uri.path()).isEqualTo("skills/search");
        }

        @Test
        void shouldRoundTripToString() {
            for (String raw :
                    new String[] {
                        "ctxfs://global", "ctxfs://user/10001/memories/preferences.md",
                    }) {
                assertThat(CtxfsUri.parse(raw).toString()).isEqualTo(raw);
            }
        }
    }

    @Nested
    @DisplayName("parse 非法输入")
    class ParseInvalid {

        @Test
        void shouldRejectBlankAndWrongScheme() {
            assertThatThrownBy(() -> CtxfsUri.parse(null)).isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.parse("  ")).isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.parse("http://global"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.parse("ctxfs:/global"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.parse("ctxfs://"))
                    .isInstanceOf(InvalidUriException.class);
        }

        @Test
        void shouldRejectUnknownScopeKind() {
            assertThatThrownBy(() -> CtxfsUri.parse("ctxfs://other/1"))
                    .isInstanceOf(InvalidUriException.class)
                    .hasMessageContaining("other");
        }

        @Test
        void shouldRejectScopedKindWithoutId() {
            assertThatThrownBy(() -> CtxfsUri.parse("ctxfs://user"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.parse("ctxfs://user/"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.parse("ctxfs://agent/"))
                    .isInstanceOf(InvalidUriException.class);
        }

        @Test
        void shouldRejectPathTraversal() {
            assertThatThrownBy(() -> CtxfsUri.parse("ctxfs://global/../etc"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.parse("ctxfs://global/a/../b"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.parse("ctxfs://global/a/./b"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.parse("ctxfs://global/.."))
                    .isInstanceOf(InvalidUriException.class);
        }

        @Test
        void shouldRejectIllegalCharacters() {
            assertThatThrownBy(() -> CtxfsUri.parse("ctxfs://global/a\\b"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.parse("ctxfs://user/100\u00001/x"))
                    .isInstanceOf(InvalidUriException.class);
        }

        @Test
        void shouldRejectPathExceedingDepthLimit() {
            StringBuilder deep = new StringBuilder("ctxfs://global");
            for (int i = 0; i <= CtxfsUri.MAX_PATH_DEPTH; i++) {
                deep.append("/seg").append(i);
            }
            assertThatThrownBy(() -> CtxfsUri.parse(deep.toString()))
                    .isInstanceOf(InvalidUriException.class)
                    .hasMessageContaining("depth");
        }

        @Test
        void shouldAcceptPathAtDepthLimit() {
            StringBuilder deep = new StringBuilder("ctxfs://global");
            for (int i = 0; i < CtxfsUri.MAX_PATH_DEPTH; i++) {
                deep.append("/seg").append(i);
            }
            assertThat(CtxfsUri.parse(deep.toString()).depth())
                    .isEqualTo(CtxfsUri.MAX_PATH_DEPTH);
        }
    }

    @Nested
    @DisplayName("of 工厂方法")
    class OfFactory {

        @Test
        void shouldValidateScope() {
            assertThatThrownBy(() -> CtxfsUri.of(null, "a"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.of(" ", "a"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.of("tenant/1", "a"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.of("user", "a"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.of("user/", "a"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.of("user/1/2", "a"))
                    .isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> CtxfsUri.of("user/1\u0000", "a"))
                    .isInstanceOf(InvalidUriException.class);
        }

        @Test
        void shouldTreatNullPathAsRoot() {
            CtxfsUri uri = CtxfsUri.of("global", null);
            assertThat(uri.isRoot()).isTrue();
            assertThat(uri.path()).isEmpty();
        }

        @Test
        void shouldNormalizePath() {
            assertThat(CtxfsUri.of("global", "/a//b/").path()).isEqualTo("a/b");
        }
    }

    @Nested
    @DisplayName("值对象行为")
    class ValueObject {

        @Test
        void shouldBuildChildUri() {
            CtxfsUri root = CtxfsUri.parse("ctxfs://user/10001");
            assertThat(root.child("memories").toString())
                    .isEqualTo("ctxfs://user/10001/memories");
            assertThat(root.child("memories").child("preferences.md").toString())
                    .isEqualTo("ctxfs://user/10001/memories/preferences.md");
        }

        @Test
        void shouldRejectInvalidChildName() {
            CtxfsUri root = CtxfsUri.parse("ctxfs://global");
            assertThatThrownBy(() -> root.child("")).isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> root.child("a/b")).isInstanceOf(InvalidUriException.class);
            assertThatThrownBy(() -> root.child(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldWalkParentsToRoot() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://agent/coder/a/b/c.md");
            assertThat(uri.parent()).hasValue(CtxfsUri.parse("ctxfs://agent/coder/a/b"));
            assertThat(uri.parent().flatMap(CtxfsUri::parent))
                    .hasValue(CtxfsUri.parse("ctxfs://agent/coder/a"));
            assertThat(uri.parent().flatMap(CtxfsUri::parent).flatMap(CtxfsUri::parent))
                    .hasValue(CtxfsUri.parse("ctxfs://agent/coder"));
            assertThat(
                            uri.parent()
                                    .flatMap(CtxfsUri::parent)
                                    .flatMap(CtxfsUri::parent)
                                    .flatMap(CtxfsUri::parent))
                    .isEmpty();
        }

        @Test
        void shouldRespectEqualsAndHashCode() {
            CtxfsUri a = CtxfsUri.parse("ctxfs://global/a//b");
            CtxfsUri b = CtxfsUri.parse("ctxfs://global/a/b/");
            CtxfsUri c = CtxfsUri.parse("ctxfs://global/a/b");
            assertThat(a).isEqualTo(b).isEqualTo(c);
            assertThat(a.hashCode()).isEqualTo(c.hashCode());
            assertThat(a).isNotEqualTo(CtxfsUri.parse("ctxfs://user/1/a/b"));
        }

        @Test
        void shouldSupportUnicodeNames() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://user/10001/记忆/偏好.md");
            assertThat(uri.name()).isEqualTo("偏好.md");
            assertThat(uri.depth()).isEqualTo(2);
        }
    }
}

package com.contextfs.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contextfs.api.common.GlobalExceptionHandler;
import com.contextfs.api.fs.FileSystemService;
import com.contextfs.core.inmem.InMemoryCacheStore;
import com.contextfs.core.inmem.InMemoryNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** FsController 接口契约测试：InMemory 装配，不依赖外部服务。 */
@DisplayName("FsController REST 接口")
class FsControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InMemoryNodeRepository repository = new InMemoryNodeRepository();
        FileSystemService service =
                new FileSystemService(
                        repository,
                        new InMemoryCacheStore(),
                        new ObjectMapper(),
                        Duration.ofMinutes(10));
        mockMvc =
                MockMvcBuilders.standaloneSetup(new FsController(service))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    @DisplayName("POST /fs/write 返回统一响应体与节点信息")
    void write() throws Exception {
        String body =
                """
                {
                  "uri": "ctxfs://user/10001/memories/preferences.md",
                  "content": "用户偏好简洁回答，代码示例优先使用 Java。",
                  "type": "MEMORY",
                  "generateLevels": true
                }
                """;

        mockMvc.perform(
                        post("/api/v1/fs/write")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(
                        jsonPath("$.data.uri").value("ctxfs://user/10001/memories/preferences.md"))
                .andExpect(jsonPath("$.data.type").value("MEMORY"))
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());
    }

    @Test
    @DisplayName("POST /fs/write 参数校验失败返回 400")
    void writeValidationFailure() throws Exception {
        String body = "{\"uri\": \"\", \"content\": \"内容\"}";

        mockMvc.perform(
                        post("/api/v1/fs/write")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /fs/write 非法 URI 返回 400")
    void writeInvalidUri() throws Exception {
        String body = "{\"uri\": \"ctxfs://unknown/x\", \"content\": \"内容\"}";

        mockMvc.perform(
                        post("/api/v1/fs/write")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("GET /fs/read 返回 L2 原文，L0 为占位")
    void readLevels() throws Exception {
        mockMvc.perform(
                        post("/api/v1/fs/write")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"uri": "ctxfs://global/docs/java.md", "content": "并发编程要点"}
                                        """))
                .andExpect(status().isOk());

        String l2Body =
                mockMvc.perform(get("/api/v1/fs/read").param("uri", "ctxfs://global/docs/java.md"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(l2Body)
                .contains("\"code\":0")
                .contains("\"level\":\"L2\"")
                .contains("并发编程要点")
                .contains("\"generated\":true");

        String l0Body =
                mockMvc.perform(
                                get("/api/v1/fs/read")
                                        .param("uri", "ctxfs://global/docs/java.md")
                                        .param("level", "L0"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(l0Body).contains("\"level\":\"L0\"").contains("\"generated\":false");
    }

    @Test
    @DisplayName("GET /fs/read 节点不存在返回 404")
    void readNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/fs/read").param("uri", "ctxfs://global/none/a.md"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("GET /fs/read level 非法返回 400")
    void readInvalidLevel() throws Exception {
        mockMvc.perform(
                        get("/api/v1/fs/read")
                                .param("uri", "ctxfs://global/docs/a.md")
                                .param("level", "L9"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /fs/ls 返回直接子节点列表")
    void ls() throws Exception {
        mockMvc.perform(
                        post("/api/v1/fs/write")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"uri": "ctxfs://user/10001/memories/preferences.md", "content": "内容"}
                                        """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/fs/ls").param("uri", "ctxfs://user/10001/memories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("preferences.md"))
                .andExpect(jsonPath("$.data[0].type").value("DOCUMENT"));
    }

    @Test
    @DisplayName("GET /fs/tree 返回嵌套目录树")
    void tree() throws Exception {
        write("ctxfs://user/10001/memories/preferences.md");
        write("ctxfs://user/10001/notes/a.md");

        mockMvc.perform(
                        get("/api/v1/fs/tree")
                                .param("uri", "ctxfs://user/10001")
                                .param("depth", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uri").value("ctxfs://user/10001"))
                .andExpect(jsonPath("$.data.children.length()").value(2));
    }

    @Test
    @DisplayName("GET /fs/find 按 keyword 与 type 过滤")
    void find() throws Exception {
        mockMvc.perform(
                        post("/api/v1/fs/write")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"uri": "ctxfs://global/skills/search/web_search.md", "content": "内容", "type": "SKILL"}
                                        """))
                .andExpect(status().isOk());
        write("ctxfs://global/docs/java.md");

        mockMvc.perform(
                        get("/api/v1/fs/find")
                                .param("uri", "ctxfs://global")
                                .param("keyword", "web_search.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("web_search.md"));

        mockMvc.perform(
                        get("/api/v1/fs/find")
                                .param("uri", "ctxfs://global")
                                .param("type", "DOCUMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("java.md"));
    }

    @Test
    @DisplayName("跨 scope 访问（X-ContextFS-Scope 不匹配）返回 403")
    void scopeViolationReturns403() throws Exception {
        write("ctxfs://user/10001/memories/preferences.md");

        mockMvc.perform(
                        get("/api/v1/fs/read")
                                .param("uri", "ctxfs://user/10001/memories/preferences.md")
                                .header(FsController.SCOPE_HEADER, "user/99999"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("scope 匹配时正常访问")
    void scopeMatchAllowed() throws Exception {
        write("ctxfs://user/10001/memories/preferences.md");

        mockMvc.perform(
                        get("/api/v1/fs/read")
                                .param("uri", "ctxfs://user/10001/memories/preferences.md")
                                .header(FsController.SCOPE_HEADER, "user/10001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("缺少必填参数返回 400")
    void missingParam() throws Exception {
        mockMvc.perform(get("/api/v1/fs/read"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("统一响应体结构：成功 code=0，失败 data=null")
    void responseEnvelope() throws Exception {
        String okBody =
                mockMvc.perform(get("/api/v1/fs/ls").param("uri", "ctxfs://global"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(okBody)
                .contains("\"code\":0")
                .contains("\"message\":\"ok\"")
                .contains("\"data\":");
    }

    private void write(String uri) throws Exception {
        mockMvc.perform(
                        post("/api/v1/fs/write")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"uri\": \"" + uri + "\", \"content\": \"内容\"}"))
                .andExpect(status().isOk());
    }
}

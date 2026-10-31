package com.contextfs.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 本机无外部依赖时验证上下文可装配：关闭 Flyway（避免连库执行迁移），PG/Redis 连接均为惰性建立
@SpringBootTest(properties = "spring.flyway.enabled=false")
class ContextFsApplicationTests {

    @Test
    void contextLoads() {}
}

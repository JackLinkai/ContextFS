package com.contextfs.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 扫描 com.contextfs 全部子包，装配 api 模块的 Controller / Service / 异常处理等组件
@SpringBootApplication(scanBasePackages = "com.contextfs")
public class ContextFsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContextFsApplication.class, args);
    }
}

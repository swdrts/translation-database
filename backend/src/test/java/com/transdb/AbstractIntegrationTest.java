package com.transdb;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    /**
     * 每个 JVM 仅启动一次、整个测试套件共享的 PostgreSQL 容器。
     * 不用 @Testcontainers/@Container：那会在每个测试类结束后停掉容器，
     * 而缓存的 Spring 上下文仍指向旧容器（第二个测试类的 health 检查会因数据库 DOWN 而 503）。
     * 静态块启动 + JVM 退出时由 Ryuk 清理，保证全套件共用一个容器与一个 Spring 上下文。
     */
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    protected TestRestTemplate rest;
}

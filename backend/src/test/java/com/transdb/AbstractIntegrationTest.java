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

    @Autowired
    protected com.transdb.security.JwtService jwtService;
    @Autowired
    protected org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired
    protected com.transdb.repository.SysUserRepository userRepository;

    protected com.transdb.domain.SysUser createUser(com.transdb.domain.Role role) {
        var u = new com.transdb.domain.SysUser();
        u.setUsername("u_" + java.util.UUID.randomUUID().toString().substring(0, 10));
        u.setPassword(passwordEncoder.encode("password123"));
        u.setDisplayName("测试用户");
        u.setRole(role);
        u.setStatus(com.transdb.domain.UserStatus.ACTIVE);
        return userRepository.save(u);
    }

    protected String bearer(com.transdb.domain.SysUser u) {
        return "Bearer " + jwtService.generate(
                new com.transdb.security.LoginUser(u.getId(), u.getUsername(),
                        u.getDisplayName(), u.getRole()));
    }
}

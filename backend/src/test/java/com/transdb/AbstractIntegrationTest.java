package com.transdb;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    /**
     * 每个 JVM 仅启动一次、整个测试套件共享的 PostgreSQL 容器。
     * 不用 @Testcontainers/@Container：那会在每个测试类结束后停掉容器，
     * 而缓存的 Spring 上下文仍指向旧容器（第二个测试类的 health 检查会因数据库 DOWN 而 503）。
     * 静态块启动 + JVM 退出时由 Ryuk 清理，保证全套件共用一个容器与一个 Spring 上下文。
     */
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * 与 PG 同一套手动单例模式管理的 Elasticsearch 容器（带 analysis-ik / analysis-pinyin 插件的
     * 自建镜像，见 docker/elasticsearch/Dockerfile）。静态块启动 + JVM 退出时由 Ryuk 清理。
     */
    static final ImageFromDockerfile ES_IMAGE = new ImageFromDockerfile(
            "transdb/elasticsearch-ik-pinyin:8.13.4", false)
            .withFileFromFile("Dockerfile", resolveEsDockerfile());

    static final GenericContainer<?> elasticsearch = new GenericContainer<>(ES_IMAGE)
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(5)));

    static {
        postgres.start();
        elasticsearch.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + elasticsearch.getHost() + ":" + elasticsearch.getMappedPort(9200));
    }

    private static java.io.File resolveEsDockerfile() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        List<Path> candidates = List.of(
                cwd.resolve("docker/elasticsearch/Dockerfile"),
                cwd.resolveSibling("docker/elasticsearch/Dockerfile"));
        return candidates.stream().filter(Files::exists).findFirst()
                .map(Path::toFile)
                .orElseThrow(() -> new IllegalStateException("找不到 docker/elasticsearch/Dockerfile"));
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

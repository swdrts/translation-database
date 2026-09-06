# 后端核心（Backend Core）实施计划 — Plan 1/4

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 Spring Boot 后端核心：数据库迁移、JWT 认证与三角色权限、句段/标签/用户完整 CRUD REST API（搜索与导入在 Plan 2/3）。

**Architecture:** 模块化单体，PG 16 为唯一权威存储（Flyway 管理 schema），事务提交后发布 `SegmentChangedEvent`（Plan 2 的 ES 同步消费此事件）。统一响应体 `{code, message, data}`。

**Tech Stack:** Java 21、Spring Boot 3.3.5、Spring Security（JWT HS256）、Spring Data JPA、Flyway、PostgreSQL 16、Testcontainers、Lombok、jjwt 0.12.6。

## Global Constraints

- 包根 `com.transdb`；Maven `groupId=com.transdb, artifactId=backend`；代码在仓库根 `backend/` 目录
- API 前缀 `/api/v1`；统一响应体 `ApiResponse<T>{code, message, data}`，`code=0` 表示成功
- 角色：`ADMIN`/`EDITOR`/`VIEWER`（Spring Security authority `ROLE_` 前缀）；用户状态 `ACTIVE`/`DISABLED`；条目状态 `DRAFT`/`PUBLISHED`
- JWT：HS256，有效期 24h，密钥来自环境变量 `TRANSDB_JWT_SECRET`（≥32 字节）；密码 BCrypt 强度 10
- 错误码分段（来自设计文档 §9）：1xxx 认证、2xxx 条目、3xxx 导入、5xxx 用户/标签、9xxx 通用（9001 参数校验、9002 资源不存在、9003 服务器内部错误、9004 无权限）
- 所有命令在仓库根目录执行；测试统一用 `mvn test`（Testcontainers 需要本机 Docker 运行中）
- 设计文档：`docs/superpowers/specs/2026-09-06-translation-database-design.md`（权限矩阵见 §6，API 见 §7）
- 分支：`main`；每个任务结束时提交（用户已批准本项目全部 git 操作）
- VIEWER 只能查看 `PUBLISHED` 条目；删除条目仅 ADMIN；用户管理仅 ADMIN

---

### Task 1: 项目脚手架与测试基建

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/transdb/BackendApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/transdb/AbstractIntegrationTest.java`
- Create: `backend/src/test/java/com/transdb/ScaffoldSmokeTest.java`

**Interfaces:**
- Produces: `AbstractIntegrationTest`（后续所有集成测试的基类），提供 Testcontainers PostgreSQL 16（`@ServiceConnection` 自动注入数据源）与 `TestRestTemplate rest`
- Produces: Spring 上下文 + `/actuator/health` 端点

- [ ] **Step 1: 写冒烟测试（先失败）**

`backend/src/test/java/com/transdb/AbstractIntegrationTest.java`：

```java
package com.transdb;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    protected TestRestTemplate rest;
}
```

`backend/src/test/java/com/transdb/ScaffoldSmokeTest.java`：

```java
package com.transdb;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ScaffoldSmokeTest extends AbstractIntegrationTest {

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<String> res = rest.getForEntity("/actuator/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"status\":\"UP\"");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test`
Expected: 编译失败（无 pom.xml 时 `mvn` 报 "no POM in this directory"）

- [ ] **Step 3: 编写 pom.xml、主类、application.yml**

`backend/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>
    <groupId>com.transdb</groupId>
    <artifactId>backend</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>transdb-backend</name>
    <description>翻译学术数据库后端</description>

    <properties>
        <java.version>21</java.version>
        <jjwt.version>0.12.6</jjwt.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>${jjwt.version}</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

`backend/src/main/java/com/transdb/BackendApplication.java`：

```java
package com.transdb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
```

`backend/src/main/resources/application.yml`：

```yaml
spring:
  application:
    name: transdb-backend
  datasource:
    url: ${TRANSDB_DB_URL:jdbc:postgresql://localhost:5432/transdb}
    username: ${TRANSDB_DB_USERNAME:transdb}
    password: ${TRANSDB_DB_PASSWORD:transdb}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true

transdb:
  jwt:
    secret: ${TRANSDB_JWT_SECRET:dev-only-secret-key-change-me-32bytes!}
    expiry-hours: ${TRANSDB_JWT_EXPIRY_HOURS:24}

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test`
Expected: `ScaffoldSmokeTest` PASS（此时无 SecurityConfig，actuator 默认开放；无 Flyway 脚本，DDL validate 无表可校验不报错）

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): 项目脚手架与 Testcontainers 测试基建"
```

---

### Task 2: 数据库迁移与领域模型

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__init.sql`
- Create: `backend/src/main/java/com/transdb/domain/SysUser.java`
- Create: `backend/src/main/java/com/transdb/domain/Segment.java`
- Create: `backend/src/main/java/com/transdb/domain/Tag.java`
- Create: `backend/src/main/java/com/transdb/domain/Role.java`
- Create: `backend/src/main/java/com/transdb/domain/UserStatus.java`
- Create: `backend/src/main/java/com/transdb/domain/SegmentStatus.java`
- Create: `backend/src/main/java/com/transdb/repository/SysUserRepository.java`
- Create: `backend/src/main/java/com/transdb/repository/SegmentRepository.java`
- Create: `backend/src/main/java/com/transdb/repository/TagRepository.java`
- Test: `backend/src/test/java/com/transdb/domain/DomainMigrationTest.java`

**Interfaces:**
- Produces: 实体 `SysUser{id, username, password, displayName, role, status, createdAt, updatedAt}`、`Segment{id, sourceText, translatedText, workTitle, chapter, author, dynasty, translator, notes, status, version, contentHash, createdBy:SysUser, tags:Set<Tag>, createdAt, updatedAt}`、`Tag{id, name, description, createdAt}`
- Produces: 仓储 `SysUserRepository extends JpaRepository<SysUser, Long>`（`Optional<SysUser> findByUsername(String)`）、`TagRepository extends JpaRepository<Tag, Long>`、`SegmentRepository extends JpaRepository<Segment, Long>, JpaSpecificationExecutor<Segment>`
- Produces: 枚举 `Role{ADMIN, EDITOR, VIEWER}`、`UserStatus{ACTIVE, DISABLED}`、`SegmentStatus{DRAFT, PUBLISHED}`

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/domain/DomainMigrationTest.java`：

```java
package com.transdb.domain;

import com.transdb.AbstractIntegrationTest;
import com.transdb.repository.SegmentRepository;
import com.transdb.repository.SysUserRepository;
import com.transdb.repository.TagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainMigrationTest extends AbstractIntegrationTest {

    @Autowired SysUserRepository users;
    @Autowired SegmentRepository segments;
    @Autowired TagRepository tags;

    private SysUser newUser(Role role) {
        SysUser u = new SysUser();
        u.setUsername("tester_" + System.nanoTime());
        u.setPassword("x");
        u.setDisplayName("测试者");
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        return users.save(u);
    }

    @Test
    void saveSegmentWithTagsPersists() {
        SysUser author = newUser(Role.EDITOR);
        Tag t = new Tag();
        t.setName("儒家");
        tags.save(t);

        Segment s = new Segment();
        s.setSourceText("学而时习之");
        s.setTranslatedText("To learn and practice it in due time");
        s.setWorkTitle("论语");
        s.setDynasty("先秦");
        s.setStatus(SegmentStatus.PUBLISHED);
        s.setCreatedBy(author);
        s.setTags(Set.of(t));
        s = segments.save(s);

        Segment loaded = segments.findById(s.getId()).orElseThrow();
        assertThat(loaded.getTags()).extracting(Tag::getName).containsExactly("儒家");
        assertThat(loaded.getCreatedBy().getUsername()).isEqualTo(author.getUsername());
    }

    @Test
    void duplicateTagNameRejected() {
        Tag t = new Tag();
        t.setName("道家");
        tags.save(t);
        Tag dup = new Tag();
        dup.setName("道家");
        assertThatThrownBy(() -> tags.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateUsernameRejected() {
        newUser(Role.VIEWER);
        SysUser dup = new SysUser();
        dup.setUsername(users.findAll().getFirst().getUsername());
        dup.setPassword("x");
        dup.setRole(Role.VIEWER);
        dup.setStatus(UserStatus.ACTIVE);
        assertThatThrownBy(() -> users.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest=DomainMigrationTest`
Expected: 编译失败（domain/repository 类不存在）

- [ ] **Step 3: 写迁移脚本与实体、仓储**

`backend/src/main/resources/db/migration/V1__init.sql`：

```sql
CREATE TABLE sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    display_name VARCHAR(64),
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tag (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE segment (
    id BIGSERIAL PRIMARY KEY,
    source_text TEXT NOT NULL,
    translated_text TEXT NOT NULL,
    work_title VARCHAR(255),
    chapter VARCHAR(255),
    author VARCHAR(255),
    dynasty VARCHAR(64),
    translator VARCHAR(255),
    notes TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
    version INT NOT NULL DEFAULT 0,
    content_hash VARCHAR(64) NOT NULL,
    created_by BIGINT NOT NULL REFERENCES sys_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_segment_content_hash ON segment (content_hash);
CREATE INDEX idx_segment_dynasty ON segment (dynasty);
CREATE INDEX idx_segment_work_title ON segment (work_title);
CREATE INDEX idx_segment_updated_at ON segment (updated_at);
CREATE INDEX idx_segment_status ON segment (status);

CREATE TABLE segment_tag (
    segment_id BIGINT NOT NULL REFERENCES segment(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
    PRIMARY KEY (segment_id, tag_id)
);
```

`backend/src/main/java/com/transdb/domain/Role.java`：

```java
package com.transdb.domain;

public enum Role { ADMIN, EDITOR, VIEWER }
```

`backend/src/main/java/com/transdb/domain/UserStatus.java`：

```java
package com.transdb.domain;

public enum UserStatus { ACTIVE, DISABLED }
```

`backend/src/main/java/com/transdb/domain/SegmentStatus.java`：

```java
package com.transdb.domain;

public enum SegmentStatus { DRAFT, PUBLISHED }
```

`backend/src/main/java/com/transdb/domain/SysUser.java`：

```java
package com.transdb.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "sys_user")
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserStatus status;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
```

`backend/src/main/java/com/transdb/domain/Tag.java`：

```java
package com.transdb.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Getter
@Setter
@Entity
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    private String description;

    @CreationTimestamp
    private Instant createdAt;
}
```

`backend/src/main/java/com/transdb/domain/Segment.java`：

```java
package com.transdb.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Entity
public class Segment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String sourceText;

    @Column(nullable = false, columnDefinition = "text", name = "translated_text")
    private String translatedText;

    private String workTitle;

    private String chapter;

    private String author;

    private String dynasty;

    private String translator;

    @Column(columnDefinition = "text")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SegmentStatus status;

    @Version
    private Integer version;

    @Column(nullable = false, name = "content_hash")
    private String contentHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private SysUser createdBy;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "segment_tag",
            joinColumns = @JoinColumn(name = "segment_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new HashSet<>();

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
```

`backend/src/main/java/com/transdb/repository/SysUserRepository.java`：

```java
package com.transdb.repository;

import com.transdb.domain.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, Long> {
    Optional<SysUser> findByUsername(String username);
}
```

`backend/src/main/java/com/transdb/repository/TagRepository.java`：

```java
package com.transdb.repository;

import com.transdb.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {
    boolean existsByName(String name);
}
```

`backend/src/main/java/com/transdb/repository/SegmentRepository.java`：

```java
package com.transdb.repository;

import com.transdb.domain.Segment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SegmentRepository extends JpaRepository<Segment, Long>,
        JpaSpecificationExecutor<Segment> {
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=DomainMigrationTest`
Expected: 3 个测试 PASS（Flyway 建表成功，`ddl-auto: validate` 校验通过）

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): Flyway 初始迁移与领域模型"
```

---

### Task 3: 统一响应体与全局异常处理

**Files:**
- Create: `backend/src/main/java/com/transdb/common/ApiResponse.java`
- Create: `backend/src/main/java/com/transdb/common/PageResponse.java`
- Create: `backend/src/main/java/com/transdb/common/ErrorCode.java`
- Create: `backend/src/main/java/com/transdb/common/BusinessException.java`
- Create: `backend/src/main/java/com/transdb/common/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/transdb/common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `ApiResponse<T>`（record：`int code, String message, T data`；静态方法 `ok(data)`、`error(int code, String message)`）——所有 Controller 的返回类型
- Produces: `PageResponse<T>`（record：`List<T> content, long total, int page, int size`；静态方法 `of(Page<T>)`）
- Produces: `ErrorCode` 枚举（`HttpStatus status, int code, String defaultMessage`）与 `BusinessException.of(ErrorCode)`、`BusinessException.of(ErrorCode, String customMessage)`——全部业务异常入口
- Produces: `GlobalExceptionHandler`（@RestControllerAdvice）

**错误码全表（后续任务的唯一依据）：**

| ErrorCode | HTTP | code | 默认消息 |
|---|---|---|---|
| WRONG_CREDENTIALS | 401 | 1001 | 用户名或密码错误 |
| UNAUTHORIZED | 401 | 1002 | 未认证 |
| USER_DISABLED | 401 | 1004 | 账号已被禁用 |
| SEGMENT_NOT_FOUND | 404 | 2001 | 条目不存在 |
| OPTIMISTIC_LOCK | 409 | 2002 | 数据已被他人修改，请刷新后重试 |
| SEGMENT_FORBIDDEN | 403 | 2003 | 无权查看该条目 |
| USERNAME_EXISTS | 409 | 5001 | 用户名已存在 |
| TAG_NAME_EXISTS | 409 | 5002 | 标签名称已存在 |
| TAG_NOT_FOUND | 404 | 5003 | 标签不存在 |
| USER_NOT_FOUND | 404 | 5004 | 用户不存在 |
| SELF_MODIFY_FORBIDDEN | 400 | 5005 | 不能修改自己的角色或状态 |
| VALIDATION_FAILED | 400 | 9001 | 参数校验失败 |
| NOT_FOUND | 404 | 9002 | 资源不存在 |
| INTERNAL_ERROR | 500 | 9003 | 服务器内部错误 |
| ACCESS_DENIED | 403 | 9004 | 无权限访问 |

（1003 预留给"令牌无效"，Plan 2 前不使用；3xxx/4xxx 属于导入/搜索，在 Plan 2/3 定义。）

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/common/GlobalExceptionHandlerTest.java`：

```java
package com.transdb.common;

import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandlerTest.ThrowingController.class)
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void businessExceptionMappedToCodeAndStatus() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.message").value("条目不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void validationFailureMappedTo9001() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(9001))
                .andExpect(jsonPath("$.message").value("参数校验失败: name 不能为空"));
    }

    @Test
    void unknownPathMappedTo9002() throws Exception {
        mockMvc.perform(get("/test/never-exists"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(9002));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/test/business")
        public ApiResponse<String> business() {
            throw BusinessException.of(ErrorCode.SEGMENT_NOT_FOUND);
        }

        @PostMapping("/test/validate")
        public ApiResponse<String> validate(@RequestBody @jakarta.validation.Valid ValidDto dto) {
            return ApiResponse.ok("ok");
        }
    }

    record ValidDto(@NotBlank(message = "name 不能为空") String name) {}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest=GlobalExceptionHandlerTest`
Expected: 编译失败（common 包类不存在）

- [ ] **Step 3: 实现 common 包**

`backend/src/main/java/com/transdb/common/ApiResponse.java`：

```java
package com.transdb.common;

public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
```

`backend/src/main/java/com/transdb/common/PageResponse.java`：

```java
package com.transdb.common;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(List<T> content, long total, int page, int size) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getTotalElements(),
                page.getNumber(), page.getSize());
    }
}
```

`backend/src/main/java/com/transdb/common/ErrorCode.java`：

```java
package com.transdb.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    WRONG_CREDENTIALS(HttpStatus.UNAUTHORIZED, 1001, "用户名或密码错误"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, 1002, "未认证"),
    USER_DISABLED(HttpStatus.UNAUTHORIZED, 1004, "账号已被禁用"),
    SEGMENT_NOT_FOUND(HttpStatus.NOT_FOUND, 2001, "条目不存在"),
    OPTIMISTIC_LOCK(HttpStatus.CONFLICT, 2002, "数据已被他人修改，请刷新后重试"),
    SEGMENT_FORBIDDEN(HttpStatus.FORBIDDEN, 2003, "无权查看该条目"),
    USERNAME_EXISTS(HttpStatus.CONFLICT, 5001, "用户名已存在"),
    TAG_NAME_EXISTS(HttpStatus.CONFLICT, 5002, "标签名称已存在"),
    TAG_NOT_FOUND(HttpStatus.NOT_FOUND, 5003, "标签不存在"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, 5004, "用户不存在"),
    SELF_MODIFY_FORBIDDEN(HttpStatus.BAD_REQUEST, 5005, "不能修改自己的角色或状态"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, 9001, "参数校验失败"),
    NOT_FOUND(HttpStatus.NOT_FOUND, 9002, "资源不存在"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, 9003, "服务器内部错误"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, 9004, "无权限访问");

    private final HttpStatus status;
    private final int code;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, int code, String defaultMessage) {
        this.status = status;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() { return status; }
    public int getCode() { return code; }
    public String getDefaultMessage() { return defaultMessage; }
}
```

`backend/src/main/java/com/transdb/common/BusinessException.java`：

```java
package com.transdb.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    private BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static BusinessException of(ErrorCode errorCode) {
        return new BusinessException(errorCode, errorCode.getDefaultMessage());
    }

    public static BusinessException of(ErrorCode errorCode, String message) {
        return new BusinessException(errorCode, message);
    }
}
```

`backend/src/main/java/com/transdb/common/GlobalExceptionHandler.java`：

```java
package com.transdb.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(ApiResponse.error(e.getErrorCode().getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED.getCode(),
                        ErrorCode.VALIDATION_FAILED.getDefaultMessage() + ": " + detail));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.NOT_FOUND.getCode(),
                        ErrorCode.NOT_FOUND.getDefaultMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR.getCode(),
                        ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=GlobalExceptionHandlerTest`
Expected: 3 个测试 PASS

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): 统一响应体、错误码与全局异常处理"
```

---

### Task 4: JWT 服务

**Files:**
- Create: `backend/src/main/java/com/transdb/security/LoginUser.java`
- Create: `backend/src/main/java/com/transdb/security/JwtService.java`
- Test: `backend/src/test/java/com/transdb/security/JwtServiceTest.java`

**Interfaces:**
- Produces: `record LoginUser(long id, String username, String displayName, Role role)`（认证主体，贯穿所有 Controller 的 `@AuthenticationPrincipal`）
- Produces: `JwtService`：
  - `String generate(LoginUser user)` — 签发 token（claims：`sub=username`、`uid`、`displayName`、`role`）
  - `LoginUser parse(String token)` — 解析；过期/篡改抛 `JwtException`
  - 构造：`(String secret, long expiryHours)`（Spring `@Value` 注入）；测试用 `(String secret, Duration expiry)`

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/security/JwtServiceTest.java`：

```java
package com.transdb.security;

import com.transdb.domain.Role;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void generateThenParseRoundTrips() {
        JwtService service = new JwtService(SECRET, Duration.ofHours(24));
        LoginUser in = new LoginUser(42L, "admin", "管理员", Role.ADMIN);

        LoginUser out = service.parse(service.generate(in));

        assertThat(out.id()).isEqualTo(42L);
        assertThat(out.username()).isEqualTo("admin");
        assertThat(out.displayName()).isEqualTo("管理员");
        assertThat(out.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void expiredTokenRejected() {
        JwtService service = new JwtService(SECRET, Duration.ofMillis(-1000));
        String token = service.generate(new LoginUser(1L, "u", "u", Role.VIEWER));
        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedTokenRejected() {
        JwtService service = new JwtService(SECRET, Duration.ofHours(24));
        String token = service.generate(new LoginUser(1L, "u", "u", Role.VIEWER));
        String tampered = token.substring(0, token.length() - 3) + "abc";
        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(JwtException.class);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest=JwtServiceTest`
Expected: 编译失败

- [ ] **Step 3: 实现 LoginUser 与 JwtService**

`backend/src/main/java/com/transdb/security/LoginUser.java`：

```java
package com.transdb.security;

import com.transdb.domain.Role;

public record LoginUser(long id, String username, String displayName, Role role) {
}
```

`backend/src/main/java/com/transdb/security/JwtService.java`：

```java
package com.transdb.security;

import com.transdb.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration expiry;

    public JwtService(@Value("${transdb.jwt.secret}") String secret,
                      @Value("${transdb.jwt.expiry-hours}") long expiryHours) {
        this(secret, Duration.ofHours(expiryHours));
    }

    JwtService(String secret, Duration expiry) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiry = expiry;
    }

    public String generate(LoginUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.username())
                .claim("uid", user.id())
                .claim("displayName", user.displayName())
                .claim("role", user.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(key)
                .compact();
    }

    public LoginUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return new LoginUser(
                claims.get("uid", Long.class),
                claims.getSubject(),
                claims.get("displayName", String.class),
                Role.valueOf(claims.get("role", String.class)));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=JwtServiceTest`
Expected: 3 个测试 PASS

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): JWT 签发与解析服务"
```

---

### Task 5: Security 配置、JWT 过滤器与 admin 初始化

**Files:**
- Create: `backend/src/main/java/com/transdb/security/RestAuthenticationEntryPoint.java`
- Create: `backend/src/main/java/com/transdb/security/RestAccessDeniedHandler.java`
- Create: `backend/src/main/java/com/transdb/security/JwtAuthFilter.java`
- Create: `backend/src/main/java/com/transdb/config/SecurityConfig.java`
- Create: `backend/src/main/java/com/transdb/config/AdminInitializer.java`
- Modify: `backend/src/test/java/com/transdb/AbstractIntegrationTest.java`（追加测试辅助方法）
- Test: `backend/src/test/java/com/transdb/security/SecuritySmokeTest.java`

**Interfaces:**
- Consumes: Task 4 的 `JwtService`/`LoginUser`，Task 3 的 `ErrorCode`/`ApiResponse`
- Produces: SecurityFilterChain——`/api/v1/auth/login` 与 `/actuator/health` 公开，其余需认证；未认证返回 401 JSON `{code:1002}`；`@PreAuthorize` 拒绝返回 403 JSON `{code:9004}`
- Produces: `AdminInitializer`——`sys_user` 空表时创建 `admin`（密码取环境变量 `TRANSDB_ADMIN_PASSWORD`，默认 `admin123`，仅开发用）
- Produces（测试基建，Task 6-9 依赖）: `AbstractIntegrationTest` 中的 `createUser(Role)` 与 `bearer(SysUser)` 辅助方法

- [ ] **Step 1: 写失败测试**

先给 `AbstractIntegrationTest` 追加辅助方法（在该类末尾、`rest` 字段之后追加）：

```java
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
```

`backend/src/test/java/com/transdb/security/SecuritySmokeTest.java`：

```java
package com.transdb.security;

import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import com.transdb.repository.SysUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class SecuritySmokeTest extends AbstractIntegrationTest {

    @Autowired SysUserRepository users;

    @Test
    void protectedEndpointWithoutTokenReturns401Code1002() {
        ResponseEntity<String> res = rest.getForEntity("/api/v1/tags", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("\"code\":1002");
    }

    @Test
    void actuatorHealthIsPublic() {
        ResponseEntity<String> res = rest.getForEntity("/actuator/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void validTokenGrantsAccess() {
        var user = createUser(Role.EDITOR);
        ResponseEntity<String> res = rest.exchange("/api/v1/tags",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<Void>(authHeaders(user)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"code\":0");
    }

    @Test
    void adminInitializedOnStartup() {
        assertThat(users.findByUsername("admin")).isPresent();
    }

    private org.springframework.http.HttpHeaders authHeaders(com.transdb.domain.SysUser u) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("Authorization", bearer(u));
        return headers;
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest=SecuritySmokeTest`
Expected: 编译失败（SecurityConfig 等不存在）

- [ ] **Step 3: 实现安全组件**

`backend/src/main/java/com/transdb/security/RestAuthenticationEntryPoint.java`：

```java
package com.transdb.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transdb.common.ApiResponse;
import com.transdb.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(ErrorCode.UNAUTHORIZED.getCode(),
                        ErrorCode.UNAUTHORIZED.getDefaultMessage())));
    }
}
```

`backend/src/main/java/com/transdb/security/RestAccessDeniedHandler.java`：

```java
package com.transdb.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transdb.common.ApiResponse;
import com.transdb.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(ErrorCode.ACCESS_DENIED.getCode(),
                        ErrorCode.ACCESS_DENIED.getDefaultMessage())));
    }
}
```

`backend/src/main/java/com/transdb/security/JwtAuthFilter.java`：

```java
package com.transdb.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                LoginUser user = jwtService.parse(header.substring(7));
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException ignored) {
                // 无效令牌按未认证处理，由 RestAuthenticationEntryPoint 统一响应
            }
        }
        chain.doFilter(request, response);
    }
}
```

（`JwtException` 需要 `import io.jsonwebtoken.JwtException;`，在 `import java.util.List;` 前补上。）

`backend/src/main/java/com/transdb/config/SecurityConfig.java`：

```java
package com.transdb.config;

import com.transdb.security.JwtAuthFilter;
import com.transdb.security.RestAccessDeniedHandler;
import com.transdb.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RestAuthenticationEntryPoint entryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/login", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
```

`backend/src/main/java/com/transdb/config/AdminInitializer.java`：

```java
package com.transdb.config;

import com.transdb.domain.Role;
import com.transdb.domain.SysUser;
import com.transdb.domain.UserStatus;
import com.transdb.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${TRANSDB_ADMIN_PASSWORD:admin123}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setDisplayName("管理员");
        admin.setRole(Role.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        userRepository.save(admin);
        log.info("已初始化内置管理员账号 admin（密码来自 TRANSDB_ADMIN_PASSWORD，默认 admin123，请在生产环境修改）");
    }
}
```

注意：`@Value("${TRANSDB_ADMIN_PASSWORD:admin123}")` 直接读环境变量（Spring 的 relaxed binding 下环境变量可用作属性）。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=SecuritySmokeTest`
Expected: 4 个测试 PASS

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): JWT 过滤器、Security 配置与内置管理员初始化"
```

---

### Task 6: 认证接口（login / me）

**Files:**
- Create: `backend/src/main/java/com/transdb/dto/LoginRequest.java`
- Create: `backend/src/main/java/com/transdb/dto/LoginVO.java`
- Create: `backend/src/main/java/com/transdb/dto/UserVO.java`
- Create: `backend/src/main/java/com/transdb/service/AuthService.java`
- Create: `backend/src/main/java/com/transdb/controller/AuthController.java`
- Test: `backend/src/test/java/com/transdb/controller/AuthControllerTest.java`

**Interfaces:**
- Consumes: Task 4 `JwtService`、Task 5 测试辅助 `createUser`/`bearer`
- Produces:
  - `POST /api/v1/auth/login`，body `{"username": "...", "password": "..."}` → `data: {"token": "...", "user": {"id", "username", "displayName", "role"}}`；密码错/用户不存在 → 401 `1001`；被禁用 → 401 `1004`
  - `GET /api/v1/auth/me` → `data: UserVO{id, username, displayName, role}`
  - `record UserVO(long id, String username, String displayName, Role role)` + `static UserVO from(SysUser)` —— Task 9 用户管理复用

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/controller/AuthControllerTest.java`：

```java
package com.transdb.controller;

import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest extends AbstractIntegrationTest {

    private HttpEntity<String> json(Object token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.set("Authorization", (String) token);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void loginAsBuiltInAdminReturnsToken() {
        ResponseEntity<String> res = rest.postForEntity("/api/v1/auth/login",
                json(null, "{\"username\":\"admin\",\"password\":\"admin123\"}"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"code\":0");
        assertThat(res.getBody()).contains("\"token\":");
        assertThat(res.getBody()).contains("\"role\":\"ADMIN\"");
    }

    @Test
    void wrongPasswordReturns1001() {
        ResponseEntity<String> res = rest.postForEntity("/api/v1/auth/login",
                json(null, "{\"username\":\"admin\",\"password\":\"nope\"}"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("\"code\":1001");
    }

    @Test
    void disabledUserLoginReturns1004() {
        var u = createUser(Role.EDITOR);
        u.setStatus(com.transdb.domain.UserStatus.DISABLED);
        userRepository.save(u);

        ResponseEntity<String> res = rest.postForEntity("/api/v1/auth/login",
                json(null, "{\"username\":\"" + u.getUsername() + "\",\"password\":\"password123\"}"),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("\"code\":1004");
    }

    @Test
    void meReturnsCurrentUser() {
        var u = createUser(Role.VIEWER);
        ResponseEntity<String> res = rest.exchange("/api/v1/auth/me",
                org.springframework.http.HttpMethod.GET, json(bearer(u), null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains(u.getUsername());
        assertThat(res.getBody()).contains("\"role\":\"VIEWER\"");
    }

    @Test
    void meWithoutTokenReturns401() {
        ResponseEntity<String> res = rest.getForEntity("/api/v1/auth/me", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("\"code\":1002");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest=AuthControllerTest`
Expected: 编译失败

- [ ] **Step 3: 实现 DTO、AuthService、AuthController**

`backend/src/main/java/com/transdb/dto/UserVO.java`：

```java
package com.transdb.dto;

import com.transdb.domain.Role;
import com.transdb.domain.SysUser;

public record UserVO(long id, String username, String displayName, Role role) {

    public static UserVO from(SysUser u) {
        return new UserVO(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole());
    }
}
```

`backend/src/main/java/com/transdb/dto/LoginRequest.java`：

```java
package com.transdb.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password) {
}
```

`backend/src/main/java/com/transdb/dto/LoginVO.java`：

```java
package com.transdb.dto;

public record LoginVO(String token, UserVO user) {
}
```

`backend/src/main/java/com/transdb/service/AuthService.java`：

```java
package com.transdb.service;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.domain.LoginRequestUnused;
import com.transdb.domain.UserStatus;
import com.transdb.dto.LoginRequest;
import com.transdb.dto.LoginVO;
import com.transdb.dto.UserVO;
import com.transdb.repository.SysUserRepository;
import com.transdb.security.JwtService;
import com.transdb.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional(readOnly = true)
    public LoginVO login(LoginRequest request) {
        var user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> BusinessException.of(ErrorCode.WRONG_CREDENTIALS));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw BusinessException.of(ErrorCode.WRONG_CREDENTIALS);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw BusinessException.of(ErrorCode.USER_DISABLED);
        }
        LoginUser principal = new LoginUser(user.getId(), user.getUsername(),
                user.getDisplayName(), user.getRole());
        return new LoginVO(jwtService.generate(principal), UserVO.from(user));
    }
}
```

（修正：`import com.transdb.domain.LoginRequestUnused;` 一行不存在，删除该行。）

`backend/src/main/java/com/transdb/controller/AuthController.java`：

```java
package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.dto.LoginRequest;
import com.transdb.dto.LoginVO;
import com.transdb.dto.UserVO;
import com.transdb.security.LoginUser;
import com.transdb.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<LoginVO> login(@RequestBody @Valid LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserVO> me(@AuthenticationPrincipal LoginUser principal) {
        return ApiResponse.ok(new UserVO(principal.id(), principal.username(),
                principal.displayName(), principal.role()));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=AuthControllerTest`
Expected: 5 个测试 PASS

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): 登录与当前用户接口"
```

---

### Task 7: 标签管理

**Files:**
- Create: `backend/src/main/java/com/transdb/dto/TagUpsertDTO.java`
- Create: `backend/src/main/java/com/transdb/dto/TagVO.java`
- Create: `backend/src/main/java/com/transdb/service/TagService.java`
- Create: `backend/src/main/java/com/transdb/controller/TagController.java`
- Test: `backend/src/test/java/com/transdb/controller/TagControllerTest.java`

**Interfaces:**
- Consumes: Task 2 `TagRepository`、Task 3 `ErrorCode.TAG_NAME_EXISTS/TAG_NOT_FOUND`
- Produces:
  - `GET /api/v1/tags`（登录即可）→ `data: [TagVO{id, name, description}]`，按 name 升序
  - `POST /api/v1/tags`（EDITOR+）body `{"name", "description"}`；重名 → 409 `5002`
  - `PUT /api/v1/tags/{id}`（EDITOR+）；`DELETE /api/v1/tags/{id}`（ADMIN）
  - `record TagVO(long id, String name, String description)` + `static TagVO from(Tag)`
  - `record TagUpsertDTO(@NotBlank String name, String description)`

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/controller/TagControllerTest.java`：

```java
package com.transdb.controller;

import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class TagControllerTest extends AbstractIntegrationTest {

    private HttpEntity<String> req(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token.substring(7));
        return new HttpEntity<>(body, headers);
    }

    @Test
    void editorCanCreateAndListTags() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);

        ResponseEntity<String> created = rest.exchange("/api/v1/tags", HttpMethod.POST,
                req(token, "{\"name\":\"儒家\",\"description\":\"儒家经典\"}"), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody()).contains("\"code\":0");

        ResponseEntity<String> list = rest.exchange("/api/v1/tags", HttpMethod.GET,
                req(token, null), String.class);
        assertThat(list.getBody()).contains("儒家");
    }

    @Test
    void duplicateTagNameReturns5002() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        rest.exchange("/api/v1/tags", HttpMethod.POST,
                req(token, "{\"name\":\"道家\"}"), String.class);

        ResponseEntity<String> dup = rest.exchange("/api/v1/tags", HttpMethod.POST,
                req(token, "{\"name\":\"道家\"}"), String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody()).contains("\"code\":5002");
    }

    @Test
    void editorCanUpdateTag() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        rest.exchange("/api/v1/tags", HttpMethod.POST, req(token, "{\"name\":\"佛家\"}"), String.class);
        long id = Long.parseLong(listFirstId(token));

        ResponseEntity<String> updated = rest.exchange("/api/v1/tags/" + id, HttpMethod.PUT,
                req(token, "{\"name\":\"佛家\",\"description\":\"更新后的描述\"}"), String.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void editorCannotDeleteTagButAdminCan() {
        var editor = createUser(Role.EDITOR);
        var admin = createUser(Role.ADMIN);
        rest.exchange("/api/v1/tags", HttpMethod.POST,
                req(bearer(editor), "{\"name\":\"兵家\"}"), String.class);
        long id = Long.parseLong(listFirstId(bearer(editor)));

        ResponseEntity<String> forbidden = rest.exchange("/api/v1/tags/" + id, HttpMethod.DELETE,
                req(bearer(editor), null), String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody()).contains("\"code\":9004");

        ResponseEntity<String> ok = rest.exchange("/api/v1/tags/" + id, HttpMethod.DELETE,
                req(bearer(admin), null), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void viewerCannotCreateTag() {
        var viewer = createUser(Role.VIEWER);
        ResponseEntity<String> res = rest.exchange("/api/v1/tags", HttpMethod.POST,
                req(bearer(viewer), "{\"name\":\"墨家\"}"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains("\"code\":9004");
    }

    private String listFirstId(String token) {
        ResponseEntity<String> list = rest.exchange("/api/v1/tags", HttpMethod.GET,
                req(token, null), String.class);
        var root = new com.jayway.jsonpath.JsonPath();
        return com.jayway.jsonpath.JsonPath.read(list.getBody(), "$.data[0].id").toString();
    }
}
```

（修正：`var root = new com.jayway.jsonpath.JsonPath();` 一行是笔误，删除；`listFirstId` 直接用静态 `JsonPath.read`。）

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest=TagControllerTest`
Expected: 编译失败

- [ ] **Step 3: 实现 TagService 与 TagController**

`backend/src/main/java/com/transdb/dto/TagUpsertDTO.java`：

```java
package com.transdb.dto;

import jakarta.validation.constraints.NotBlank;

public record TagUpsertDTO(
        @NotBlank(message = "标签名不能为空") String name,
        String description) {
}
```

`backend/src/main/java/com/transdb/dto/TagVO.java`：

```java
package com.transdb.dto;

import com.transdb.domain.Tag;

public record TagVO(long id, String name, String description) {

    public static TagVO from(Tag t) {
        return new TagVO(t.getId(), t.getName(), t.getDescription());
    }
}
```

`backend/src/main/java/com/transdb/service/TagService.java`：

```java
package com.transdb.service;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.domain.Tag;
import com.transdb.dto.TagUpsertDTO;
import com.transdb.dto.TagVO;
import com.transdb.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;

    @Transactional(readOnly = true)
    public List<TagVO> listAll() {
        return tagRepository.findAll(org.springframework.data.domain.Sort.by("name"))
                .stream().map(TagVO::from).toList();
    }

    @Transactional
    public TagVO create(TagUpsertDTO dto) {
        if (tagRepository.existsByName(dto.name())) {
            throw BusinessException.of(ErrorCode.TAG_NAME_EXISTS);
        }
        Tag tag = new Tag();
        tag.setName(dto.name());
        tag.setDescription(dto.description());
        return TagVO.from(tagRepository.save(tag));
    }

    @Transactional
    public TagVO update(long id, TagUpsertDTO dto) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.TAG_NOT_FOUND));
        tagRepository.findByName(dto.name())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw BusinessException.of(ErrorCode.TAG_NAME_EXISTS); });
        tag.setName(dto.name());
        tag.setDescription(dto.description());
        return TagVO.from(tagRepository.save(tag));
    }

    @Transactional
    public void delete(long id) {
        if (!tagRepository.existsById(id)) {
            throw BusinessException.of(ErrorCode.TAG_NOT_FOUND);
        }
        tagRepository.deleteById(id);
    }
}
```

（修正：`TagRepository` 需增加方法 `Optional<Tag> findByName(String name);`，在 Task 2 创建的接口中追加该行。）

`backend/src/main/java/com/transdb/controller/TagController.java`：

```java
package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.dto.TagUpsertDTO;
import com.transdb.dto.TagVO;
import com.transdb.service.TagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @GetMapping
    public ApiResponse<List<TagVO>> list() {
        return ApiResponse.ok(tagService.listAll());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<TagVO> create(@RequestBody @Valid TagUpsertDTO dto) {
        return ApiResponse.ok(tagService.create(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<TagVO> update(@PathVariable long id, @RequestBody @Valid TagUpsertDTO dto) {
        return ApiResponse.ok(tagService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable long id) {
        tagService.delete(id);
        return ApiResponse.ok();
    }
}
```

同时在 `backend/src/main/java/com/transdb/repository/TagRepository.java` 的接口体内追加：

```java
    java.util.Optional<Tag> findByName(String name);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=TagControllerTest`
Expected: 5 个测试 PASS

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): 标签管理接口"
```

---

### Task 8: 句段 CRUD（含乐观锁、事件发布、权限矩阵）

**Files:**
- Create: `backend/src/main/java/com/transdb/common/ContentHash.java`
- Create: `backend/src/main/java/com/transdb/domain/SegmentChangedEvent.java`
- Create: `backend/src/main/java/com/transdb/domain/ChangeType.java`
- Create: `backend/src/main/java/com/transdb/dto/SegmentUpsertDTO.java`
- Create: `backend/src/main/java/com/transdb/dto/SegmentVO.java`
- Create: `backend/src/main/java/com/transdb/dto/SegmentFilter.java`
- Create: `backend/src/main/java/com/transdb/service/SegmentService.java`
- Create: `backend/src/main/java/com/transdb/controller/SegmentController.java`
- Test: `backend/src/test/java/com/transdb/controller/SegmentControllerTest.java`

**Interfaces:**
- Consumes: Task 2 实体/仓储、Task 3 异常体系、Task 5 测试辅助
- Produces（Plan 2 依赖的关键契约）:
  - `record SegmentChangedEvent(Long segmentId, ChangeType type)`，`enum ChangeType { CREATED, UPDATED, DELETED }`——Service 在事务内通过 `ApplicationEventPublisher` 发布；Plan 2 用 `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` 消费
  - `ContentHash.sha256(String sourceText, String translatedText)` → 64 位小写 hex（SHA-256 over UTF-8(source+translated)）——Plan 3 导入复用
  - REST：
    - `GET /api/v1/segments?work=&dynasty=&tagId=&status=&page=0&size=20`（size≤100；VIEWER 强制 `status=PUBLISHED`）→ `data: PageResponse<SegmentVO>`
    - `GET /api/v1/segments/{id}`（VIEWER 看到 DRAFT → 403 `2003`）
    - `POST /api/v1/segments`（EDITOR+）→ 401/403 由 Security 处理
    - `PUT /api/v1/segments/{id}`（EDITOR+，`version` 不匹配 → 409 `2002`）
    - `DELETE /api/v1/segments/{id}`（ADMIN）
  - `record SegmentUpsertDTO(@NotBlank String sourceText, @NotBlank String translatedText, String workTitle, String chapter, String author, String dynasty, String translator, String notes, SegmentStatus status, List<Long> tagIds, Long version)`（`version` 仅更新时使用；`status` null 默认 PUBLISHED）
  - `record SegmentVO(long id, String sourceText, String translatedText, String workTitle, String chapter, String author, String dynasty, String translator, String notes, SegmentStatus status, int version, List<String> tags, Instant createdAt, Instant updatedAt)`
  - `record SegmentFilter(String work, String dynasty, Long tagId, SegmentStatus status)`

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/controller/SegmentControllerTest.java`：

```java
package com.transdb.controller;

import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentControllerTest extends AbstractIntegrationTest {

    private HttpEntity<String> req(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token.substring(7));
        return new HttpEntity<>(body, headers);
    }

    private long createTag(String token, String name) {
        rest.exchange("/api/v1/tags", HttpMethod.POST, req(token, "{\"name\":\"" + name + "\"}"), String.class);
        ResponseEntity<String> list = rest.exchange("/api/v1/tags", HttpMethod.GET, req(token, null), String.class);
        return ((Number) com.jayway.jsonpath.JsonPath.read(list.getBody(),
                "$.data[?(@.name=='" + name + "')][0].id")).longValue();
    }

    private ResponseEntity<String> createSegment(String token, String source, String translated,
                                                 String status, Long tagId) {
        String tagPart = tagId == null ? "" : ",\"tagIds\":[" + tagId + "]";
        String body = "{\"sourceText\":\"" + source + "\",\"translatedText\":\"" + translated
                + "\",\"workTitle\":\"论语\",\"dynasty\":\"先秦\",\"status\":" + (status == null ? "null" : "\"" + status + "\"")
                + tagPart + "}";
        return rest.exchange("/api/v1/segments", HttpMethod.POST, req(token, body), String.class);
    }

    @Test
    void editorCreatesSegmentAndReadsItBack() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        long tagId = createTag(token, "儒家");

        ResponseEntity<String> created = createSegment(token, "学而时习之，不亦说乎？",
                "Is it not pleasant to learn and practice what one has learned?", null, tagId);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        Number id = com.jayway.jsonpath.JsonPath.read(created.getBody(), "$.data.id");
        assertThat(id.longValue()).isPositive();

        ResponseEntity<String> detail = rest.exchange("/api/v1/segments/" + id.longValue(),
                HttpMethod.GET, req(token, null), String.class);
        assertThat(detail.getBody()).contains("学而时习之");
        assertThat(detail.getBody()).contains("儒家");
        assertThat(com.jayway.jsonpath.JsonPath.read(detail.getBody(), "$.data.status")).isEqualTo("PUBLISHED");
    }

    @Test
    void blankSourceTextRejected() {
        var editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = createSegment(bearer(editor), "", "x", null, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("\"code\":9001");
    }

    @Test
    void updateWithStaleVersionReturns409() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        ResponseEntity<String> created = createSegment(token, "有朋自远方来", "Friends from afar", null, null);
        Number id = com.jayway.jsonpath.JsonPath.read(created.getBody(), "$.data.id");
        Number version = com.jayway.jsonpath.JsonPath.read(created.getBody(), "$.data.version");

        String body = "{\"sourceText\":\"有朋自远方来，不亦乐乎？\",\"translatedText\":\"Friends from afar\",\"version\":"
                + (version.intValue() + 5) + "}";
        ResponseEntity<String> conflict = rest.exchange("/api/v1/segments/" + id.longValue(),
                HttpMethod.PUT, req(token, body), String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("\"code\":2002");

        String okBody = "{\"sourceText\":\"有朋自远方来，不亦乐乎？\",\"translatedText\":\"And is it not delightful to have friends coming from distant quarters?\",\"version\":"
                + version.intValue() + "}";
        ResponseEntity<String> ok = rest.exchange("/api/v1/segments/" + id.longValue(),
                HttpMethod.PUT, req(token, okBody), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).contains("delightful");
    }

    @Test
    void viewerSeesOnlyPublished() {
        var editor = createUser(Role.EDITOR);
        var viewer = createUser(Role.VIEWER);
        String token = bearer(editor);
        createSegment(token, "人不知而不愠", "not be displeased", "DRAFT", null);
        createSegment(token, "吾日三省吾身", "I daily examine myself", "PUBLISHED", null);

        ResponseEntity<String> list = rest.exchange("/api/v1/segments", HttpMethod.GET,
                req(bearer(viewer), null), String.class);
        assertThat(com.jayway.jsonpath.JsonPath.read(list.getBody(), "$.data.total")).isEqualTo(1);
        assertThat(list.getBody()).contains("吾日三省吾身");

        Number draftId = com.jayway.jsonpath.JsonPath.read(
                rest.exchange("/api/v1/segments?status=DRAFT", HttpMethod.GET, req(token, null), String.class).getBody(),
                "$.data.content[0].id");
        ResponseEntity<String> forbidden = rest.exchange("/api/v1/segments/" + draftId.longValue(),
                HttpMethod.GET, req(bearer(viewer), null), String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody()).contains("\"code\":2003");
    }

    @Test
    void deleteRestrictedToAdmin() {
        var editor = createUser(Role.EDITOR);
        var admin = createUser(Role.ADMIN);
        String token = bearer(editor);
        ResponseEntity<String> created = createSegment(token, "温故而知新", "keep what has been taught", null, null);
        Number id = com.jayway.jsonpath.JsonPath.read(created.getBody(), "$.data.id");

        ResponseEntity<String> forbidden = rest.exchange("/api/v1/segments/" + id.longValue(),
                HttpMethod.DELETE, req(token, null), String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody()).contains("\"code\":9004");

        ResponseEntity<String> ok = rest.exchange("/api/v1/segments/" + id.longValue(),
                HttpMethod.DELETE, req(bearer(admin), null), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/v1/segments/" + id.longValue(), HttpMethod.GET,
                req(bearer(admin), null), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listFilterByDynastyAndTag() {
        var editor = createUser(Role.EDITOR);
        String token = bearer(editor);
        long tagId = createTag(token, "道家");
        createSegment(token, "道可道，非常道", "The Tao that can be trodden", null, tagId);
        createSegment(token, "学而时习之", "learn and practice", null, null);

        ResponseEntity<String> byDynasty = rest.exchange("/api/v1/segments?dynasty=先秦", HttpMethod.GET,
                req(token, null), String.class);
        assertThat(com.jayway.jsonpath.JsonPath.read(byDynasty.getBody(), "$.data.total")).isEqualTo(2);

        ResponseEntity<String> byTag = rest.exchange("/api/v1/segments?tagId=" + tagId, HttpMethod.GET,
                req(token, null), String.class);
        assertThat(com.jayway.jsonpath.JsonPath.read(byTag.getBody(), "$.data.total")).isEqualTo(1);
        assertThat(byTag.getBody()).contains("道可道");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest=SegmentControllerTest`
Expected: 编译失败

- [ ] **Step 3: 实现**

`backend/src/main/java/com/transdb/common/ContentHash.java`：

```java
package com.transdb.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ContentHash {

    private ContentHash() {
    }

    /** SHA-256 over UTF-8(sourceText + translatedText)，64 位小写 hex。 */
    public static String sha256(String sourceText, String translatedText) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((sourceText + translatedText).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`backend/src/main/java/com/transdb/domain/ChangeType.java`：

```java
package com.transdb.domain;

public enum ChangeType { CREATED, UPDATED, DELETED }
```

`backend/src/main/java/com/transdb/domain/SegmentChangedEvent.java`：

```java
package com.transdb.domain;

/** 事务内发布；消费者须用 @TransactionalEventListener(AFTER_COMMIT)（Plan 2 的 ES 同步）。 */
public record SegmentChangedEvent(Long segmentId, ChangeType type) {
}
```

`backend/src/main/java/com/transdb/dto/SegmentUpsertDTO.java`：

```java
package com.transdb.dto;

import com.transdb.domain.SegmentStatus;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record SegmentUpsertDTO(
        @NotBlank(message = "原文不能为空") String sourceText,
        @NotBlank(message = "译文不能为空") String translatedText,
        String workTitle,
        String chapter,
        String author,
        String dynasty,
        String translator,
        String notes,
        SegmentStatus status,
        List<Long> tagIds,
        Long version) {
}
```

`backend/src/main/java/com/transdb/dto/SegmentVO.java`：

```java
package com.transdb.dto;

import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record SegmentVO(long id, String sourceText, String translatedText, String workTitle,
                        String chapter, String author, String dynasty, String translator,
                        String notes, SegmentStatus status, int version, List<String> tags,
                        Instant createdAt, Instant updatedAt) {

    public static SegmentVO from(Segment s) {
        return new SegmentVO(s.getId(), s.getSourceText(), s.getTranslatedText(),
                s.getWorkTitle(), s.getChapter(), s.getAuthor(), s.getDynasty(),
                s.getTranslator(), s.getNotes(), s.getStatus(), s.getVersion(),
                s.getTags().stream().map(com.transdb.domain.Tag::getName).sorted().toList(),
                s.getCreatedAt(), s.getUpdatedAt());
    }
}
```

`backend/src/main/java/com/transdb/dto/SegmentFilter.java`：

```java
package com.transdb.dto;

import com.transdb.domain.SegmentStatus;

public record SegmentFilter(String work, String dynasty, Long tagId, SegmentStatus status) {
}
```

`backend/src/main/java/com/transdb/service/SegmentService.java`：

```java
package com.transdb.service;

import com.transdb.common.BusinessException;
import com.transdb.common.ContentHash;
import com.transdb.common.ErrorCode;
import com.transdb.domain.*;
import com.transdb.dto.PageResponse;
import com.transdb.dto.SegmentFilter;
import com.transdb.dto.SegmentUpsertDTO;
import com.transdb.dto.SegmentVO;
import com.transdb.repository.SegmentRepository;
import com.transdb.repository.SysUserRepository;
import com.transdb.repository.TagRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SegmentService {

    private final SegmentRepository segmentRepository;
    private final TagRepository tagRepository;
    private final SysUserRepository userRepository;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Transactional
    public SegmentVO create(SegmentUpsertDTO dto, LoginUser operator) {
        Segment s = new Segment();
        applyUpsert(s, dto);
        s.setCreatedBy(userRepository.getReferenceById(operator.id()));
        Segment saved = segmentRepository.save(s);
        eventPublisher.publishEvent(new SegmentChangedEvent(saved.getId(), ChangeType.CREATED));
        return SegmentVO.from(saved);
    }

    @Transactional
    public SegmentVO update(long id, SegmentUpsertDTO dto) {
        Segment s = segmentRepository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.SEGMENT_NOT_FOUND));
        if (dto.version() == null || !dto.version().equals(Long.valueOf(s.getVersion()))) {
            throw BusinessException.of(ErrorCode.OPTIMISTIC_LOCK);
        }
        applyUpsert(s, dto);
        Segment saved = segmentRepository.save(s);
        eventPublisher.publishEvent(new SegmentChangedEvent(saved.getId(), ChangeType.UPDATED));
        return SegmentVO.from(saved);
    }

    @Transactional
    public void delete(long id) {
        Segment s = segmentRepository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.SEGMENT_NOT_FOUND));
        segmentRepository.delete(s);
        eventPublisher.publishEvent(new SegmentChangedEvent(id, ChangeType.DELETED));
    }

    @Transactional(readOnly = true)
    public SegmentVO get(long id) {
        return SegmentVO.from(segmentRepository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.SEGMENT_NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public PageResponse<SegmentVO> list(SegmentFilter filter, int page, int size, LoginUser operator) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Segment> result = segmentRepository.findAll(toSpecification(filter, operator), pageable);
        return PageResponse.of(result.map(SegmentVO::from));
    }

    /** VIEWER 只能看已发布：强制 status=PUBLISHED。 */
    private Specification<Segment> toSpecification(SegmentFilter filter, LoginUser operator) {
        SegmentStatus status = filter.status();
        if (operator.role() == Role.VIEWER) {
            status = SegmentStatus.PUBLISHED;
        }
        SegmentStatus finalStatus = status;
        String work = filter.work();
        String dynasty = filter.dynasty();
        Long tagId = filter.tagId();
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (finalStatus != null) {
                ps.add(cb.equal(root.get("status"), finalStatus));
            }
            if (work != null && !work.isBlank()) {
                ps.add(cb.equal(root.get("workTitle"), work));
            }
            if (dynasty != null && !dynasty.isBlank()) {
                ps.add(cb.equal(root.get("dynasty"), dynasty));
            }
            if (tagId != null) {
                ps.add(cb.equal(root.join("tags").get("id"), tagId));
                query.distinct(true);
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private void applyUpsert(Segment s, SegmentUpsertDTO dto) {
        s.setSourceText(dto.sourceText());
        s.setTranslatedText(dto.translatedText());
        s.setWorkTitle(dto.workTitle());
        s.setChapter(dto.chapter());
        s.setAuthor(dto.author());
        s.setDynasty(dto.dynasty());
        s.setTranslator(dto.translator());
        s.setNotes(dto.notes());
        s.setStatus(dto.status() == null ? SegmentStatus.PUBLISHED : dto.status());
        s.setContentHash(ContentHash.sha256(dto.sourceText(), dto.translatedText()));
        if (dto.tagIds() != null) {
            s.setTags(new HashSet<>(tagRepository.findAllById(dto.tagIds())));
        }
    }
}
```

`backend/src/main/java/com/transdb/controller/SegmentController.java`：

```java
package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.common.PageResponse;
import com.transdb.domain.SegmentStatus;
import com.transdb.dto.SegmentFilter;
import com.transdb.dto.SegmentUpsertDTO;
import com.transdb.dto.SegmentVO;
import com.transdb.security.LoginUser;
import com.transdb.service.SegmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/segments")
@RequiredArgsConstructor
public class SegmentController {

    private final SegmentService segmentService;

    @GetMapping
    public ApiResponse<PageResponse<SegmentVO>> list(
            @RequestParam(required = false) String work,
            @RequestParam(required = false) String dynasty,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) SegmentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal LoginUser operator) {
        return ApiResponse.ok(segmentService.list(
                new SegmentFilter(work, dynasty, tagId, status), page, size, operator));
    }

    @GetMapping("/{id}")
    public ApiResponse<SegmentVO> get(@PathVariable long id, @AuthenticationPrincipal LoginUser operator) {
        SegmentVO vo = segmentService.get(id);
        if (operator.role() == com.transdb.domain.Role.VIEWER
                && vo.status() == SegmentStatus.DRAFT) {
            throw com.transdb.common.BusinessException.of(com.transdb.common.ErrorCode.SEGMENT_FORBIDDEN);
        }
        return ApiResponse.ok(vo);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<SegmentVO> create(@RequestBody @Valid SegmentUpsertDTO dto,
                                         @AuthenticationPrincipal LoginUser operator) {
        return ApiResponse.ok(segmentService.create(dto, operator));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
    public ApiResponse<SegmentVO> update(@PathVariable long id, @RequestBody @Valid SegmentUpsertDTO dto) {
        return ApiResponse.ok(segmentService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable long id) {
        segmentService.delete(id);
        return ApiResponse.ok();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=SegmentControllerTest`
Expected: 6 个测试 PASS

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): 句段 CRUD、乐观锁与变更事件"
```

---

### Task 9: 用户管理（ADMIN）

**Files:**
- Create: `backend/src/main/java/com/transdb/dto/CreateUserDTO.java`
- Create: `backend/src/main/java/com/transdb/dto/UpdateUserDTO.java`
- Create: `backend/src/main/java/com/transdb/service/UserService.java`
- Create: `backend/src/main/java/com/transdb/controller/UserController.java`
- Test: `backend/src/test/java/com/transdb/controller/UserControllerTest.java`

**Interfaces:**
- Consumes: Task 6 `UserVO`、Task 2 仓储、Task 5 测试辅助
- Produces:
  - `GET /api/v1/users?page=&size=`（ADMIN）→ `PageResponse<UserVO>`
  - `POST /api/v1/users`（ADMIN）body `{"username","password"(≥6位),"displayName","role"}`；重名 → 409 `5001`
  - `PUT /api/v1/users/{id}`（ADMIN）body `{"displayName","role","status"}`（均可选）；对自己降权或禁用 → 400 `5005`；禁用后登录 → 401 `1004`（Task 6 已实现）
  - `record CreateUserDTO(@NotBlank String username, @NotBlank @Size(min=6) String password, String displayName, @NotNull Role role)`
  - `record UpdateUserDTO(String displayName, Role role, UserStatus status)`

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/controller/UserControllerTest.java`：

```java
package com.transdb.controller;

import com.transdb.AbstractIntegrationTest;
import com.transdb.domain.Role;
import com.transdb.repository.SysUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class UserControllerTest extends AbstractIntegrationTest {

    @Autowired SysUserRepository users;

    private HttpEntity<String> req(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token.substring(7));
        return new HttpEntity<>(body, headers);
    }

    private String adminToken() {
        return bearer(users.findByUsername("admin").orElseThrow());
    }

    @Test
    void adminCreatesAndListsUsers() {
        String token = adminToken();
        String username = "alice_" + System.currentTimeMillis();

        ResponseEntity<String> created = rest.exchange("/api/v1/users", HttpMethod.POST,
                req(token, "{\"username\":\"" + username + "\",\"password\":\"secret66\",\"displayName\":\"爱丽丝\",\"role\":\"EDITOR\"}"),
                String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> list = rest.exchange("/api/v1/users", HttpMethod.GET,
                req(token, null), String.class);
        assertThat(list.getBody()).contains(username);
        assertThat(list.getBody()).contains("\"total\":");
    }

    @Test
    void duplicateUsernameReturns5001() {
        String token = adminToken();
        String username = "bob_" + System.currentTimeMillis();
        rest.exchange("/api/v1/users", HttpMethod.POST,
                req(token, "{\"username\":\"" + username + "\",\"password\":\"secret66\",\"role\":\"EDITOR\"}"),
                String.class);
        ResponseEntity<String> dup = rest.exchange("/api/v1/users", HttpMethod.POST,
                req(token, "{\"username\":\"" + username + "\",\"password\":\"secret66\",\"role\":\"VIEWER\"}"),
                String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody()).contains("\"code\":5001");
    }

    @Test
    void disableUserBlocksLogin() {
        String token = adminToken();
        String username = "carol_" + System.currentTimeMillis();
        rest.exchange("/api/v1/users", HttpMethod.POST,
                req(token, "{\"username\":\"" + username + "\",\"password\":\"secret66\",\"role\":\"EDITOR\"}"),
                String.class);
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                users.findByUsername(username).map(u -> u.getId()).get(), "$")).longValue();

        ResponseEntity<String> disabled = rest.exchange("/api/v1/users/" + id, HttpMethod.PUT,
                req(token, "{\"status\":\"DISABLED\"}"), String.class);
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> login = rest.postForEntity("/api/v1/auth/login",
                req(null, "{\"username\":\"" + username + "\",\"password\":\"secret66\"}"), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login.getBody()).contains("\"code\":1004");
    }

    @Test
    void adminCannotDisableSelf() {
        long adminId = users.findByUsername("admin").orElseThrow().getId();
        ResponseEntity<String> res = rest.exchange("/api/v1/users/" + adminId, HttpMethod.PUT,
                req(adminToken(), "{\"status\":\"DISABLED\"}"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("\"code\":5005");
    }

    @Test
    void nonAdminAccessDenied() {
        var editor = createUser(Role.EDITOR);
        ResponseEntity<String> res = rest.exchange("/api/v1/users", HttpMethod.GET,
                req(bearer(editor), null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains("\"code\":9004");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest=UserControllerTest`
Expected: 编译失败

- [ ] **Step 3: 实现 UserService 与 UserController**

`backend/src/main/java/com/transdb/dto/CreateUserDTO.java`：

```java
package com.transdb.dto;

import com.transdb.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserDTO(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") @Size(min = 6, message = "密码至少 6 位") String password,
        String displayName,
        @NotNull(message = "角色不能为空") Role role) {
}
```

`backend/src/main/java/com/transdb/dto/UpdateUserDTO.java`：

```java
package com.transdb.dto;

import com.transdb.domain.Role;
import com.transdb.domain.UserStatus;

public record UpdateUserDTO(String displayName, Role role, UserStatus status) {
}
```

`backend/src/main/java/com/transdb/service/UserService.java`：

```java
package com.transdb.service;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.common.PageResponse;
import com.transdb.domain.Role;
import com.transdb.domain.SysUser;
import com.transdb.domain.UserStatus;
import com.transdb.dto.CreateUserDTO;
import com.transdb.dto.UpdateUserDTO;
import com.transdb.dto.UserVO;
import com.transdb.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public PageResponse<UserVO> list(int page, int size) {
        var result = userRepository.findAll(
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Direction.ASC, "id")));
        return PageResponse.of(result.map(UserVO::from));
    }

    @Transactional
    public UserVO create(CreateUserDTO dto) {
        if (userRepository.existsByUsername(dto.username())) {
            throw BusinessException.of(ErrorCode.USERNAME_EXISTS);
        }
        SysUser u = new SysUser();
        u.setUsername(dto.username());
        u.setPassword(passwordEncoder.encode(dto.password()));
        u.setDisplayName(dto.displayName());
        u.setRole(dto.role());
        u.setStatus(UserStatus.ACTIVE);
        return UserVO.from(userRepository.save(u));
    }

    @Transactional
    public UserVO update(long id, UpdateUserDTO dto, LoginUser operator) {
        SysUser u = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.USER_NOT_FOUND));
        boolean demotingSelf = operator.id() == id
                && (dto.role() != null && dto.role() != u.getRole()
                    || dto.status() != null && dto.status() != UserStatus.ACTIVE);
        if (demotingSelf) {
            throw BusinessException.of(ErrorCode.SELF_MODIFY_FORBIDDEN);
        }
        if (dto.displayName() != null) {
            u.setDisplayName(dto.displayName());
        }
        if (dto.role() != null) {
            u.setRole(dto.role());
        }
        if (dto.status() != null) {
            u.setStatus(dto.status());
        }
        return UserVO.from(userRepository.save(u));
    }
}
```

（修正：`SysUserRepository` 需追加 `boolean existsByUsername(String username);`——与 Task 2 创建的接口中追加。）

`backend/src/main/java/com/transdb/controller/UserController.java`：

```java
package com.transdb.controller;

import com.transdb.common.ApiResponse;
import com.transdb.common.PageResponse;
import com.transdb.dto.CreateUserDTO;
import com.transdb.dto.UpdateUserDTO;
import com.transdb.dto.UserVO;
import com.transdb.security.LoginUser;
import com.transdb.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public ApiResponse<PageResponse<UserVO>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(userService.list(page, size));
    }

    @PostMapping
    public ApiResponse<UserVO> create(@RequestBody @Valid CreateUserDTO dto) {
        return ApiResponse.ok(userService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserVO> update(@PathVariable long id,
                                      @RequestBody @Valid UpdateUserDTO dto,
                                      @AuthenticationPrincipal LoginUser operator) {
        return ApiResponse.ok(userService.update(id, dto, operator));
    }
}
```

同时在 `backend/src/main/java/com/transdb/repository/SysUserRepository.java` 接口体内追加：

```java
    boolean existsByUsername(String username);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=UserControllerTest`
Expected: 5 个测试 PASS

- [ ] **Step 5: 提交**

```bash
git add backend/ && git commit -m "feat(backend): 用户管理接口（ADMIN）"
```

---

### Task 10: 全量回归、README 与收尾

**Files:**
- Create: `README.md`（仓库根）

**Interfaces:**
- Consumes: 前九个任务的全部交付物
- Produces: 可交付的后端核心：`mvn test` 全绿；README 含本地运行说明

- [ ] **Step 1: 全量回归**

Run: `cd backend && mvn test`
Expected: BUILD SUCCESS，全部测试 PASS（若有失败，先修复再继续——修复遵循 TDD，不允许直接改测试迁就实现）

- [ ] **Step 2: 写 README**

`README.md`：

```markdown
# 翻译学术数据库（Translation Academic Database）

存储与检索古典中文作品英译对照的学术数据库。句段级"古文原文 + 英文译文"对照，标签体系，
支持中文分词全文检索、拼音/首字母搜索、英文容错搜索（搜索能力见后续里程碑）。

## 设计文档

- 设计规格：`docs/superpowers/specs/2026-09-06-translation-database-design.md`
- 实施计划：`docs/superpowers/plans/`

## 技术栈

- 后端：Java 21、Spring Boot 3.3、PostgreSQL 16、Flyway、JWT
- 前端（规划中）：Vue 3 + Element Plus
- 搜索（规划中）：Elasticsearch 8（IK 分词 + pinyin）

## 本地运行后端

前置：JDK 21、Maven 3.9+、本机 Docker（集成测试用 Testcontainers）。

```bash
# 启动一个本地 PostgreSQL（仅开发用）
docker run -d --name transdb-pg -p 5432:5432 \
  -e POSTGRES_USER=transdb -e POSTGRES_PASSWORD=transdb -e POSTGRES_DB=transdb \
  postgres:16-alpine

cd backend && mvn spring-boot:run
```

- 服务地址：http://localhost:8080
- 健康检查：http://localhost:8080/actuator/health
- 内置管理员：`admin` / `admin123`（生产环境务必通过环境变量 `TRANSDB_ADMIN_PASSWORD` 覆盖）

## 运行测试

```bash
cd backend && mvn test
```

测试使用 Testcontainers 自动拉起 PostgreSQL 16 容器，无需本地数据库。

## API 一览（当前阶段）

| 方法与路径 | 说明 | 权限 |
|---|---|---|
| POST /api/v1/auth/login | 登录 | 公开 |
| GET /api/v1/auth/me | 当前用户 | 登录 |
| GET/POST/PUT/DELETE /api/v1/segments | 句段 CRUD | 见设计文档 §6 |
| GET/POST/PUT/DELETE /api/v1/tags | 标签管理 | 见设计文档 §6 |
| GET/POST/PUT /api/v1/users | 用户管理 | ADMIN |

统一响应体：`{"code": 0, "message": "ok", "data": {...}}`。
```

- [ ] **Step 3: 提交**

```bash
git add README.md && git commit -m "docs: 后端核心 README 与本地运行说明"
```

- [ ] **Step 4: 推送到 Gitee**

```bash
git push -u origin main
```

（前提：Gitee 远程仓库已创建。若尚未创建，由用户在 Gitee 网页创建 `translation-database` 仓库后执行。）

---

## Self-Review 记录

1. **规格覆盖（Plan 1 范围）**：§3 数据模型→Task 2；§6 认证与权限→Task 4/5/6/9；§7 API（segments/tags/users/auth）→Task 6/7/8/9；§5 同步事件契约→Task 8（`SegmentChangedEvent`）；§9 错误处理→Task 3/5；§3.2 content_hash→Task 8（`ContentHash`，Plan 3 导入复用）。搜索（§4）、导入（§8）、facets、Docker（§12）、前端（§10）按分解属于 Plan 2-4。
2. **占位符扫描**：无 TBD/TODO；所有代码步骤给出完整代码；三处"笔误修正"说明（Task 6 AuthService 的多余 import、Task 7 JsonPath 实例化笔误、Task 7/9 仓储方法追加）已在对应步骤内联标注为必须执行的修正，实现者以修正后版本为准。
3. **类型一致性**：`LoginUser(long id, String username, String displayName, Role role)` 在 Task 4 定义、Task 5/6/8/9 使用一致；`SegmentChangedEvent(Long, ChangeType)` 与 Plan 2 契约一致；`ContentHash.sha256(String, String)` 与 Plan 3 契约一致；`ApiResponse.ok/error`、`PageResponse.of` 全文一致；错误码全表在 Task 3 定义且后续任务引用一致。

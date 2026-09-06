package com.transdb.domain;

import com.transdb.AbstractIntegrationTest;
import com.transdb.repository.SegmentRepository;
import com.transdb.repository.SysUserRepository;
import com.transdb.repository.TagRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainMigrationTest extends AbstractIntegrationTest {

    @Autowired SysUserRepository users;
    @Autowired SegmentRepository segments;
    @Autowired TagRepository tags;
    @Autowired EntityManager em;

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
    @Transactional  // 断言 LAZY 关联必须在打开的 Session 内进行（仓储方法各自的事务结束后实体已脱管）
    void saveSegmentWithTagsPersists() {
        SysUser author = newUser(Role.EDITOR);
        Tag t = new Tag();
        t.setName("儒家_" + System.nanoTime());   // 唯一名：所有测试类共享同一 PG 容器，固定名会跨测试唯一约束冲突
        tags.save(t);

        Segment s = new Segment();
        s.setSourceText("学而时习之");
        s.setTranslatedText("To learn and practice it in due time");
        s.setWorkTitle("论语");
        s.setDynasty("先秦");
        s.setStatus(SegmentStatus.PUBLISHED);
        s.setContentHash("hash_" + System.nanoTime());  // content_hash NOT NULL，真实 hash 由后续业务计算
        s.setCreatedBy(author);
        s.setTags(Set.of(t));
        s = segments.save(s);
        em.flush();   // 先把关联表写库
        em.clear();   // 再清空一级缓存，确保下面 findById 是真实回读而非读缓存

        Segment loaded = segments.findById(s.getId()).orElseThrow();
        assertThat(loaded.getTags()).extracting(Tag::getName).containsExactly(t.getName());
        assertThat(loaded.getCreatedBy().getUsername()).isEqualTo(author.getUsername());
    }

    @Test
    void duplicateTagNameRejected() {
        String name = "道家_" + System.nanoTime();
        Tag t = new Tag();
        t.setName(name);
        tags.save(t);
        Tag dup = new Tag();
        dup.setName(name);
        assertThatThrownBy(() -> tags.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateUsernameRejected() {
        SysUser saved = newUser(Role.VIEWER);
        SysUser dup = new SysUser();
        dup.setUsername(saved.getUsername());
        dup.setPassword("x");
        dup.setRole(Role.VIEWER);
        dup.setStatus(UserStatus.ACTIVE);
        assertThatThrownBy(() -> users.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

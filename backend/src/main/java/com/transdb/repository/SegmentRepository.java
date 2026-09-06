package com.transdb.repository;

import com.transdb.domain.Segment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SegmentRepository extends JpaRepository<Segment, Long>,
        JpaSpecificationExecutor<Segment> {

    /** ES 对账分页：always page 0 + id 游标推进（updatedAt >= since 且 id > lastId）。 */
    org.springframework.data.domain.Page<com.transdb.domain.Segment>
            findByUpdatedAtGreaterThanEqualAndIdGreaterThan(java.time.Instant since, Long id,
                    org.springframework.data.domain.Pageable pageable);

    /**
     * ES 同步专用：一次取回并初始化 tags（LAZY 集合）。
     * 同步监听器在事务提交后的异步线程组装文档，无持久化上下文，
     * 普通 findById 返回的游离实体访问 tags 会抛 LazyInitializationException。
     */
    @Query("select s from Segment s left join fetch s.tags where s.id = :id")
    com.transdb.domain.Segment findByIdForSync(@Param("id") Long id);
}

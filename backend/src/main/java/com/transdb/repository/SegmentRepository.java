package com.transdb.repository;

import com.transdb.domain.Segment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SegmentRepository extends JpaRepository<Segment, Long>,
        JpaSpecificationExecutor<Segment> {

    /** ES 全量重建分页：id 游标推进（id > lastId），按 id 升序取一批。 */
    java.util.List<Segment> findTop500ByIdGreaterThanOrderByIdAsc(Long id);

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

    /** 导入批量同步专用：按 id 批量取回并初始化 tags（LAZY 集合），供 bulkUpsert 组装文档。 */
    @Query("select s from Segment s left join fetch s.tags where s.id in :ids")
    java.util.List<Segment> findByIdForSyncIn(@Param("ids") java.util.List<Long> ids);

    /** 标签改名/删除传播：查出所有携带该标签的句段 id，用于发布 SegmentChangedEvent。 */
    @Query("select s.id from Segment s join s.tags t where t.id = :tagId")
    java.util.List<Long> findIdsByTagId(@Param("tagId") Long tagId);

    /** PG 降级搜索的朝代 facets：去重非空朝代列表。 */
    @Query("select distinct s.dynasty from Segment s where s.dynasty is not null order by s.dynasty")
    java.util.List<String> findDistinctDynasties();

    /** PG 降级搜索的作品 facets：去重非空作品名列表。 */
    @Query("select distinct s.workTitle from Segment s where s.workTitle is not null order by s.workTitle")
    java.util.List<String> findDistinctWorkTitles();

    /** 批量导入预览：按内容 hash 批查库内是否已存在。 */
    java.util.List<Segment> findByContentHashIn(java.util.List<String> hashes);

    /** 整本书导入预览：按原文批查库内是否已存在（不论译文状态），用于保护已有译文的条目。 */
    java.util.List<Segment> findBySourceTextIn(java.util.Collection<String> sources);

    /** 整本书导入（译文侧）预览：按译文批查库内是否已存在，用于保护已配原文的条目。 */
    java.util.List<Segment> findByTranslatedTextIn(java.util.Collection<String> translations);
}

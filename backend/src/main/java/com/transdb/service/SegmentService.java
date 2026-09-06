package com.transdb.service;

import com.transdb.common.BusinessException;
import com.transdb.common.ContentHash;
import com.transdb.common.ErrorCode;
import com.transdb.common.PageResponse;
import com.transdb.domain.*;
import com.transdb.dto.SegmentFilter;
import com.transdb.dto.SegmentUpsertDTO;
import com.transdb.dto.SegmentVO;
import com.transdb.repository.SegmentRepository;
import com.transdb.repository.SysUserRepository;
import com.transdb.repository.TagRepository;
import com.transdb.security.LoginUser;
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
        applyUpsert(s, dto, true);
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
        applyUpsert(s, dto, false);
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

    /**
     * status 语义：create 时 null 默认 PUBLISHED；update 时 null 保留原状态，仅显式传入才变更。
     * tagIds 为 null 时同样保留既有标签（部分更新语义）。
     */
    private void applyUpsert(Segment s, SegmentUpsertDTO dto, boolean create) {
        s.setSourceText(dto.sourceText());
        s.setTranslatedText(dto.translatedText());
        s.setWorkTitle(dto.workTitle());
        s.setChapter(dto.chapter());
        s.setAuthor(dto.author());
        s.setDynasty(dto.dynasty());
        s.setTranslator(dto.translator());
        s.setNotes(dto.notes());
        if (create || dto.status() != null) {
            s.setStatus(dto.status() == null ? SegmentStatus.PUBLISHED : dto.status());
        }
        s.setContentHash(ContentHash.sha256(dto.sourceText(), dto.translatedText()));
        if (dto.tagIds() != null) {
            s.setTags(new HashSet<>(tagRepository.findAllById(dto.tagIds())));
        }
    }
}

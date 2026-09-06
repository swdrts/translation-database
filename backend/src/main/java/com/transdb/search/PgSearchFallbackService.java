package com.transdb.search;

import com.transdb.domain.Role;
import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;
import com.transdb.dto.*;
import com.transdb.repository.SegmentRepository;
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
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PgSearchFallbackService {

    private final SegmentRepository segmentRepository;
    private final com.transdb.repository.TagRepository tagRepository;

    @Transactional(readOnly = true)
    public SearchResponseVO search(SearchQueryParams p, LoginUser operator) {
        int size = Math.min(Math.max(p.size(), 1), 50);
        int page = Math.max(p.page(), 0);
        if ((long) page * size + size > 1000) {
            throw com.transdb.common.BusinessException.of(com.transdb.common.ErrorCode.VALIDATION_FAILED,
                    "分页过深：page*size 不得超过 1000");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));

        Specification<Segment> spec = buildSpec(p, operator);
        Page<Segment> result = segmentRepository.findAll(spec, pageable);

        List<SearchItemVO> items = result.getContent().stream().map(this::toItem).toList();
        return new SearchResponseVO(items, result.getTotalElements(), page, size, true, FacetsVO.empty());
    }

    private Specification<Segment> buildSpec(SearchQueryParams p, LoginUser operator) {
        String q = p.q();
        String field = p.field() == null ? "all" : p.field();
        List<String> tags = p.tags();
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (operator.role() == Role.VIEWER) {
                ps.add(cb.equal(root.get("status"), SegmentStatus.PUBLISHED));
            }
            if (tags != null && !tags.isEmpty()) {
                ps.add(root.join("tags").get("name").in(tags));
                query.distinct(true);
            }
            if (p.dynasty() != null && !p.dynasty().isBlank()) {
                ps.add(cb.equal(root.get("dynasty"), p.dynasty()));
            }
            if (p.work() != null && !p.work().isBlank()) {
                ps.add(cb.equal(root.get("workTitle"), p.work()));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.toLowerCase(Locale.ROOT) + "%";
                List<Predicate> ors = new ArrayList<>();
                if ("all".equals(field) || "source".equals(field)) {
                    ors.add(cb.like(cb.lower(root.get("sourceText")), like));
                }
                if ("all".equals(field) || "translation".equals(field)) {
                    ors.add(cb.like(cb.lower(root.get("translatedText")), like));
                }
                if ("all".equals(field)) {
                    ors.add(cb.like(cb.lower(root.get("workTitle")), like));
                }
                ps.add(cb.or(ors.toArray(new Predicate[0])));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private SearchItemVO toItem(Segment s) {
        return new SearchItemVO(s.getId(), s.getSourceText(), s.getTranslatedText(),
                s.getWorkTitle(), s.getChapter(), s.getAuthor(), s.getDynasty(), s.getTranslator(),
                s.getTags().stream().map(t -> t.getName()).sorted().toList(),
                java.util.Map.of(), 0d);
    }

    @Transactional(readOnly = true)
    public FacetsVO facets() {
        List<FacetItem> dynasties = segmentRepository.findDistinctDynasties().stream()
                .filter(d -> d != null && !d.isBlank())
                .map(d -> new FacetItem(d, 0)).toList();
        List<FacetItem> works = segmentRepository.findDistinctWorkTitles().stream()
                .filter(w -> w != null && !w.isBlank())
                .map(w -> new FacetItem(w, 0)).toList();
        List<FacetItem> tags = tagRepository.findAll().stream()
                .map(t -> new FacetItem(t.getName(), 0)).toList();
        return new FacetsVO(tags, dynasties, works);
    }
}

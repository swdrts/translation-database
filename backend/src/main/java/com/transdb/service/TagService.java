package com.transdb.service;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.domain.ChangeType;
import com.transdb.domain.SegmentChangedEvent;
import com.transdb.domain.Tag;
import com.transdb.dto.TagUpsertDTO;
import com.transdb.dto.TagVO;
import com.transdb.repository.SegmentRepository;
import com.transdb.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;
    private final SegmentRepository segmentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<TagVO> listAll() {
        return tagRepository.findAll(Sort.by("name"))
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
        // 重命名不改变 join 行：保存后按 tagId 查携带该标签的句段，通知 ES 更新 tags 数组
        for (Long segmentId : segmentRepository.findIdsByTagId(tag.getId())) {
            eventPublisher.publishEvent(new SegmentChangedEvent(segmentId, ChangeType.UPDATED));
        }
        return TagVO.from(tagRepository.save(tag));
    }

    @Transactional
    public void delete(long id) {
        if (!tagRepository.existsById(id)) {
            throw BusinessException.of(ErrorCode.TAG_NOT_FOUND);
        }
        // 删除前收集受影响句段（deleteById 级联删除 join 行，之后查询将为空）
        List<Long> affectedSegmentIds = segmentRepository.findIdsByTagId(id);
        tagRepository.deleteById(id);
        for (Long segmentId : affectedSegmentIds) {
            eventPublisher.publishEvent(new SegmentChangedEvent(segmentId, ChangeType.UPDATED));
        }
    }
}

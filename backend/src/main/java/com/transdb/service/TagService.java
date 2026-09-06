package com.transdb.service;

import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.domain.Tag;
import com.transdb.dto.TagUpsertDTO;
import com.transdb.dto.TagVO;
import com.transdb.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;

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

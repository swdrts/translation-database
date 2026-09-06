package com.transdb.dto;

import com.transdb.domain.Segment;
import com.transdb.domain.SegmentStatus;

import java.time.Instant;
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

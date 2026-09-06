package com.transdb.dto;

import com.transdb.domain.Tag;

public record TagVO(long id, String name, String description) {

    public static TagVO from(Tag t) {
        return new TagVO(t.getId(), t.getName(), t.getDescription());
    }
}

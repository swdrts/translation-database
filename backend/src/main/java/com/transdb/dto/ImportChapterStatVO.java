package com.transdb.dto;

/** 会话内章节及分段数；title 为空串代表未分章。 */
public record ImportChapterStatVO(String title, long rowCount) {
}

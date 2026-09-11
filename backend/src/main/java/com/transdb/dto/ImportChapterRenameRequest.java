package com.transdb.dto;

/** 章节改名；from 为空串表示"未分章"；to 为空串表示把该章段归入"未分章"。 */
public record ImportChapterRenameRequest(String from, String to) {
}

package com.transdb.dto;

/** 在 atChar 字符偏移处拆分为两段（0 < atChar < 文本长度）。 */
public record ImportRowSplitRequest(int atChar) {
}

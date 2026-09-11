package com.transdb.dto;

/** PATCH 编辑单段：text/chapter 至少传一个，均可选。 */
public record ImportRowEditRequest(String text, String chapter) {
}

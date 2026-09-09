package com.transdb.dto;

/** mode/format 用字符串接收、控制器内解析，非法值给友好错误（与 ImportTextRole 同惯例）。 */
public record ExportRequest(String workTitle, String mode, String format) {
}

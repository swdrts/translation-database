package com.transdb.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    WRONG_CREDENTIALS(HttpStatus.UNAUTHORIZED, 1001, "用户名或密码错误"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, 1002, "未认证"),
    USER_DISABLED(HttpStatus.UNAUTHORIZED, 1004, "账号已被禁用"),
    SEGMENT_NOT_FOUND(HttpStatus.NOT_FOUND, 2001, "条目不存在"),
    OPTIMISTIC_LOCK(HttpStatus.CONFLICT, 2002, "数据已被他人修改，请刷新后重试"),
    SEGMENT_FORBIDDEN(HttpStatus.FORBIDDEN, 2003, "无权查看该条目"),
    IMPORT_FILE_UNREADABLE(HttpStatus.BAD_REQUEST, 3001, "导入文件无法解析或格式不受支持"),
    IMPORT_FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, 3002, "导入文件超出大小或行数上限"),
    IMPORT_PREVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, 3003, "导入预览不存在或已过期"),
    IMPORT_PREVIEW_FORBIDDEN(HttpStatus.FORBIDDEN, 3004, "只能确认自己创建的导入预览"),
    IMPORT_NO_ROWS(HttpStatus.BAD_REQUEST, 3005, "导入文件中没有数据行"),
    USERNAME_EXISTS(HttpStatus.CONFLICT, 5001, "用户名已存在"),
    TAG_NAME_EXISTS(HttpStatus.CONFLICT, 5002, "标签名称已存在"),
    TAG_NOT_FOUND(HttpStatus.NOT_FOUND, 5003, "标签不存在"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, 5004, "用户不存在"),
    SELF_MODIFY_FORBIDDEN(HttpStatus.BAD_REQUEST, 5005, "不能修改自己的角色或状态"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, 9001, "参数校验失败"),
    NOT_FOUND(HttpStatus.NOT_FOUND, 9002, "资源不存在"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, 9003, "服务器内部错误"),
    REINDEX_ALREADY_RUNNING(HttpStatus.CONFLICT, 4001, "索引重建正在进行中"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, 9004, "无权限访问"),
    EXPORT_WORK_NOT_FOUND(HttpStatus.NOT_FOUND, 6001, "该书名不存在或没有可导出的内容");

    private final HttpStatus status;
    private final int code;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, int code, String defaultMessage) {
        this.status = status;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() { return status; }
    public int getCode() { return code; }
    public String getDefaultMessage() { return defaultMessage; }
}

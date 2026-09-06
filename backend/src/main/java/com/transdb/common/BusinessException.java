package com.transdb.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    private BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static BusinessException of(ErrorCode errorCode) {
        return new BusinessException(errorCode, errorCode.getDefaultMessage());
    }

    public static BusinessException of(ErrorCode errorCode, String message) {
        return new BusinessException(errorCode, message);
    }
}

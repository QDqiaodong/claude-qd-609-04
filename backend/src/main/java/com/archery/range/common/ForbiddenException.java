package com.archery.range.common;

/**
 * 越权操作：角色无权执行该动作。统一异常处理转成 403 + 中文提示（无权操作）。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}

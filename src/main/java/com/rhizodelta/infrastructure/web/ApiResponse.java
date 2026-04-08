package com.rhizodelta.infrastructure.web;

/**
 * 表示统一的 API 响应包裹结构。
 *
 * <p>该对象把响应码、提示消息和业务数据收敛为固定格式，
 * 让控制器和异常处理器都能以一致方式返回结果。
 */
public record ApiResponse<T>(Integer code, String message, T data) {
    private static final int SUCCESS_CODE = 0;
    private static final int BAD_REQUEST_CODE = 40001;
    private static final int UNAUTHORIZED_CODE = 40101;
    private static final int FORBIDDEN_CODE = 40301;
    private static final int CONFLICT_CODE = 40901;
    private static final int NOT_FOUND_CODE = 40401;
    private static final int INTERNAL_ERROR_CODE = 50001;

    /**
     * 构造成功响应。
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, "ok", data);
    }

    /**
     * 构造 400 响应。
     */
    public static <T> ApiResponse<T> badRequest(String message) {
        return new ApiResponse<>(BAD_REQUEST_CODE, message, null);
    }

    /**
     * 构造 401 响应。
     */
    public static <T> ApiResponse<T> unauthorized(String message) {
        return new ApiResponse<>(UNAUTHORIZED_CODE, message, null);
    }

    /**
     * 构造 403 响应。
     */
    public static <T> ApiResponse<T> forbidden(String message) {
        return new ApiResponse<>(FORBIDDEN_CODE, message, null);
    }

    /**
     * 构造 409 响应。
     */
    public static <T> ApiResponse<T> conflict(String message) {
        return new ApiResponse<>(CONFLICT_CODE, message, null);
    }

    /**
     * 构造 404 响应。
     */
    public static <T> ApiResponse<T> notFound(String message) {
        return new ApiResponse<>(NOT_FOUND_CODE, message, null);
    }

    /**
     * 构造 500 响应。
     */
    public static <T> ApiResponse<T> internalError(String message) {
        return new ApiResponse<>(INTERNAL_ERROR_CODE, message, null);
    }
}

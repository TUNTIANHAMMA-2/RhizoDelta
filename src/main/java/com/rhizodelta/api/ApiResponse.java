package com.rhizodelta.api;

public record ApiResponse<T>(Integer code, String message, T data) {
    private static final int SUCCESS_CODE = 0;
    private static final int BAD_REQUEST_CODE = 40001;
    private static final int UNAUTHORIZED_CODE = 40101;
    private static final int CONFLICT_CODE = 40901;
    private static final int NOT_FOUND_CODE = 40401;
    private static final int INTERNAL_ERROR_CODE = 50001;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, "ok", data);
    }

    public static <T> ApiResponse<T> badRequest(String message) {
        return new ApiResponse<>(BAD_REQUEST_CODE, message, null);
    }

    public static <T> ApiResponse<T> unauthorized(String message) {
        return new ApiResponse<>(UNAUTHORIZED_CODE, message, null);
    }

    public static <T> ApiResponse<T> conflict(String message) {
        return new ApiResponse<>(CONFLICT_CODE, message, null);
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return new ApiResponse<>(NOT_FOUND_CODE, message, null);
    }

    public static <T> ApiResponse<T> internalError(String message) {
        return new ApiResponse<>(INTERNAL_ERROR_CODE, message, null);
    }
}

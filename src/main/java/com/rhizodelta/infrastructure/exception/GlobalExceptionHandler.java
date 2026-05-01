package com.rhizodelta.infrastructure.exception;

import com.rhizodelta.consensus.domain.exception.DagIntegrityViolationException;
import com.rhizodelta.core.domain.association.AssociationType;
import com.rhizodelta.consensus.domain.exception.RollbackBlockedException;
import com.rhizodelta.infrastructure.exception.ConflictException;
import com.rhizodelta.infrastructure.web.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 负责将常见异常映射为统一的 HTTP 响应。
 *
 * <p>该处理器把业务异常、鉴权异常和部分基础设施异常收敛为
 * {@link ApiResponse}，从而避免控制器层重复编写异常到响应的转换逻辑。
 */
@ControllerAdvice
public class GlobalExceptionHandler {
    private static final int CONFLICT_CODE = 40901;
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理请求参数或业务校验类错误。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.badRequest(exception.getMessage()));
    }

    /**
     * 处理资源不存在错误。
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.notFound(exception.getMessage()));
    }

    /**
     * 处理 DAG 完整性冲突。
     */
    @ExceptionHandler(DagIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDagIntegrityViolation(DagIntegrityViolationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.conflict(exception.getMessage()));
    }

    /**
     * 处理资源已存在等业务级冲突。
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.conflict(exception.getMessage()));
    }

    /**
     * 处理回滚受阻错误，并返回依赖节点列表。
     */
    @ExceptionHandler(RollbackBlockedException.class)
    public ResponseEntity<ApiResponse<RollbackBlockedData>> handleRollbackBlocked(RollbackBlockedException exception) {
        RollbackBlockedData data = new RollbackBlockedData(exception.dependent_node_ids());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiResponse<>(CONFLICT_CODE, exception.getMessage(), data));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(HttpMessageNotReadableException exception) {
        Throwable cause = exception.getMostSpecificCause();
        if (cause instanceof IllegalArgumentException) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.badRequest(cause.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.badRequest("Malformed request body"));
    }

    /**
     * 处理 URL 参数类型不匹配错误。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        if (AssociationType.class.equals(exception.getRequiredType())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.badRequest("Invalid association type. Allowed values: CONCEPTUAL_OVERLAP, RELATES_TO"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.badRequest("Invalid parameter type: " + exception.getName()));
    }

    /**
     * 处理认证失败错误。
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.unauthorized(exception.getMessage()));
    }

    /**
     * 处理运行期非法状态错误。
     *
     * <p>该类异常通常表示内部流程或基础设施状态异常，因此会记录错误日志并返回通用 500。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException exception) {
        LOGGER.error("Internal error: {}", exception.getMessage(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.internalError("internal server error"));
    }

    /**
     * 处理 IO 异常。
     *
     * <p>对于 Broken pipe 这类客户端主动断连场景，会直接忽略而不再返回响应。
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Void>> handleIOException(IOException exception) {
        String message = exception.getMessage();
        if (message != null && message.contains("Broken pipe")) {
            LOGGER.debug("Client disconnected (broken pipe), ignoring");
            return null;
        }
        LOGGER.error("Unhandled IOException", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.internalError("internal server error"));
    }

    /**
     * 处理兜底未捕获异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleInternalError(Exception exception) {
        LOGGER.error("Unhandled exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.internalError("internal server error"));
    }

    /**
     * 表示回滚受阻时的附加响应数据。
     */
    public record RollbackBlockedData(List<UUID> dependent_node_ids) {
    }
}

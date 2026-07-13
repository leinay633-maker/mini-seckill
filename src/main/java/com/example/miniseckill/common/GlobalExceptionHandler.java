package com.example.miniseckill.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converts common exceptions into the unified Result format.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        return error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception ex) {
        return error(400, "参数不合法");
    }

    @ExceptionHandler({
            RedisConnectionFailureException.class,
            RedisSystemException.class
    })
    public ResponseEntity<Result<Void>> handleRedisException(Exception ex) {
        log.warn("redis unavailable", ex);
        return error(503, "Redis 不可用，秒杀入口已降级");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex) {
        log.error("unexpected error", ex);
        return error(500, "系统繁忙，请稍后重试");
    }

    private ResponseEntity<Result<Void>> error(int code, String message) {
        return ResponseEntity.status(httpStatus(code)).body(Result.fail(code, message));
    }

    private HttpStatus httpStatus(int code) {
        if (code == 1002) {
            return HttpStatus.CONFLICT;
        }
        HttpStatus status = HttpStatus.resolve(code);
        if (status != null) {
            return status;
        }
        if (code >= 500) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (code >= 400) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.OK;
    }
}

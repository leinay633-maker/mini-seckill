package com.example.miniseckill.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionUsesMatchingHttpStatusAndBodyCode() {
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(
                new BusinessException(429, "请求过于频繁")
        );

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(429, response.getBody().getCode());
    }

    @Test
    void customStockCodeMapsToConflictWhenThrownAsException() {
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(
                new BusinessException(1002, "库存不足")
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1002, response.getBody().getCode());
    }

    @Test
    void validationErrorsUseBadRequestStatus() {
        ResponseEntity<Result<Void>> response = handler.handleBadRequest(new IllegalArgumentException("bad"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().getCode());
    }
}

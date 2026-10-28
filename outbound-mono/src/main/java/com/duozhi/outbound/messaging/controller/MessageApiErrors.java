package com.duozhi.outbound.messaging.controller;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.messaging.service.Messaging;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = MessageApi.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MessageApiErrors {
    @ExceptionHandler(ApiError.class)
    public ResponseEntity<?> error(ApiError error) {
        String code =
                switch (error.status) {
                    case 401 -> "UNAUTHENTICATED";
                    case 403 -> "FORBIDDEN";
                    case 404 -> "NOT_FOUND";
                    case 409 -> "CONFLICT";
                    case 413 -> "PAYLOAD_TOO_LARGE";
                    case 422 -> "INVALID_REQUEST";
                    case 429 -> "RATE_LIMITED";
                    case 503 -> "UNAVAILABLE";
                    default -> "INVALID_REQUEST";
                };
        return ResponseEntity.status(error.status)
                .body(
                        Map.of(
                                "request_id",
                                Messaging.id("req"),
                                "code",
                                code,
                                "message",
                                error.getMessage(),
                                "retryable",
                                error.status == 429));
    }
}

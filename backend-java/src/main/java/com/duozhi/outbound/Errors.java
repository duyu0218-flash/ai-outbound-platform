package com.duozhi.outbound;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> api(ApiError e) { return ResponseEntity.status(e.status).body(Values.map("detail", e.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Values.map("detail", "invalid request")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(Exception e) { return ResponseEntity.status(409).body(Values.map("detail", "data conflicts with existing records")); }
}

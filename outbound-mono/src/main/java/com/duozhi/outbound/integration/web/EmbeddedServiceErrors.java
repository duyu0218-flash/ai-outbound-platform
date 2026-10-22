package com.duozhi.outbound.integration.web;

import com.duozhi.outbound.ai.controller.EmbeddedAgentController;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.recording.controller.RecordingController;
import com.duozhi.outbound.voice.controller.VoiceController;
import java.util.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * Preserve service-specific status, detail and retry headers without changing backend API errors.
 */
@RestControllerAdvice(
        assignableTypes = {
            EmbeddedAgentController.class,
            VoiceController.class,
            RecordingController.class
        })
@Order(-10)
public class EmbeddedServiceErrors {
    @ExceptionHandler(Support.Failure.class)
    ResponseEntity<?> failure(Support.Failure error) {
        var result = ResponseEntity.status(error.status);
        error.headers.forEach(result::header);
        return result.body(Map.of("detail", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException error) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", error.getMessage()));
    }
}

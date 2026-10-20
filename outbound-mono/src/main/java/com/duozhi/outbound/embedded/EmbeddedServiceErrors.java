package com.duozhi.outbound.embedded;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
/** Preserve service-specific status, detail and retry headers without changing backend API errors. */
@RestControllerAdvice(assignableTypes={EmbeddedAgentController.class,VoiceController.class,RecordingController.class})
@org.springframework.core.annotation.Order(-10)
public class EmbeddedServiceErrors {
    @ExceptionHandler(Support.Failure.class) ResponseEntity<?> failure(Support.Failure error){var result=ResponseEntity.status(error.status);error.headers.forEach(result::header);return result.body(Map.of("detail",error.getMessage()));}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(IllegalArgumentException error){return ResponseEntity.unprocessableEntity().body(Map.of("detail",error.getMessage()));}
}

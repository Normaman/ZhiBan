package com.pddcopilot.core.api;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
  org.springframework.http.ResponseEntity<?> authentication(Exception error) {
    return org.springframework.http.ResponseEntity.status(401)
        .body(Map.of("code", "AUTHENTICATION_FAILED"));
  }

  @ExceptionHandler(org.springframework.dao.EmptyResultDataAccessException.class)
  org.springframework.http.ResponseEntity<?> missingRow(Exception error) {
    return org.springframework.http.ResponseEntity.status(404).body(Map.of("code", "NOT_FOUND"));
  }

  @ExceptionHandler(ResponseStatusException.class)
  org.springframework.http.ResponseEntity<?> response(ResponseStatusException error) {
    return org.springframework.http.ResponseEntity.status(error.getStatusCode())
        .body(Map.of("code", error.getReason() == null ? "REQUEST_FAILED" : error.getReason()));
  }

  @ExceptionHandler(com.pddcopilot.core.platform.pdd.PddPlatformException.class)
  org.springframework.http.ResponseEntity<?> platform(
      com.pddcopilot.core.platform.pdd.PddPlatformException error) {
    return org.springframework.http.ResponseEntity.status(502).body(Map.of("code", error.code()));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  org.springframework.http.ResponseEntity<?> invalid(Exception error) {
    return org.springframework.http.ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_REQUEST"));
  }

  @ExceptionHandler(Exception.class)
  org.springframework.http.ResponseEntity<?> unexpected(
      Exception error, jakarta.servlet.http.HttpServletRequest request) {
    org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class)
        .error(
            "request_failed trace_id={} error_type={}",
            request.getAttribute("trace_id"),
            error.getClass().getSimpleName());
    return org.springframework.http.ResponseEntity.internalServerError()
        .body(Map.of("code", "INTERNAL_ERROR"));
  }
}

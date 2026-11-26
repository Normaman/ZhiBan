package com.pddcopilot.core.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Value conversions shared by the workflow services; no mutable application state. */
public final class WorkflowValues {
  private WorkflowValues() {}

  public static final long CONNECTOR_STALE_AFTER_MS = 20_000;

  public static long nowMillis() {
    return System.currentTimeMillis();
  }

  public static String newId() {
    return UUID.randomUUID().toString();
  }

  public static long asLong(Object value) {
    return ((Number) value).longValue();
  }

  public static ResponseStatusException conflict(String code) {
    return new ResponseStatusException(HttpStatus.CONFLICT, code);
  }

  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}

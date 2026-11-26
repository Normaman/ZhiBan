package com.pddcopilot.core.repository;

import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Explicit missing-row semantics at the repository boundary. */
public final class DatabaseRows {
  private DatabaseRows() {}

  public static Map<String, Object> required(Map<String, Object> row) {
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND");
    return row;
  }

  public static Map<String, Object> required(List<Map<String, Object>> rows) {
    return required(rows.isEmpty() ? null : rows.get(0));
  }

  public static Map<String, Object> single(Map<String, Object> row) {
    if (row == null) throw new EmptyResultDataAccessException(1);
    return row;
  }
}

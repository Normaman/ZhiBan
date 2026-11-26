package com.pddcopilot.core.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/** Apply validity at a pinned run time; facts and reception guidelines use the same scope. */
public final class KnowledgeEntries {
  private KnowledgeEntries() {}
  public static Instant time(Map<?,?> entry,String field) {
    Object value=entry.get(field);
    return value==null ? null : OffsetDateTime.parse(value.toString()).toInstant();
  }
  public static List<?> active(Object snapshot,long at) {
    if (!(snapshot instanceof List<?> entries)) return List.of();
    Instant point=Instant.ofEpochMilli(at);
    return entries.stream().filter(item -> {
      if (!(item instanceof Map<?,?> entry)) return false;
      var from=time(entry,"valid_from"); var until=time(entry,"valid_until");
      return (from==null || !point.isBefore(from)) && (until==null || point.isBefore(until));
    }).toList();
  }
}

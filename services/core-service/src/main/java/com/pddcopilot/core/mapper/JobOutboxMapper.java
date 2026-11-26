package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for JobOutboxRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface JobOutboxMapper {
  List<Map<String, Object>> claimDue(
      @Param("now") long now,
      @Param("limit") int limit,
      @Param("leaseUntil") long leaseUntil,
      @Param("claimId") String claimId);

  int published(
      @Param("timestamp") long timestamp, @Param("id") String id, @Param("claimId") String claimId);

  int retry(
      @Param("availableAt") long availableAt,
      @Param("errorCode") String errorCode,
      @Param("id") String id,
      @Param("claimId") String claimId);

  Long countActive();
}

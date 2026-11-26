package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for ProductRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface ProductMapper {
  List<Map<String, Object>> details(@Param("shopId") String shopId, @Param("goods") String goods);

  List<Map<String, Object>> summaries(@Param("shop") String shop);

  List<Map<String, Object>> latestRun(@Param("shopId") String shopId);

  Map<String, Object> run(@Param("id") String id, @Param("shopId") String shopId);

  List<Map<String, Object>> activeRun(@Param("shopId") String shopId);

  int insertRun(
      @Param("id") String id,
      @Param("shopId") String shopId,
      @Param("count") int count,
      @Param("now") long now);

  int insertItem(@Param("id") String id, @Param("run") String run, @Param("goods") String goods);

  int sourceHash(@Param("hash") String hash, @Param("item") String item);

  int skip(@Param("item") String item);

  int savedSource(
      @Param("hash") String hash, @Param("shopId") String shopId, @Param("goods") String goods);

  Map<String, Object> item(@Param("id") String id);

  int markRunning(@Param("id") Object id);

  Map<String, Object> lockRun(@Param("id") Object id);

  int finishItem(
      @Param("value") Object value, @Param("value2") Object value2, @Param("id") Object id);

  Map<String, Object> countItemStates(@Param("run") Object run);

  int updateProgress(
      @Param("done") Object done,
      @Param("skipped") Object skipped,
      @Param("failed") Object failed,
      @Param("value") Object value,
      @Param("now") long now,
      @Param("run") Object run);

  int insertDetail(
      @Param("shopId") String shopId,
      @Param("goods") String goods,
      @Param("payload") String payload,
      @Param("category") String category,
      @Param("skus") int skus,
      @Param("attributes") int attributes,
      @Param("now") long now);

  int updateDetail(
      @Param("payload") String payload,
      @Param("category") String category,
      @Param("skus") int skus,
      @Param("attributes") int attributes,
      @Param("now") long now,
      @Param("shopId") String shopId,
      @Param("goods") String goods);
}

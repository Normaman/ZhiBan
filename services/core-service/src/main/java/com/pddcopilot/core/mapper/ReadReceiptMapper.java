package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for ReadReceiptRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface ReadReceiptMapper {
  Map<String, Object> lockShop(@Param("shop") String shop);

  List<Map<String, Object>> find(@Param("shop") String shop, @Param("buyer") String buyer);

  int insert(
      @Param("shop") String shop,
      @Param("buyer") String buyer,
      @Param("last") String last,
      @Param("minimum") String minimum,
      @Param("observed") long observed);

  int update(
      @Param("last") String last,
      @Param("minimum") String minimum,
      @Param("observed") long observed,
      @Param("shop") String shop,
      @Param("buyer") String buyer);
}

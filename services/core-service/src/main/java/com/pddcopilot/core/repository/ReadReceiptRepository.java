package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.ReadReceiptMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class ReadReceiptRepository {
  private final ReadReceiptMapper mapper;
  private final BusinessStore store;

  public ReadReceiptRepository(ReadReceiptMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public Map<String, Object> lockShop(String shop) {
    return DatabaseRows.required(mapper.lockShop(shop));
  }

  public List<Map<String, Object>> find(String shop, String buyer) {
    return mapper.find(shop, buyer);
  }

  public void insert(String shop, String buyer, String last, String minimum, long observed) {
    mapper.insert(shop, buyer, last, minimum, observed);
  }

  public void update(String shop, String buyer, String last, String minimum, long observed) {
    mapper.update(last, minimum, observed, shop, buyer);
  }
}

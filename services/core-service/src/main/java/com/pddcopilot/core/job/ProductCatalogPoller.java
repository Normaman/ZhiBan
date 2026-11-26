package com.pddcopilot.core.job;

import com.pddcopilot.core.platform.pdd.PddCommerceService;
import com.pddcopilot.core.repository.ConnectionRepository;
import com.pddcopilot.core.service.KnowledgeService;
import com.pddcopilot.core.service.ProductService;
import com.pddcopilot.core.support.ShopScope;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Poll complete platform listings, persist only changed snapshots, then queue changed details. */
@Component
public class ProductCatalogPoller {
  private static final Logger log = LoggerFactory.getLogger(ProductCatalogPoller.class);
  private static final String ACTOR = "system:product-poller";

  private final ConnectionRepository connections;
  private final PddCommerceService commerce;
  private final KnowledgeService knowledge;
  private final ProductService products;

  public ProductCatalogPoller(
      ConnectionRepository connections,
      PddCommerceService commerce,
      KnowledgeService knowledge,
      ProductService products) {
    this.connections = connections;
    this.commerce = commerce;
    this.knowledge = knowledge;
    this.products = products;
  }

  @Scheduled(
      initialDelayString = "${copilot.product-catalog-poll-initial-delay-ms:60000}",
      fixedDelayString = "${copilot.product-catalog-poll-interval-ms:300000}")
  public void poll() {
    for (Map<String, Object> shop : connections.findBoundLiveShops()) {
      String shopId = shop.get("id").toString();
      try (var scope = ShopScope.open(shopId)) {
        Map<String, Object> listing = commerce.products();
        Map<String, Object> saved = knowledge.saveProducts(shopId, listing, ACTOR, false);
        boolean changed = Boolean.FALSE.equals(saved.get("unchanged"));
        int count = ((Number) saved.get("count")).intValue();
        boolean detailsQueued = count > 0 && products.detailsNeedSync();
        if (detailsQueued) products.start(ACTOR);
        log.info(
            "product_catalog_poll shop_id={} count={} changed={} details_queued={}",
            shopId,
            count,
            changed,
            detailsQueued);
      } catch (RuntimeException error) {
        log.warn(
            "product_catalog_poll_failed shop_id={} error_type={}",
            shopId,
            error.getClass().getSimpleName());
      }
    }
  }
}

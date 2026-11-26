package com.pddcopilot.core.platform.pdd;

import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.ConnectionRepository;
import com.pddcopilot.core.service.ConnectorRegistryService;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Lists use Camoufox directly; details retain the original-first fallback policy. */
@Service
public class PddOrderQueryService {
  private static final Logger log = LoggerFactory.getLogger(PddOrderQueryService.class);
  private final PddOrderBrowserService original;
  private final CamoufoxOrderService camoufox;
  private final BusinessStore store;
  private final ConnectionRepository shops;
  private final ConnectorRegistryService registry;
  private final boolean enableCamoufoxFallback;

  public PddOrderQueryService(PddOrderBrowserService original, CamoufoxOrderService camoufox,
      BusinessStore store, ConnectionRepository shops, ConnectorRegistryService registry,
      @Value("${copilot.pdd-order.enable-camoufox-fallback:false}") boolean enableCamoufoxFallback) {
    this.original = original;
    this.camoufox = camoufox;
    this.store = store;
    this.shops = shops;
    this.registry = registry;
    this.enableCamoufoxFallback = enableCamoufoxFallback;
  }

  /** Uses the existing authenticated shop scope; never selects a shop from URL data. */
  public OrderResult queryPddOrder(String url) {
    PddOrderTarget target = PddOrderTarget.parse(url);
    Map<String, Object> shop = shops.findShop(store.shopId()).stream().findFirst()
        .orElseThrow(() -> new PddPlatformException("PDD_SHOP_NOT_BOUND"));
    if (!"live".equals(shop.get("mode")) || !"bound".equals(shop.get("binding_state")))
      throw new PddPlatformException("PDD_SHOP_NOT_BOUND");
    Path directory = registry.directory(store.shopId());
    if (directory == null) throw new PddPlatformException("PDD_CREDENTIALS_UNAVAILABLE");
    String platformShop = String.valueOf(shop.get("platform_shop_id"));
    String account = String.valueOf(shop.get("connector_account_id"));
    return target.detail() ? detail(directory, platformShop, account, target.orderId())
        : list(directory, platformShop, account, target.orderType(), target.page());
  }

  OrderResult detail(Path directory, String shop, String account, String orderId) {
    PddOrderTarget target = PddOrderTarget.parse(PddWebClient.BASE_URL + "/orders/detail?sn="
        + java.net.URLEncoder.encode(orderId, java.nio.charset.StandardCharsets.UTF_8));
    return execute(target, shop, account, () -> original.read(directory, shop, account, orderId));
  }

  OrderResult list(Path directory, String shop, String account, int type, int page) {
    if (!java.util.Set.of(1, 2, 7).contains(type) || page < 1 || page > 100)
      throw new PddPlatformException("ORDER_LIST_FILTER_INVALID");
    log.info("pdd_order_query channel=camoufox kind=list routing=direct attempt=1");
    return camoufox.query(PddOrderTarget.list(type, page), store.shopId(), shop, account);
  }

  private OrderResult execute(PddOrderTarget target, String shop, String account,
      Callable<Map<String, Object>> task) {
    log.info("pdd_order_query channel=original kind=detail attempt=1");
    try {
      return new OrderResult("original", target.detail() ? "detail" : "list", task.call());
    } catch (PddPlatformException error) {
      if (!enableCamoufoxFallback || !PddOrderResponseCodes.REJECTED.equals(error.code())) throw error;
      // No original-channel retry and no same-instance retry. HTTP errors, timeouts,
      // login expiry and other business failures do not enter this fallback.
      log.warn("pdd_order_fallback channel=camoufox attempt=1 original_business_code=40002");
      return camoufox.query(target, store.shopId(), shop, account);
    } catch (Exception error) {
      throw new PddPlatformException("ORDER_SESSION_CAPTURE_FAILED");
    }
  }
}

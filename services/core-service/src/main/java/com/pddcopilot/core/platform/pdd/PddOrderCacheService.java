package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.ConnectionRepository;
import com.pddcopilot.core.support.ShopScope;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** Redis snapshots survive navigation and restarts; leases coalesce refreshes across replicas. */
@Service
public class PddOrderCacheService {
  private static final Logger log = LoggerFactory.getLogger(PddOrderCacheService.class);
  private static final Duration FRESH = Duration.ofSeconds(60);
  private static final Duration RETAIN = Duration.ofHours(24);
  private static final Duration LEASE = Duration.ofSeconds(180);
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final DefaultRedisScript<Long> STORE = new DefaultRedisScript<>(
      "if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end "
          + "redis.call('SET',KEYS[2],ARGV[2],'EX',ARGV[3]); return 1", Long.class);
  private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
      "if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]) end return 0", Long.class);
  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final BusinessStore store;
  private final ConnectionRepository shops;
  private final PddCommerceService commerce;
  private final Executor executor;
  private final Executor overviewExecutor;

  public PddOrderCacheService(StringRedisTemplate redis, ObjectMapper json, BusinessStore store,
      ConnectionRepository shops, PddCommerceService commerce,
      @Qualifier("orderViewExecutor") Executor executor,
      @Qualifier("orderOverviewExecutor") Executor overviewExecutor) {
    this.redis = redis; this.json = json; this.store = store; this.shops = shops;
    this.commerce = commerce; this.executor = executor;
    this.overviewExecutor = overviewExecutor;
  }

  public Map<String, Object> list(int type, int page, boolean refresh) {
    if (!java.util.Set.of(1, 2, 7).contains(type) || page < 1 || page > 100)
      throw new PddPlatformException("ORDER_LIST_FILTER_INVALID");
    return read("list:" + type + ":" + page, refresh, () -> commerce.orderList(type, page));
  }

  public Map<String, Object> overview(boolean refresh) {
    return read("overview", refresh, () -> commerce.orderOverview(true));
  }

  private String identity(String shop) {
    var binding = shops.findShop(shop).stream().findFirst()
        .orElseThrow(() -> new PddPlatformException("PDD_SHOP_NOT_BOUND"));
    if (!"live".equals(binding.get("mode")) || !"bound".equals(binding.get("binding_state")))
      throw new PddPlatformException("PDD_SHOP_NOT_BOUND");
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
          json.writeValueAsBytes(List.of(shop, String.valueOf(binding.get("platform_shop_id")),
              String.valueOf(binding.get("connector_account_id"))))));
    } catch (Exception error) { throw new PddPlatformException("ORDER_CACHE_UNAVAILABLE"); }
  }

  private Map<String, Object> read(String resource, boolean force, Supplier<Map<String, Object>> loader) {
    String shop = store.shopId();
    String binding = identity(shop);
    // Braces keep all lease/snapshot/error keys in one Redis Cluster slot.
    String key = "copilot:orders:v1:{" + binding + ":" + resource + "}";
    try {
      Snapshot current = snapshot(key);
      if (force || (!current.fresh() && current.error() == null)) {
        schedule(key, shop, binding, resource, loader, force);
        current = snapshot(key);
      }
      Map<String, Object> result = new LinkedHashMap<>(current.data());
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("has_value", current.hasValue());
      metadata.put("state", !current.hasValue() ? "empty" : current.fresh() ? "fresh" : "stale");
      metadata.put("refreshing", current.refreshing());
      metadata.put("error_code", current.error() == null ? null : current.error().get("code"));
      metadata.put("expires_at", current.hasValue()
          ? Instant.ofEpochMilli(current.storedAt() + FRESH.toMillis()).toString() : null);
      result.put("cache", metadata);
      return result;
    } catch (PddPlatformException error) { throw error; }
    catch (Exception error) { throw new PddPlatformException("ORDER_CACHE_UNAVAILABLE"); }
  }

  private record Snapshot(Map<String, Object> data, long storedAt, boolean refreshing,
      Map<String, Object> error) {
    boolean hasValue() { return storedAt > 0; }
    boolean fresh() { return hasValue() && System.currentTimeMillis() - storedAt < FRESH.toMillis(); }
  }

  private Snapshot snapshot(String key) throws Exception {
    // Atomic MGET avoids returning an old value together with a newly released lease.
    List<String> values = redis.opsForValue().multiGet(List.of(key, key + ":lock", key + ":error"));
    if (values == null) throw new PddPlatformException("ORDER_CACHE_UNAVAILABLE");
    Map<String, Object> data = Map.of();
    long storedAt = 0;
    if (values.get(0) != null) {
      var envelope = json.readTree(values.get(0));
      data = json.convertValue(envelope.path("data"), MAP);
      storedAt = envelope.path("stored_at").asLong();
    }
    return new Snapshot(data, storedAt, values.get(1) != null,
        values.get(2) == null ? null : json.readValue(values.get(2), MAP));
  }

  private void schedule(String key, String shop, String binding, String resource,
      Supplier<Map<String, Object>> loader, boolean force) throws Exception {
    String token = UUID.randomUUID().toString();
    if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key + ":lock", token, LEASE))) return;
    try {
      // A different refresh may have finished between our read and lease acquisition.
      Snapshot latest = snapshot(key);
      if (!force && (latest.fresh() || latest.error() != null)) {
        unlock(key, token);
        return;
      }
      redis.delete(key + ":error");
      Executor worker = "overview".equals(resource) ? overviewExecutor : executor;
      worker.execute(() -> refresh(key, token, shop, binding, resource, loader));
    } catch (RejectedExecutionException busy) {
      log.info("pdd_order_cache_refresh resource={} state=busy", resource);
      try { failure(key, token, "ORDER_REFRESH_BUSY", 5); }
      finally { unlock(key, token); }
    } catch (Exception error) {
      unlock(key, token);
      throw error;
    }
  }

  private void refresh(String key, String token, String shop, String binding, String resource,
      Supplier<Map<String, Object>> loader) {
    try (var ignored = ShopScope.open(shop)) {
      if (!binding.equals(identity(shop))) throw new PddPlatformException("PDD_SHOP_NOT_BOUND");
      Map<String, Object> data = loader.get();
      if (!binding.equals(identity(shop))) throw new PddPlatformException("PDD_SHOP_NOT_BOUND");
      String value = json.writeValueAsString(Map.of("data", data, "stored_at", System.currentTimeMillis()));
      Long written = redis.execute(STORE, List.of(key + ":lock", key), token, value,
          String.valueOf(RETAIN.toSeconds()));
      log.info("pdd_order_cache_refresh resource={} state={}", resource,
          Long.valueOf(1).equals(written) ? "completed" : "discarded");
    } catch (Exception error) {
      String code = error instanceof PddPlatformException platform ? platform.code() : "ORDER_REFRESH_FAILED";
      try { failure(key, token, code, 30); }
      catch (Exception unavailable) { log.warn("pdd_order_cache_error_store_failed"); }
      log.warn("pdd_order_cache_refresh resource={} state=failed code={}", resource, code);
    } finally {
      try { unlock(key, token); }
      catch (Exception unavailable) { log.warn("pdd_order_cache_unlock_failed"); }
    }
  }

  private void failure(String key, String token, String code, int seconds) throws Exception {
    redis.execute(STORE, List.of(key + ":lock", key + ":error"), token,
        json.writeValueAsString(Map.of("code", code)), String.valueOf(seconds));
  }

  private void unlock(String key, String token) {
    redis.execute(UNLOCK, List.of(key + ":lock"), token);
  }
}

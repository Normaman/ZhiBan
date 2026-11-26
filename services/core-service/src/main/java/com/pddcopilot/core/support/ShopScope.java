package com.pddcopilot.core.support;

/** Request and worker scopes must close on the thread which opened them. */
public final class ShopScope implements AutoCloseable {
  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
  private final String previous;

  private ShopScope(String shopId) {
    if (shopId == null || shopId.isBlank()) throw new IllegalArgumentException("SHOP_REQUIRED");
    previous = CURRENT.get();
    CURRENT.set(shopId);
  }

  public static ShopScope open(String shopId) {
    return new ShopScope(shopId);
  }

  public static String current() {
    return CURRENT.get();
  }

  @Override
  public void close() {
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }
}

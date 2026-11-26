package com.pddcopilot.core.platform.pdd;

import com.pddcopilot.core.mapper.CamoufoxLoginStateMapper;
import com.pddcopilot.core.support.ModelSecretCipher;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Independent, encrypted Camoufox state. Never reads the Chromium credential store. */
@Service
public class CamoufoxLoginStateStore {
  public record Saved(String json, String revision) {}

  private final CamoufoxLoginStateMapper mapper;
  private final ModelSecretCipher cipher;

  public CamoufoxLoginStateStore(CamoufoxLoginStateMapper mapper, ModelSecretCipher cipher) {
    this.mapper = mapper;
    this.cipher = cipher;
  }

  public boolean exists(String shop) {
    return Boolean.TRUE.equals(mapper.exists(shop));
  }

  public Saved load(String shop, String platformShop, String account) {
    var rows = mapper.load(shop);
    if (rows.isEmpty()) throw new PddPlatformException("CAMOUFOX_LOGIN_REQUIRED");
    var row = rows.get(0);
    if (row.get("invalidated_at") != null) throw new PddPlatformException("CAMOUFOX_LOGIN_EXPIRED");
    if (!platformShop.equals(row.get("platform_shop_id")) || !account.equals(row.get("account_id")))
      throw new PddPlatformException("CAMOUFOX_SHOP_IDENTITY_MISMATCH");
    try {
      return new Saved(
          cipher.decrypt((String) row.get("encrypted_state"), scope(shop, platformShop, account)),
          (String) row.get("revision"));
    } catch (Exception error) {
      throw new PddPlatformException("CAMOUFOX_LOGIN_STATE_UNAVAILABLE");
    }
  }

  public void save(String shop, String platformShop, String account, String state) {
    validateSize(state);
    mapper.save(
        shop,
        platformShop,
        account,
        cipher.encrypt(state, scope(shop, platformShop, account)),
        UUID.randomUUID().toString(),
        System.currentTimeMillis());
  }

  public void refresh(
      String shop, String platformShop, String account, Saved previous, String state) {
    validateSize(state);
    // A running query must not overwrite a newer, independently approved login.
    mapper.refresh(
        cipher.encrypt(state, scope(shop, platformShop, account)),
        UUID.randomUUID().toString(),
        System.currentTimeMillis(),
        shop,
        previous.revision());
  }

  public void invalidate(String shop, Saved previous) {
    mapper.invalidate(System.currentTimeMillis(), shop, previous.revision());
  }

  private String scope(String shop, String platform, String account) {
    return "camoufox-login:" + shop + ":" + platform + ":" + account;
  }

  private void validateSize(String state) {
    if (state == null || state.length() > 8 * 1024 * 1024)
      throw new PddPlatformException("CAMOUFOX_LOGIN_STATE_TOO_LARGE");
  }
}

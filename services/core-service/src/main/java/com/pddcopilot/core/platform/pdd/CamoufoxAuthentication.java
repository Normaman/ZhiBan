package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Login checks run inside Firefox's own network stack, never a Chromium HTTP client. */
@Service
public class CamoufoxAuthentication {
  private static final Logger log = LoggerFactory.getLogger(CamoufoxAuthentication.class);
  private final ObjectMapper json;
  public CamoufoxAuthentication(ObjectMapper json) { this.json = json; }

  public boolean loginPage(Page page) {
    try {
      URI url = URI.create(page.url());
      return !"mms.pinduoduo.com".equals(url.getHost()) || url.getPath().startsWith("/login");
    } catch (Exception error) { return true; }
  }

  public boolean interactiveLoginPage(Page page) {
    try {
      URI uri = URI.create(page.url());
      return "https".equals(uri.getScheme()) && "mms.pinduoduo.com".equals(uri.getHost())
          && uri.getPath().startsWith("/login");
    } catch (Exception error) { return false; }
  }

  public boolean challenge(Page page) {
    for (var frame : page.frames()) {
      try {
        URI uri = URI.create(frame.url());
        if (java.util.Set.of("captcha.pinduoduo.com", "captcha.yangkeduo.com").contains(uri.getHost())) {
          if (frame == page.mainFrame()) return true;
          var element = frame.frameElement();
          try { if (element.isVisible()) return true; } finally { element.dispose(); }
        }
      } catch (Exception ignored) { }
    }
    return false;
  }

  public void verify(Page page, String platformShop, String account, String instance, double timeout) {
    log.info("pdd_order_login channel=camoufox instanceId={} state=checking_identity", instance);
    if (challenge(page)) throw new PddPlatformException("CAMOUFOX_LOGIN_CHALLENGE_REQUIRED");
    if (loginPage(page)) throw new PddPlatformException("CAMOUFOX_LOGIN_EXPIRED");
    try {
      String encoded = (String) page.evaluate("""
          async ({timeout}) => {
            const c = new AbortController();
            const timer = setTimeout(() => c.abort(), timeout);
            const post = async (path, body) => {
              const r = await fetch(path, {method:'POST', credentials:'include', signal:c.signal,
                headers:body === null ? {} : {'Content-Type':'application/json'}, body});
              return {http:r.status, text:await r.text()};
            };
            try {
              return JSON.stringify({user:await post('/janus/api/new/userinfo', null),
                shop:await post('/earth/api/merchant/queryMerchantInfoByMallId', '{}')});
            } catch (_) { return JSON.stringify({failed:true, timeout:c.signal.aborted}); }
            finally {clearTimeout(timer);}
          }
          """, Map.of("timeout", Math.min(10_000, timeout)));
      var result = json.readTree(encoded);
      if (result.path("timeout").asBoolean()) throw new PddPlatformException("CAMOUFOX_LOGIN_TIMEOUT");
      if (result.path("failed").asBoolean()) throw new PddPlatformException("CAMOUFOX_LOGIN_FAILED");
      com.fasterxml.jackson.databind.JsonNode user = null, merchant = null;
      for (String key : java.util.List.of("user", "shop")) {
        var response = result.path(key);
        int http = response.path("http").asInt();
        if (http < 200 || http >= 300) throw new PddPlatformException("CAMOUFOX_LOGIN_HTTP_" + http);
        var body = json.readTree(response.path("text").asText());
        if (PddOrderResponseCodes.rejected(body)) throw new PddPlatformException(PddOrderResponseCodes.REJECTED);
        if (challengeResponse(body)) throw new PddPlatformException("CAMOUFOX_LOGIN_CHALLENGE_REQUIRED");
        if ("user".equals(key)) user = body; else merchant = body;
      }
      if (!user.path("success").asBoolean() || !merchant.path("success").asBoolean())
        throw new PddPlatformException("CAMOUFOX_LOGIN_FAILED");
      if (!account.equals(user.path("result").path("id").asText())
          || !platformShop.equals(merchant.path("result").path("mallId").asText()))
        throw new PddPlatformException("CAMOUFOX_SHOP_IDENTITY_MISMATCH");
      log.info("pdd_order_login channel=camoufox instanceId={} state=authenticated", instance);
    } catch (PddPlatformException error) { throw error; }
    catch (Exception error) { throw new PddPlatformException("CAMOUFOX_LOGIN_FAILED"); }
  }

  static boolean challengeResponse(com.fasterxml.jackson.databind.JsonNode body) {
    if (body == null) return false;
    // 54001 alone is also observed as a rate limit. Only explicit challenge
    // evidence is classified as captcha; don't guess from a numeric code alone.
    String message = body.path("errorMsg").asText(body.path("error_msg").asText(""));
    return !body.path("result").path("verifyAuthToken").asText("").isBlank()
        || message.contains("安全验证") || message.toLowerCase(java.util.Locale.ROOT).contains("captcha");
  }
}

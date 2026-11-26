package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Gateway transport only: an HTTP failure is never classified as platform code 40002. */
@Component
public class CamoufoxGatewayClient {
  private static final Logger log = LoggerFactory.getLogger(CamoufoxGatewayClient.class);
  public record Instance(String instanceId, String wsEndpoint, int ttlSeconds) {
    @Override public String toString() {
      return "Instance[instanceId=" + instanceId + ", wsEndpoint=***, ttlSeconds=" + ttlSeconds + "]";
    }
  }
  private final ObjectMapper json;
  private final String baseUrl;
  private final String proxy;
  private final String token;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NEVER).build();

  public CamoufoxGatewayClient(ObjectMapper json,
      @Value("${copilot.pdd-order.camoufox-gateway-url:}") String baseUrl,
      @Value("${copilot.pdd-order.camoufox-proxy:}") String proxy,
      @Value("${copilot.pdd-order.camoufox-gateway-token:}") String token) {
    this.json = json;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.proxy = proxy;
    this.token = token;
  }

  public Instance fetch() {
    return fetch("order-fallback-" + java.util.UUID.randomUUID());
  }

  public boolean configured() { return !baseUrl.isBlank(); }

  public Instance fetch(String loginSessionHint) {
    var payload = json.createObjectNode();
    if (!proxy.isBlank()) payload.put("proxy", proxy);
    payload.put("loginSessionHint", loginSessionHint);
    String instanceId = null;
    try {
      var body = json.readTree(post("/fetch", payload.toString()).body());
      instanceId = body.path("instanceId").asText("");
      if (!instanceId.matches("[A-Za-z0-9_-]{1,128}")) {
        instanceId = null;
        throw new PddPlatformException("CAMOUFOX_GATEWAY_INSTANCE_INVALID");
      }
      if (!"playwright-firefox".equals(body.path("protocol").asText()))
        throw new PddPlatformException("CAMOUFOX_GATEWAY_PROTOCOL_INVALID");
      var ttl = body.path("ttlSeconds");
      if (!ttl.isIntegralNumber() || !ttl.canConvertToInt() || ttl.asInt() < 30 || ttl.asInt() > 3600)
        throw new PddPlatformException("CAMOUFOX_GATEWAY_TTL_INVALID");
      String endpoint = body.path("wsEndpoint").asText("");
      URI ws = URI.create(endpoint);
      if (!("ws".equals(ws.getScheme()) || "wss".equals(ws.getScheme()))
          || ws.getHost() == null || ws.getFragment() != null)
        throw new PddPlatformException("CAMOUFOX_GATEWAY_WS_INVALID");
      log.info("pdd_order_instance channel=camoufox instanceId={} ws={}", instanceId, maskedWs(ws));
      return new Instance(instanceId, endpoint, ttl.asInt());
    } catch (Exception error) {
      // Even a response with the wrong protocol can have allocated a real process.
      if (instanceId != null) {
        try { release(instanceId); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
      }
      if (error instanceof PddPlatformException platform) throw platform;
      throw new PddPlatformException("CAMOUFOX_GATEWAY_RESPONSE_INVALID");
    }
  }

  public void release(String instanceId) {
    // Cleanup must run even when the calling task has been interrupted.
    boolean interrupted = Thread.interrupted();
    try {
      var response = post("/release/" + instanceId, "{}");
      try {
        if (!json.readTree(response.body()).path("ok").asBoolean(false))
          throw new PddPlatformException("CAMOUFOX_RELEASE_NOT_CONFIRMED");
      } catch (PddPlatformException error) { throw error; }
      catch (Exception error) { throw new PddPlatformException("CAMOUFOX_RELEASE_NOT_CONFIRMED"); }
      log.info("pdd_order_instance_released channel=camoufox instanceId={}", instanceId);
    } catch (RuntimeException error) {
      log.error("pdd_order_instance_release_failed channel=camoufox instanceId={} code={}",
          instanceId, error instanceof PddPlatformException p ? p.code() : "UNAVAILABLE");
      throw error;
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private HttpResponse<String> post(String path, String body) {
    try {
      if (baseUrl.isBlank()) throw new PddPlatformException("CAMOUFOX_GATEWAY_NOT_CONFIGURED");
      URI base = URI.create(baseUrl);
      if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
          || base.getHost() == null || base.getUserInfo() != null
          || base.getQuery() != null || base.getFragment() != null)
        throw new PddPlatformException("CAMOUFOX_GATEWAY_CONFIG_INVALID");
      var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
          .timeout(Duration.ofSeconds(path.equals("/fetch") ? 60 : 15))
          .header("Content-Type", "application/json");
      if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
      var response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        log.warn("pdd_order_gateway_http channel=camoufox operation={} http={}",
            path.startsWith("/release/") ? "release" : "fetch", response.statusCode());
        throw new PddPlatformException("CAMOUFOX_GATEWAY_HTTP_" + response.statusCode());
      }
      return response;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new PddPlatformException("CAMOUFOX_GATEWAY_INTERRUPTED");
    } catch (PddPlatformException error) {
      throw error;
    } catch (java.net.http.HttpTimeoutException error) {
      throw new PddPlatformException("CAMOUFOX_GATEWAY_TIMEOUT");
    } catch (Exception error) {
      throw new PddPlatformException("CAMOUFOX_GATEWAY_UNAVAILABLE");
    }
  }

  private static String maskedWs(URI uri) {
    return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + "/***";
  }
}

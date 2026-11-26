package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** Proves an account's HTTP and WebSocket identity before the connector is registered. */
@Service
public class PddCredentialVerifier {
  private static final String WS_BASE = "wss://m-ws.pinduoduo.com/";
  private static final String WS_VERSION = "202506091557";
  private final ObjectMapper json;
  private final PddCredentialStore credentials;
  private final HttpClient websocketClient;

  public PddCredentialVerifier(ObjectMapper json, PddCredentialStore credentials) {
    this.json = json;
    this.credentials = credentials;
    this.websocketClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15)).build();
  }

  public void verify(Path directory, String platformShopId, String accountId) {
    Map<String, String> cookies = credentials.load(directory, platformShopId, accountId);
    PddWebClient client = new PddWebClient(json, cookies);
    requireIdentity(client, platformShopId, accountId);
    String token = client.getToken().asText();
    String query = "access_token=" + encode(token)
        + "&role=mall_cs&client=web&version=" + WS_VERSION;
    AuthListener listener = new AuthListener(platformShopId);
    WebSocket socket = null;
    try {
      socket = websocketClient.newWebSocketBuilder()
          .connectTimeout(Duration.ofSeconds(15))
          .buildAsync(URI.create(WS_BASE + "?" + query), listener)
          .get(20, TimeUnit.SECONDS);
      String successValue = listener.authenticated.get(20, TimeUnit.SECONDS);
      socket.sendPing(ByteBuffer.wrap(new byte[] {1})).get(5, TimeUnit.SECONDS);
      writeProof(directory, platformShopId, accountId, successValue);
    } catch (PddPlatformException error) {
      throw error;
    } catch (Exception error) {
      throw new PddPlatformException("PDD_VERIFY_FAILED");
    } finally {
      if (socket != null && !socket.isOutputClosed()) {
        try { socket.sendClose(WebSocket.NORMAL_CLOSURE, "verified").get(2, TimeUnit.SECONDS); }
        catch (Exception ignored) { socket.abort(); }
      }
    }
  }

  private void writeProof(Path directory, String shopId, String accountId, String authResult) {
    try {
      String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(Files.readAllBytes(directory.resolve("credentials.enc"))));
      Map<String, Object> proof = new LinkedHashMap<>();
      proof.put("platform_shop_id", shopId);
      proof.put("account_id", accountId);
      proof.put("credential_digest", digest);
      proof.put("auth_success_result", authResult);
      proof.put("protocol", "m-ws-json");
      proof.put("verified_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
      proof.put("send_enabled", false);
      Path temporary = directory.resolve("verification.json.tmp");
      Files.writeString(temporary, json.writeValueAsString(proof));
      privateFile(temporary);
      try {
        Files.move(temporary, directory.resolve("verification.json"),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, directory.resolve("verification.json"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (Exception error) {
      throw new PddPlatformException("PDD_VERIFY_FAILED");
    }
  }

  private void requireIdentity(PddWebClient client, String shopId, String accountId) {
    PddWebClient.Identity identity = client.identity();
    if (!shopId.equals(identity.platformShopId()) || !accountId.equals(identity.accountId()))
      throw new PddPlatformException("LOGIN_ACCOUNT_MISMATCH");
  }

  private static void privateFile(Path file) {
    try { Files.setPosixFilePermissions(file,
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
    catch (UnsupportedOperationException | java.io.IOException ignored) { }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private final class AuthListener implements WebSocket.Listener {
    private final String platformShopId;
    private final StringBuilder text = new StringBuilder();
    private final CompletableFuture<String> authenticated = new CompletableFuture<>();

    private AuthListener(String platformShopId) { this.platformShopId = platformShopId; }

    @Override
    public void onOpen(WebSocket socket) { socket.request(1); }

    @Override
    public CompletableFuture<?> onText(WebSocket socket, CharSequence data, boolean last) {
      text.append(data);
      if (last) {
        try {
          JsonNode frame = json.readTree(text.toString());
          if ("auth".equals(frame.path("response").asText())) {
            String result = frame.path("auth").path("result").asText("");
            if (!"ok".equals(result)) throw new PddPlatformException("PLATFORM_AUTH_REJECTED");
            JsonNode uid = frame.get("uid");
            if (uid != null && !uid.isNull() && !platformShopId.equals(uid.asText()))
              throw new PddPlatformException("AUTH_IDENTITY_MISMATCH");
            authenticated.complete(result);
          }
        } catch (Exception error) {
          authenticated.completeExceptionally(error instanceof PddPlatformException
              ? error : new PddPlatformException("WS_PROTOCOL_UNSUPPORTED"));
          socket.abort();
        } finally {
          text.setLength(0);
        }
      }
      socket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> onClose(WebSocket socket, int statusCode, String reason) {
      authenticated.completeExceptionally(new PddPlatformException("WEBSOCKET_ENDED"));
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
      authenticated.completeExceptionally(new PddPlatformException("WS_CONNECTION_FAILED"));
    }
  }
}

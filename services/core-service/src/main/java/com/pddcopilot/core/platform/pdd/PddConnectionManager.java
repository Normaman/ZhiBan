package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.repository.ConnectionRepository;
import com.pddcopilot.core.service.ConnectionService;
import com.pddcopilot.core.service.ConnectorRegistryService;
import com.pddcopilot.core.support.ShopScope;
import jakarta.annotation.PreDestroy;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Java-side owner for the existing merchant WebSocket protocol. */
@Service
public class PddConnectionManager {
  private static final Logger log = LoggerFactory.getLogger(PddConnectionManager.class);
  private static final String WS_BASE = "wss://m-ws.pinduoduo.com/";
  private static final String WS_VERSION = "202506091557";
  private static final int MAX_ATTEMPTS = 5;
  private final ConnectorRegistryService registry;
  private final ConnectionRepository shops;
  private final PddCredentialStore credentials;
  private final PddMessageNormalizer normalizer;
  private final PddEventSpool spool;
  private final ConnectionService connections;
  private final ObjectMapper json;
  private final HttpClient websocketClient;
  private final ExecutorService workers =
      Executors.newCachedThreadPool(
          r -> {
            Thread thread = new Thread(r, "pdd-ws-owner");
            thread.setDaemon(true);
            return thread;
          });
  private final Map<String, Worker> active = new ConcurrentHashMap<>();

  public PddConnectionManager(
      ConnectorRegistryService registry,
      ConnectionRepository shops,
      PddCredentialStore credentials,
      PddMessageNormalizer normalizer,
      PddEventSpool spool,
      ConnectionService connections,
      ObjectMapper json) {
    this.registry = registry;
    this.shops = shops;
    this.credentials = credentials;
    this.normalizer = normalizer;
    this.spool = spool;
    this.connections = connections;
    this.json = json;
    this.websocketClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  }

  @Scheduled(fixedDelayString = "${copilot.pdd-reconcile-ms:5000}")
  public void reconcile() {
    List<Map<String, Object>> bound = shops.findBoundLiveShops();
    Set<String> present =
        bound.stream()
            .map(row -> row.get("id").toString())
            .collect(java.util.stream.Collectors.toSet());
    active.forEach(
        (shop, worker) -> {
          if (!present.contains(shop)) {
            worker.stop();
            active.remove(shop, worker);
          }
        });
    for (Map<String, Object> shop : bound) {
      String id = shop.get("id").toString();
      Path directory = registry.directory(id);
      if (directory == null || !Files.isDirectory(directory)) continue;
      active.computeIfAbsent(
          id,
          ignored -> {
            Worker worker =
                new Worker(
                    id,
                    shop.get("platform_shop_id").toString(),
                    shop.get("connector_account_id").toString(),
                    directory);
            workers.submit(worker);
            return worker;
          });
    }
  }

  public void stopForShopRemoval(String shopId) {
    Worker worker = active.remove(shopId);
    if (worker != null) worker.stop();
  }

  @PreDestroy
  public void close() {
    active.values().forEach(Worker::stop);
    active.clear();
    workers.shutdownNow();
    try {
      workers.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  private final class Worker implements Runnable {
    private final String shopId;
    private final String platformShopId;
    private final String accountId;
    private final Path directory;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private final String epoch = UUID.randomUUID().toString();
    private volatile WebSocket socket;
    private volatile String state = "disconnected";
    private volatile String credentialFingerprint;

    private Worker(String shopId, String platformShopId, String accountId, Path directory) {
      this.shopId = shopId;
      this.platformShopId = platformShopId;
      this.accountId = accountId;
      this.directory = directory;
    }

    @Override
    public void run() {
      int attempts = 0;
      try {
        while (!stopped.get()) {
          String before = fingerprint();
          transition(attempts == 0 ? "connecting" : "reconnecting", null);
          try {
            connectOnce();
            if (!stopped.get()) throw new PddPlatformException("WEBSOCKET_ENDED");
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            break;
          } catch (Exception error) {
            if (stopped.get()) break;
            if ("online".equals(state)) attempts = 0;
            String code =
                error instanceof PddPlatformException platform
                    ? platform.code()
                    : "WS_CONNECTION_FAILED";
            log.warn("pdd_connection_failed shop_id={} code={}", shopId, code);
            attempts++;
            transition(attempts >= MAX_ATTEMPTS ? "failed" : "reconnecting", code);
            if (attempts >= MAX_ATTEMPTS) {
              waitForCredentialChange(before);
              attempts = 0;
            } else {
              sleep(Math.min(1L << attempts, 30L) * 1000L);
            }
          } finally {
            closeSocket();
          }
        }
      } finally {
        transition("stopped", null);
        active.remove(shopId, this);
      }
    }

    private void connectOnce() throws Exception {
      Map<String, String> cookies = credentials.load(directory, platformShopId, accountId);
      PddWebClient client = new PddWebClient(json, cookies);
      PddWebClient.Identity identity = client.identity();
      if (!platformShopId.equals(identity.platformShopId())
          || !accountId.equals(identity.accountId()))
        throw new PddPlatformException("AUTH_IDENTITY_MISMATCH");
      String successValue =
          json.readTree(Files.readAllBytes(directory.resolve("verification.json")))
              .path("auth_success_result")
              .asText();
      if (successValue.isBlank()) throw new PddPlatformException("AUTH_RESULT_UNVERIFIED");
      String token = client.getToken().asText();
      String query =
          "access_token=" + encode(token) + "&role=mall_cs&client=web&version=" + WS_VERSION;
      WsListener listener = new WsListener(successValue);
      WebSocket ws =
          websocketClient
              .newWebSocketBuilder()
              .connectTimeout(Duration.ofSeconds(15))
              .buildAsync(URI.create(WS_BASE + "?" + query), listener)
              .get(20, TimeUnit.SECONDS);
      socket = ws;
      listener.authenticated.get(20, TimeUnit.SECONDS);
      credentialFingerprint = fingerprint();
      publishProfile(client);
      long profileSyncAt = System.nanoTime();
      transition("online", null);
      long heartbeatAt = System.nanoTime();
      long stateHeartbeatAt = heartbeatAt;
      long sessionCheckAt = heartbeatAt;
      while (!stopped.get() && !listener.closed.isDone()) {
        JsonNode raw = listener.frames.poll(1, TimeUnit.SECONDS);
        if (raw != null) dispatch(raw);
        if (listener.failure != null) throw listener.failure;
        if (listener.closed.isDone()) break;
        long now = System.nanoTime();
        if (now - sessionCheckAt >= TimeUnit.SECONDS.toNanos(60)) {
          if (!java.util.Objects.equals(credentialFingerprint, fingerprint()))
            throw new PddPlatformException("CREDENTIALS_CHANGED");
          PddWebClient currentClient =
              new PddWebClient(json, credentials.load(directory, platformShopId, accountId));
          PddWebClient.Identity currentIdentity = currentClient.identity();
          if (!platformShopId.equals(currentIdentity.platformShopId())
              || !accountId.equals(currentIdentity.accountId()))
            throw new PddPlatformException("AUTH_IDENTITY_MISMATCH");
          sessionCheckAt = now;
        }
        if (now - profileSyncAt >= TimeUnit.MINUTES.toNanos(30)) {
          publishProfile(
              new PddWebClient(json, credentials.load(directory, platformShopId, accountId)));
          profileSyncAt = now;
        }
        if (now - stateHeartbeatAt >= TimeUnit.SECONDS.toNanos(10)) {
          transition("online", null);
          stateHeartbeatAt = now;
        }
        if (System.nanoTime() - heartbeatAt >= TimeUnit.SECONDS.toNanos(30)) {
          ws.sendPing(ByteBuffer.wrap(new byte[] {1})).get(5, TimeUnit.SECONDS);
          heartbeatAt = System.nanoTime();
        }
      }
      if (!stopped.get()) {
        if (listener.failure != null) throw listener.failure;
        throw new PddPlatformException("WEBSOCKET_ENDED");
      }
    }

    private void publishProfile(PddWebClient client) {
      try (var scope = ShopScope.open(shopId)) {
        connections.updateProfile(shopId, client.shopProfile());
      } catch (Exception error) {
        log.warn("pdd_shop_profile_sync_failed shop_id={}", shopId);
      }
    }

    private void dispatch(JsonNode raw) {
      try {
        PddMessageNormalizer.Event event =
            normalizer.normalize(raw, shopId, platformShopId, accountId);
        if (event.sessionChanged()) throw new PddPlatformException("PLATFORM_SESSION_CHANGED");
        if (event.receipt() != null) {
          Contracts.ReadReceipt receipt = event.receipt();
          String key =
              "receipt:"
                  + digest(
                      receipt.buyerRef()
                          + ":"
                          + receipt.userLastRead()
                          + ":"
                          + receipt.minSupportedMessageId());
          spool.enqueue(shopId, key, receipt);
        } else if (event.message() != null) {
          Contracts.Inbound message = event.message();
          String key =
              message.platformMessageId() == null
                  ? "event:" + message.eventId()
                  : "pdd:" + message.platformMessageId();
          spool.enqueue(shopId, key, message);
        }
      } catch (PddPlatformException error) {
        throw error;
      } catch (IllegalStateException error) {
        if ("PDD_EVENT_PERSIST_FAILED".equals(error.getMessage()))
          throw new PddPlatformException("PDD_EVENT_PERSIST_FAILED");
        log.warn("pdd_event_parse_failed shop_id={}", shopId);
      } catch (Exception error) {
        log.warn("pdd_event_parse_failed shop_id={}", shopId);
      }
    }

    private void transition(String next, String errorCode) {
      state = next;
      try (var scope = ShopScope.open(shopId)) {
        connections.update(
            shopId, new Contracts.Connection(epoch, sequence.incrementAndGet(), next, errorCode));
      } catch (Exception error) {
        log.warn("pdd_connection_state_update_failed shop_id={}", shopId);
      }
    }

    private void waitForCredentialChange(String previous) {
      while (!stopped.get()) {
        sleep(2000);
        if (!java.util.Objects.equals(previous, fingerprint())) return;
      }
    }

    private String fingerprint() {
      try {
        return digest(Files.readAllBytes(directory.resolve("credentials.enc")));
      } catch (Exception ignored) {
        return "missing";
      }
    }

    private void closeSocket() {
      WebSocket current = socket;
      socket = null;
      if (current != null && !current.isOutputClosed()) {
        try {
          current.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect").get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
          current.abort();
        }
      }
    }

    private void stop() {
      if (stopped.compareAndSet(false, true)) closeSocket();
    }

    private void sleep(long millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        stopped.set(true);
      }
    }

    private final class WsListener implements WebSocket.Listener {
      private final String expectedAuth;
      private final StringBuilder text = new StringBuilder();
      private final ArrayBlockingQueue<JsonNode> frames = new ArrayBlockingQueue<>(1000);
      private final CompletableFuture<Void> authenticated = new CompletableFuture<>();
      private final CompletableFuture<Void> closed = new CompletableFuture<>();
      private volatile PddPlatformException failure;

      private WsListener(String expectedAuth) {
        this.expectedAuth = expectedAuth;
      }

      @Override
      public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
      }

      @Override
      public java.util.concurrent.CompletionStage<?> onText(
          WebSocket webSocket, CharSequence data, boolean last) {
        text.append(data);
        if (last) {
          try {
            JsonNode frame = json.readTree(text.toString());
            if ("auth".equals(frame.path("response").asText())) {
              if (!expectedAuth.equals(frame.path("auth").path("result").asText()))
                throw new PddPlatformException("PLATFORM_AUTH_REJECTED");
              JsonNode uid = frame.get("uid");
              if (uid != null && !uid.isNull() && !platformShopId.equals(uid.asText()))
                throw new PddPlatformException("AUTH_IDENTITY_MISMATCH");
              authenticated.complete(null);
            } else if (!frames.offer(frame)) {
              throw new PddPlatformException("WEBSOCKET_EVENT_BACKLOG_FULL");
            }
          } catch (Exception error) {
            failure =
                error instanceof PddPlatformException platform
                    ? platform
                    : new PddPlatformException("WS_PROTOCOL_UNSUPPORTED");
            authenticated.completeExceptionally(failure);
            webSocket.abort();
            closed.complete(null);
          } finally {
            text.setLength(0);
          }
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public java.util.concurrent.CompletionStage<?> onPing(
          WebSocket webSocket, ByteBuffer message) {
        webSocket.sendPong(message);
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public java.util.concurrent.CompletionStage<?> onClose(
          WebSocket webSocket, int statusCode, String reason) {
        closed.complete(null);
        authenticated.completeExceptionally(new PddPlatformException("WEBSOCKET_ENDED"));
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public void onError(WebSocket webSocket, Throwable error) {
        failure = new PddPlatformException("WS_CONNECTION_FAILED");
        closed.complete(null);
        authenticated.completeExceptionally(failure);
      }
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String digest(String value) {
    return digest(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String digest(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception error) {
      throw new IllegalStateException("SHA256_UNAVAILABLE");
    }
  }
}

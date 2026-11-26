package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.api.dto.ModelConfigurationInput;
import com.pddcopilot.core.client.AgentClient;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.ModelConfigurationRepository;
import com.pddcopilot.core.support.ModelSecretCipher;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelConfigurationService {
  private final ModelConfigurationRepository repository;
  private final BusinessStore store;
  private final ModelSecretCipher secrets;
  private final AgentClient agent;
  private final TransactionTemplate transaction;
  private final boolean allowLocal;
  private final String environmentModel;
  private final Set<String> allowedHosts;

  public ModelConfigurationService(
      ModelConfigurationRepository repository,
      BusinessStore store,
      ModelSecretCipher secrets,
      AgentClient agent,
      TransactionTemplate transaction,
      @Value("${copilot.allow-local-model-endpoints:false}") boolean allowLocal,
      @Value("${LLM_MODEL:}") String environmentModel,
      @Value("${copilot.model-allowed-hosts}") String allowedHosts) {
    this.repository = repository;
    this.store = store;
    this.secrets = secrets;
    this.agent = agent;
    this.transaction = transaction;
    this.allowLocal = allowLocal;
    this.environmentModel = environmentModel;
    this.allowedHosts =
        Arrays.stream(allowedHosts.split(","))
            .map(String::trim)
            .map(s -> s.toLowerCase(Locale.ROOT))
            .filter(s -> !s.isBlank())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public Map<String, Object> overview() {
    var settings = repository.settings();
    var out = new LinkedHashMap<String, Object>();
    out.put("revision", revision(settings));
    out.put("storage_ready", secrets.ready());
    out.put("status", status());
    out.put(
        "latest",
        settings == null || settings.get("latest_id") == null
            ? null
            : publicVersion(repository.version(settings.get("latest_id").toString())));
    out.put("active_id", settings == null ? null : settings.get("active_id"));
    return out;
  }

  public Map<String, Object> status() {
    var settings = repository.settings();
    if (settings == null || Boolean.TRUE.equals(settings.get("environment_fallback")))
      return Map.of(
          "enabled",
          store.isDefaultShop() && !environmentModel.isBlank(),
          "source",
          "environment",
          "model",
          store.isDefaultShop() ? environmentModel : "");
    if (settings.get("active_id") == null)
      return Map.of("enabled", false, "source", "admin", "model", "");
    var active = repository.version(settings.get("active_id").toString());
    return Map.of(
        "enabled",
        true,
        "source",
        "admin",
        "model",
        active.get("model_name"),
        "configuration_id",
        active.get("id"));
  }

  @Transactional
  public Map<String, Object> save(ModelConfigurationInput input, String actor) {
    repository.lockShop();
    var settings = checkRevision(input.revision());
    String url = validateUrl(input.baseUrl());
    String key = input.apiKey();
    if (key == null || key.isBlank()) {
      if (settings == null || settings.get("latest_id") == null) throw bad("MODEL_KEY_REQUIRED");
      var previous = repository.version(settings.get("latest_id").toString());
      if (!sameOrigin(url, previous.get("base_url").toString())) throw bad("MODEL_KEY_REQUIRED");
      key = decrypt(previous);
    }
    if (key.length() < 8 || key.length() > 4096 || key.chars().anyMatch(Character::isWhitespace))
      throw bad("MODEL_KEY_INVALID");
    String model = input.model().trim();
    if (model.isEmpty()) throw bad("INVALID_REQUEST");
    long next = input.revision() + 1;
    String id = newId();
    repository.insert(
        id,
        next,
        url,
        model,
        secrets.encrypt(key, scope(id)),
        "••••" + key.substring(key.length() - 4),
        input.maxRounds(),
        input.timeoutSeconds(),
        nowMillis());
    repository.saveSettings(
        next,
        id,
        settings == null ? null : settings.get("active_id"),
        store.isDefaultShop()
            && (settings == null || Boolean.TRUE.equals(settings.get("environment_fallback"))));
    store.recordAudit("model.saved", id, actor, id);
    return overview();
  }

  public Map<String, Object> test(String id, long expectedRevision, String actor) {
    var settings = checkRevision(expectedRevision);
    requireLatest(settings, id);
    var config = repository.version(id);
    Map<String, Object> result;
    try {
      result = agent.testModel(secretVersion(config));
    } catch (Exception error) {
      result = Map.of("passed", false, "error_code", "MODEL_TEST_UNAVAILABLE");
    }
    boolean passed = Boolean.TRUE.equals(result.get("passed"));
    Set<String> allowed =
        Set.of(
            "MODEL_TEST_UNAVAILABLE",
            "MODEL_TOOL_TEST_FAILED",
            "MODEL_REQUEST_FAILED",
            "INVALID_MODEL_CITATIONS",
            "DRAFT_NOT_SUPPORTED",
            "AGENT_STEP_LIMIT");
    String error =
        passed ? null : Objects.toString(result.get("error_code"), "MODEL_TOOL_TEST_FAILED");
    String safeError =
        error == null ? null : allowed.contains(error) ? error : "MODEL_TOOL_TEST_FAILED";
    return transaction.execute(
        tx -> {
          repository.lockShop();
          requireLatest(checkRevision(expectedRevision), id);
          repository.tested(id, passed, safeError, nowMillis());
          store.recordAudit(passed ? "model.test_passed" : "model.test_failed", id, actor, id);
          return overview();
        });
  }

  @Transactional
  public Map<String, Object> activate(String id, long expectedRevision, String actor) {
    repository.lockShop();
    var settings = checkRevision(expectedRevision);
    requireLatest(settings, id);
    if (!"passed".equals(repository.version(id).get("test_state")))
      throw conflict("MODEL_TEST_REQUIRED");
    repository.saveSettings(expectedRevision + 1, id, id, false);
    store.recordAudit("model.activated", id, actor, id);
    return overview();
  }

  @Transactional
  public Map<String, Object> disable(long expectedRevision, String actor) {
    repository.lockShop();
    var settings = checkRevision(expectedRevision);
    repository.saveSettings(
        expectedRevision + 1, settings == null ? null : settings.get("latest_id"), null, false);
    store.recordAudit("model.disabled", store.shopId(), actor, newId());
    return overview();
  }

  /** Internal service contract only. Never return this from a browser endpoint. */
  public Map<String, Object> effective(String shopId) {
    store.requireShopAccess(shopId);
    var settings = repository.settings();
    if (settings == null || Boolean.TRUE.equals(settings.get("environment_fallback")))
      return store.isDefaultShop()
          ? Map.of("managed", false)
          : Map.of("managed", true, "enabled", false);
    if (settings.get("active_id") == null) return Map.of("managed", true, "enabled", false);
    var out =
        new LinkedHashMap<String, Object>(
            secretVersion(repository.version(settings.get("active_id").toString())));
    out.put("managed", true);
    out.put("enabled", true);
    out.put("configuration_id", settings.get("active_id"));
    return out;
  }

  private Map<String, Object> secretVersion(Map<String, Object> config) {
    validateUrl(config.get("base_url").toString());
    return Map.of(
        "base_url",
        config.get("base_url"),
        "model",
        config.get("model_name"),
        "api_key",
        decrypt(config),
        "max_rounds",
        config.get("max_rounds"),
        "timeout_seconds",
        config.get("timeout_seconds"));
  }

  private String decrypt(Map<String, Object> config) {
    return secrets.decrypt(
        config.get("encrypted_api_key").toString(), scope(config.get("id").toString()));
  }

  private String scope(String id) {
    return store.shopId() + ":" + id;
  }

  private long revision(Map<String, Object> settings) {
    return settings == null ? 0 : asLong(settings.get("revision"));
  }

  private Map<String, Object> checkRevision(long expected) {
    var settings = repository.settings();
    if (revision(settings) != expected) throw conflict("MODEL_CONFIG_CONFLICT");
    return settings;
  }

  private void requireLatest(Map<String, Object> settings, String id) {
    if (settings == null || !id.equals(settings.get("latest_id")))
      throw conflict("MODEL_CONFIG_CONFLICT");
  }

  private Map<String, Object> publicVersion(Map<String, Object> config) {
    var out = new LinkedHashMap<String, Object>();
    for (String key :
        List.of(
            "id",
            "version",
            "base_url",
            "key_hint",
            "max_rounds",
            "timeout_seconds",
            "test_state",
            "test_error_code",
            "tested_at",
            "created_at")) out.put(key, config.get(key));
    out.put("model", config.get("model_name"));
    return out;
  }

  private String validateUrl(String value) {
    try {
      URI uri = URI.create(value.trim());
      String host = uri.getHost();
      boolean loopback =
          Set.of("localhost", "127.0.0.1", "[::1]", "::1")
              .contains(host == null ? "" : host.toLowerCase(Locale.ROOT));
      if (host == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || (!"https".equals(uri.getScheme())
              && !(allowLocal && loopback && "http".equals(uri.getScheme())))
          || (loopback && (!allowLocal || !store.isDefaultShop()))
          || value.endsWith("/chat/completions")) throw new IllegalArgumentException();
      if (!loopback
          && (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))
              || (uri.getPort() != -1 && uri.getPort() != 443)))
        throw bad("MODEL_HOST_NOT_ALLOWED");
      return uri.toString().replaceAll("/+$", "");
    } catch (ResponseStatusException error) {
      throw error;
    } catch (Exception error) {
      throw bad("MODEL_URL_INVALID");
    }
  }

  private boolean sameOrigin(String left, String right) {
    var a = URI.create(left);
    var b = URI.create(right);
    return Objects.equals(a.getScheme(), b.getScheme())
        && a.getHost().equalsIgnoreCase(b.getHost())
        && a.getPort() == b.getPort();
  }

  private ResponseStatusException bad(String code) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
  }
}

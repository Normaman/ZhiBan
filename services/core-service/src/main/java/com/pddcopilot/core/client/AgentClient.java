package com.pddcopilot.core.client;

import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.support.WorkflowValues;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Transport to Python. All calls happen outside the job-claim database transaction. */
@Component
public class AgentClient {
  private final RestClient http;
  private final BusinessStore store;

  public AgentClient(
      @Value("${copilot.agent-url}") String url,
      @Value("${copilot.internal-token}") String token,
      BusinessStore store) {
    this.store = store;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    // Covers context/config reads and the model run within the 120-second job lease.
    factory.setReadTimeout(Duration.ofSeconds(110));
    http =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .defaultHeader("X-Internal-Token", token)
            .build();
  }

  public Map<String, Object> generate(String runId, Map<String, Object> context) {
    return post(
        "/internal/v1/agent-runs",
        Map.of(
            "run_id",
            runId,
            "shop_id",
            context.get("shop_id"),
            "conversation_id",
            context.get("conversation_id"),
            "message_id",
            context.get("message_id"),
            "knowledge_version",
            Objects.toString(context.get("knowledge_version"), "")),
        runId);
  }

  public Map<String, Object> knowledgeStatus() {
    return post("/internal/v1/knowledge/status", Map.of(), WorkflowValues.newId());
  }

  public Map<String,Object> debugKnowledge(Map<String,Object> input) {
    return post("/internal/v1/knowledge/search",input,WorkflowValues.newId());
  }

  public Map<String, Object> previewDocument(
      com.pddcopilot.core.api.dto.Contracts.DocumentPreview input) {
    try {
      return post(
          "/internal/v1/knowledge/preview",
          Map.of("filename", input.filename(), "content_base64", input.contentBase64()),
          WorkflowValues.newId());
    } catch (org.springframework.web.client.RestClientResponseException error) {
      String code = "DOCUMENT_PARSE_FAILED";
      try {
        var body = error.getResponseBodyAs(Map.class);
        if (body != null
            && body.get("detail") instanceof String detail
            && detail.matches("DOCUMENT_[A-Z_]+")) code = detail;
      } catch (Exception ignored) {
      }
      throw new org.springframework.web.server.ResponseStatusException(
          error.getStatusCode().value() == 429
              ? org.springframework.http.HttpStatus.TOO_MANY_REQUESTS
              : org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
          code);
    } catch (org.springframework.web.client.ResourceAccessException error) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_GATEWAY, "DOCUMENT_PARSER_UNAVAILABLE");
    }
  }

  public Map<String, Object> testModel(Map<String, Object> configuration) {
    return post("/internal/v1/model-config/test", configuration, WorkflowValues.newId());
  }

  public Map<String, Object> stopShop(String shopId) {
    return requireResult(
        http.post()
            .uri("/internal/v1/shops/{shopId}/stop", shopId)
            .header("X-Shop-Id", shopId)
            .header("X-Trace-Id", WorkflowValues.newId())
            .body(Map.of())
            .retrieve()
            .body(Map.class));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> post(String path, Object body, String traceId) {
    return requireResult(
        http.post()
            .uri(path)
            .header("X-Shop-Id", store.shopId())
            .header("X-Trace-Id", traceId)
            .body(body)
            .retrieve()
            .body(Map.class));
  }

  private Map<String, Object> requireResult(Map<String, Object> result) {
    if (result == null) throw new IllegalStateException("EMPTY_SERVICE_RESULT");
    return result;
  }
}

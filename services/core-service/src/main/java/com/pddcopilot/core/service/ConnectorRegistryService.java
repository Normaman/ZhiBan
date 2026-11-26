package com.pddcopilot.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Atomically publishes verified, account-scoped credential directories to connector workers. */
@Service
public class ConnectorRegistryService {
  private static final TypeReference<List<Map<String, Object>>> RECORDS = new TypeReference<>() {};
  private final Path registry;
  private final ObjectMapper json;

  public ConnectorRegistryService(
      @Value("${CONNECTOR_REGISTRY_FILE:/data/connectors.json}") String registry,
      ObjectMapper json) {
    this.registry = Path.of(registry).toAbsolutePath().normalize();
    this.json = json;
  }

  public synchronized void register(
      String shopId, Path dataDirectory, String platformShopId, String accountId) {
    Path directory = dataDirectory.toAbsolutePath().normalize();
    try {
      verify(directory, platformShopId, accountId);
      List<Map<String, Object>> records = read();
      Map<String, Object> prior = null;
      for (Map<String, Object> record : records) {
        Path registered = Path.of(record.get("data_dir").toString()).toRealPath();
        if (shopId.equals(record.get("shop_id"))) {
          prior = record;
          if (!directory.equals(registered))
            throw new IllegalStateException("CONNECTOR_DIRECTORY_CONFLICT");
        } else if (directory.equals(registered)) {
          throw new IllegalStateException("CONNECTOR_DIRECTORY_ALREADY_REGISTERED");
        }
      }
      if (prior == null && records.size() >= 32)
        throw new IllegalStateException("CONNECTOR_CAPACITY_REACHED");
      records.removeIf(record -> shopId.equals(record.get("shop_id")));
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("shop_id", shopId);
      item.put("data_dir", directory.toString());
      records.add(item);
      write(records);
    } catch (IOException error) {
      throw new IllegalStateException("CONNECTOR_REGISTRY_WRITE_FAILED", error);
    }
  }

  public synchronized Path remove(String shopId) {
    try {
      List<Map<String, Object>> records = read();
      Map<String, Object> removed = records.stream()
          .filter(record -> shopId.equals(record.get("shop_id"))).findFirst().orElse(null);
      if (records.removeIf(record -> shopId.equals(record.get("shop_id")))) write(records);
      return removed == null ? null : Path.of(removed.get("data_dir").toString());
    } catch (IOException error) {
      throw new IllegalStateException("CONNECTOR_REGISTRY_WRITE_FAILED", error);
    }
  }

  public synchronized Path directory(String shopId) {
    try {
      return read().stream()
          .filter(record -> shopId.equals(record.get("shop_id")))
          .map(record -> Path.of(record.get("data_dir").toString()).toAbsolutePath().normalize())
          .findFirst()
          .orElse(null);
    } catch (IOException error) {
      throw new IllegalStateException("CONNECTOR_REGISTRY_READ_FAILED");
    }
  }

  public synchronized void restore(String shopId, Path directory) {
    if (directory == null || !Files.isDirectory(directory)) return;
    try {
      Map<String, Object> identity = json.readValue(
          Files.readString(directory.resolve("connection.json")), new TypeReference<>() {});
      register(shopId, directory, identity.get("platform_shop_id").toString(),
          identity.get("account_id").toString());
    } catch (IOException | NullPointerException error) {
      throw new IllegalStateException("CONNECTOR_REGISTRY_WRITE_FAILED", error);
    }
  }

  private void verify(Path directory, String platformShopId, String accountId) throws IOException {
    if (!Files.isDirectory(directory)) throw new IllegalStateException("CONNECTOR_DIRECTORY_MISSING");
    Map<String, Object> identity = json.readValue(
        Files.readString(directory.resolve("connection.json")), new TypeReference<>() {});
    Map<String, Object> verification = json.readValue(
        Files.readString(directory.resolve("verification.json")), new TypeReference<>() {});
    byte[] credentials = Files.readAllBytes(directory.resolve("credentials.enc"));
    if (!Files.isRegularFile(directory.resolve("credential.key"))
        || identity.get("platform_shop_id") == null
        || identity.get("account_id") == null
        || !platformShopId.equals(identity.get("platform_shop_id"))
        || !accountId.equals(identity.get("account_id"))
        || !identity.get("platform_shop_id").equals(verification.get("platform_shop_id"))
        || !identity.get("account_id").equals(verification.get("account_id"))
        || !"ok".equals(verification.get("auth_success_result"))
        || !sha256(credentials).equals(verification.get("credential_digest")))
      throw new IllegalStateException("CONNECTOR_VERIFICATION_MISMATCH");
  }

  private String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA256_UNAVAILABLE", error);
    }
  }

  private List<Map<String, Object>> read() throws IOException {
    if (!Files.exists(registry)) return new ArrayList<>();
    List<Map<String, Object>> records = json.readValue(Files.readString(registry), RECORDS);
    if (records.size() > 32 || records.stream().anyMatch(record ->
        !record.keySet().equals(java.util.Set.of("shop_id", "data_dir"))))
      throw new IllegalStateException("CONNECTOR_REGISTRY_INVALID");
    return new ArrayList<>(records);
  }

  private void write(List<Map<String, Object>> records) throws IOException {
    Path parent = registry.getParent();
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, "connectors-", ".tmp");
    try {
      Files.writeString(temporary, json.writeValueAsString(records));
      try {
        Files.move(temporary, registry, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException error) {
        Files.move(temporary, registry, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}

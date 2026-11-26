package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Reads the existing Python Fernet bundle without writing or exposing its contents. */
@Component
public class PddCredentialStore {
  private static final byte FERNET_VERSION = (byte) 0x80;
  private final ObjectMapper json;

  public PddCredentialStore(ObjectMapper json) {
    this.json = json;
  }

  public Map<String, String> load(Path directory, String platformShopId, String accountId) {
    try {
      byte[] plaintext = decrypt(directory, directory.resolve("credentials.enc"));
      Map<String, Object> record = json.readValue(plaintext, new TypeReference<>() {});
      if (!platformShopId.equals(String.valueOf(record.get("shop_id")))
          || !accountId.equals(String.valueOf(record.get("user_id"))))
        throw new IllegalArgumentException();
      Object rawCookies = record.get("cookies");
      Map<String, String> cookies = new LinkedHashMap<>();
      if (rawCookies instanceof String encoded) {
        rawCookies = json.readValue(encoded, new TypeReference<Map<String, Object>>() {});
      }
      if (!(rawCookies instanceof Map<?, ?> values) || values.isEmpty())
        throw new IllegalArgumentException();
      values.forEach((name, value) -> {
        if (name instanceof String keyName && value instanceof String cookieValue
            && keyName.matches("[A-Za-z0-9_!#$%&'*+.^`|~-]{1,128}")
            && !cookieValue.contains("\r") && !cookieValue.contains("\n"))
          cookies.put(keyName, cookieValue);
      });
      if (cookies.isEmpty()) throw new IllegalArgumentException();
      java.util.Arrays.fill(plaintext, (byte) 0);
      return Map.copyOf(cookies);
    } catch (Exception error) {
      throw new IllegalStateException("PDD_CREDENTIALS_UNAVAILABLE");
    }
  }

  /** Atomically writes a Fernet token compatible with the existing Python credential files. */
  public void saveEncryptedRecord(Path directory, String filename, Map<String, Object> record) {
    if (!filename.matches("[a-z-]+\\.enc")) throw new IllegalArgumentException("invalid filename");
    byte[] plaintext = null;
    byte[] key = null;
    try {
      Files.createDirectories(directory);
      setPrivate(directory);
      Path keyPath = directory.resolve("credential.key");
      key = Base64.getUrlDecoder().decode(Files.readString(keyPath).trim());
      plaintext = json.writeValueAsBytes(record);
      byte[] token = encrypt(key, plaintext);
      Path target = directory.resolve(filename);
      Path temporary = directory.resolve(filename + ".tmp");
      Files.write(temporary, Base64.getUrlEncoder().encode(token));
      try {
        Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
      }
      try {
        Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      java.util.Arrays.fill(token, (byte) 0);
    } catch (Exception error) {
      throw new IllegalStateException("PDD_SESSION_SAVE_FAILED");
    } finally {
      if (plaintext != null) java.util.Arrays.fill(plaintext, (byte) 0);
      if (key != null) java.util.Arrays.fill(key, (byte) 0);
    }
  }

  public void saveLogin(
      Path directory, String shopId, String accountId, String profileName,
      Map<String, String> cookies) {
    try {
      Files.createDirectories(directory);
      Path keyPath = directory.resolve("credential.key");
      if (!Files.exists(keyPath)) {
        if (Files.exists(directory.resolve("credentials.enc")))
          throw new IllegalStateException("CREDENTIAL_KEY_MISSING");
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        byte[] encoded = Base64.getUrlEncoder().encode(raw);
        Files.writeString(keyPath, new String(encoded, java.nio.charset.StandardCharsets.US_ASCII) + "\n",
            java.nio.file.StandardOpenOption.CREATE_NEW);
        try { Files.setPosixFilePermissions(keyPath,
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        java.util.Arrays.fill(raw, (byte) 0);
        java.util.Arrays.fill(encoded, (byte) 0);
      } else if (Files.exists(directory.resolve("credentials.enc"))) {
        load(directory, shopId, accountId);
      }
      saveEncryptedRecord(directory, "credentials.enc", Map.of(
          "shop_id", shopId, "user_id", accountId, "cookies", cookies));
      Map<String, Object> binding = Map.of(
          "platform_shop_id", shopId, "account_id", accountId, "profile_name", profileName);
      Path temporary = directory.resolve("connection.json.tmp");
      Files.writeString(temporary, json.writeValueAsString(binding));
      try { Files.setPosixFilePermissions(temporary,
          java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
      catch (UnsupportedOperationException ignored) { }
      try { Files.move(temporary, directory.resolve("connection.json"),
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
      catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, directory.resolve("connection.json"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (Exception error) {
      if (error instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("PDD_CREDENTIAL_SAVE_FAILED");
    }
  }

  private static byte[] encrypt(byte[] key, byte[] plaintext) throws Exception {
    if (key.length != 32) throw new IllegalArgumentException();
    byte[] iv = new byte[16];
    new SecureRandom().nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE,
        new SecretKeySpec(java.util.Arrays.copyOfRange(key, 16, 32), "AES"),
        new IvParameterSpec(iv));
    byte[] encrypted = cipher.doFinal(plaintext);
    byte[] token = new byte[1 + 8 + 16 + encrypted.length + 32];
    token[0] = FERNET_VERSION;
    long timestamp = Instant.now().getEpochSecond();
    for (int i = 0; i < 8; i++) token[8 - i] = (byte) (timestamp >>> (i * 8));
    System.arraycopy(iv, 0, token, 9, iv.length);
    System.arraycopy(encrypted, 0, token, 25, encrypted.length);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(java.util.Arrays.copyOfRange(key, 0, 16), "HmacSHA256"));
    byte[] tag = mac.doFinal(java.util.Arrays.copyOf(token, token.length - 32));
    System.arraycopy(tag, 0, token, token.length - 32, 32);
    java.util.Arrays.fill(encrypted, (byte) 0);
    java.util.Arrays.fill(tag, (byte) 0);
    return token;
  }

  private static void setPrivate(Path path) throws Exception {
    try {
      Files.setPosixFilePermissions(path,
          java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
    }
  }

  public Map<String, String> loadProductHeaders(
      Path directory, String platformShopId, String accountId) {
    try {
      Path sessionFile = directory.resolve("product-session.enc");
      if (!Files.exists(sessionFile)) return Map.of();
      byte[] credentialBytes = Files.readAllBytes(directory.resolve("credentials.enc"));
      String credentialDigest = HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(credentialBytes));
      byte[] plaintext = decrypt(directory, sessionFile);
      Map<String, Object> record = json.readValue(plaintext, new TypeReference<>() {});
      Object rawHeaders = record.get("headers");
      if (!(rawHeaders instanceof Map<?, ?> headers)
          || !"product-query".equals(record.get("purpose"))
          || !platformShopId.equals(record.get("shop_id"))
          || !accountId.equals(record.get("account_id"))
          || !credentialDigest.equals(record.get("credential_digest"))
          || !(headers.get("anti-content") instanceof String value)
          || value.isBlank() || value.length() > 32000)
        throw new IllegalArgumentException();
      java.util.Arrays.fill(plaintext, (byte) 0);
      return Map.of("anti-content", value);
    } catch (Exception error) {
      throw new IllegalStateException("PRODUCT_SESSION_INVALID");
    }
  }

  public Map<String, Object> loadOrderSession(
      Path directory, String platformShopId, String accountId) {
    try {
      Path sessionFile = directory.resolve("order-session.enc");
      if (!Files.exists(sessionFile)) throw new IllegalArgumentException();
      byte[] credentialBytes = Files.readAllBytes(directory.resolve("credentials.enc"));
      String credentialDigest = HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(credentialBytes));
      byte[] plaintext = decrypt(directory, sessionFile);
      Map<String, Object> record = json.readValue(plaintext, new TypeReference<>() {});
      Object rawHeaders = record.get("headers");
      Object rawCookies = record.get("cookies");
      if (!(rawHeaders instanceof Map<?, ?> headers)
          || !(rawCookies instanceof Map<?, ?> cookies)
          || !"order-query".equals(record.get("purpose"))
          || !platformShopId.equals(record.get("shop_id"))
          || !accountId.equals(record.get("account_id"))
          || !credentialDigest.equals(record.get("credential_digest")))
        throw new IllegalArgumentException();
      Map<String, String> safeHeaders = new LinkedHashMap<>();
      for (String name : java.util.List.of(
          "accept", "anti-content", "origin", "referer", "user-agent", "content-type",
          "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform")) {
        Object value = headers.get(name);
        if (value instanceof String text && text.length() <= 32000
            && !text.contains("\r") && !text.contains("\n")) safeHeaders.put(name, text);
      }
      Map<String, String> safeCookies = new LinkedHashMap<>();
      cookies.forEach((name, value) -> {
        if (name instanceof String keyName && keyName.matches("[A-Za-z0-9_!#$%&'*+.^`|~-]{1,128}")
            && value instanceof String text && text.length() <= 32000
            && !text.contains("\r") && !text.contains("\n")) safeCookies.put(keyName, text);
      });
      if (safeHeaders.getOrDefault("anti-content", "").isBlank() || safeCookies.isEmpty())
        throw new IllegalArgumentException();
      Object queryPath = record.getOrDefault("query_path", "/mangkhut/mms/orderDetail");
      if (!(queryPath instanceof String path) || !PddBrowserSessionService.ORDER_PATHS.contains(path))
        throw new IllegalArgumentException();
      java.util.Arrays.fill(plaintext, (byte) 0);
      return Map.of("headers", Map.copyOf(safeHeaders), "cookies", Map.copyOf(safeCookies),
          "query_path", queryPath);
    } catch (Exception error) {
      throw new IllegalStateException("ORDER_SESSION_INVALID");
    }
  }

  private byte[] decrypt(Path directory, Path encryptedFile) throws Exception {
    byte[] key = Base64.getUrlDecoder()
        .decode(Files.readString(directory.resolve("credential.key")).trim());
    try {
      if (key.length != 32) throw new IllegalArgumentException();
      byte[] token = Base64.getUrlDecoder().decode(Files.readAllBytes(encryptedFile));
      if (token.length < 73 || token[0] != FERNET_VERSION || (token.length - 57) % 16 != 0)
        throw new IllegalArgumentException();
      byte[] signed = java.util.Arrays.copyOf(token, token.length - 32);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(java.util.Arrays.copyOfRange(key, 0, 16), "HmacSHA256"));
      if (!MessageDigest.isEqual(
          mac.doFinal(signed), java.util.Arrays.copyOfRange(token, token.length - 32, token.length)))
        throw new IllegalArgumentException();
      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(Cipher.DECRYPT_MODE,
          new SecretKeySpec(java.util.Arrays.copyOfRange(key, 16, 32), "AES"),
          new IvParameterSpec(java.util.Arrays.copyOfRange(token, 9, 25)));
      return cipher.doFinal(token, 25, token.length - 57);
    } finally {
      java.util.Arrays.fill(key, (byte) 0);
    }
  }
}

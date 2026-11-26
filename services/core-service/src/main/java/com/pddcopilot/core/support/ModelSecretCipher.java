package com.pddcopilot.core.support;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated encryption with a deployment key stored outside the database. */
@Component
public class ModelSecretCipher {
  private final byte[] key;
  private final SecureRandom random = new SecureRandom();

  public ModelSecretCipher(@Value("${copilot.model-encryption-key:}") String encoded) {
    key = encoded.isBlank() ? null : Base64.getDecoder().decode(encoded);
    if (key != null && key.length != 32)
      throw new IllegalArgumentException("MODEL_ENCRYPTION_KEY must encode 32 bytes");
  }

  public boolean ready() {
    return key != null;
  }

  public String encrypt(String secret, String scope) {
    if (!ready()) throw unavailable();
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      var cipher = cipher(Cipher.ENCRYPT_MODE, nonce, scope);
      byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
      byte[] payload = Arrays.copyOf(nonce, nonce.length + encrypted.length);
      System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
      return "v1:" + Base64.getEncoder().encodeToString(payload);
    } catch (Exception error) {
      throw unavailable();
    }
  }

  public String decrypt(String encrypted, String scope) {
    if (!ready()) throw unavailable();
    try {
      if (!encrypted.startsWith("v1:")) throw new IllegalArgumentException();
      byte[] payload = Base64.getDecoder().decode(encrypted.substring(3));
      var cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(payload, 12), scope);
      return new String(
          cipher.doFinal(Arrays.copyOfRange(payload, 12, payload.length)), StandardCharsets.UTF_8);
    } catch (Exception error) {
      throw unavailable();
    }
  }

  private Cipher cipher(int mode, byte[] nonce, String scope) throws Exception {
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
    cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
    return cipher;
  }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_SECRET_UNAVAILABLE");
  }
}

package com.pddcopilot.core.support;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecurityDigest {
  private final byte[] key;
  public SecurityDigest(@Value("${copilot.internal-token}") String secret) {
    key = secret.getBytes(StandardCharsets.UTF_8);
  }
  public String hash(String value) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key,"HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException(error); }
  }
}

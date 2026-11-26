package com.pddcopilot.core.platform.pdd;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PddOrderSessionStoreTest {
  @TempDir Path directory;
  final PddCredentialStore credentials = new PddCredentialStore(new ObjectMapper());

  Map<String, Object> record() throws Exception {
    credentials.saveLogin(directory, "fixture-mall", "fixture-account", "login-" + "a".repeat(32),
        Map.of("session", "synthetic-cookie"));
    var record = new LinkedHashMap<String, Object>();
    record.put("purpose", "order-query");
    record.put("shop_id", "fixture-mall");
    record.put("account_id", "fixture-account");
    record.put("credential_digest", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(directory.resolve("credentials.enc")))));
    record.put("headers", Map.of("anti-content", "synthetic-header"));
    record.put("cookies", Map.of("session", "synthetic-cookie"));
    return record;
  }

  @Test
  void legacySessionsDefaultToTheObservedEndpointAndCapturedAllowedPathsArePreserved() throws Exception {
    var record = record();
    credentials.saveEncryptedRecord(directory, "order-session.enc", record);
    assertEquals("/mangkhut/mms/orderDetail", credentials.loadOrderSession(directory,
        "fixture-mall", "fixture-account").get("query_path"));
    record.put("query_path", "/mangkhut/mms/routeOrderDetail");
    credentials.saveEncryptedRecord(directory, "order-session.enc", record);
    assertEquals(record.get("query_path"), credentials.loadOrderSession(directory,
        "fixture-mall", "fixture-account").get("query_path"));
  }

  @Test
  void modifiedSessionsCannotTurnReadOnlyQueriesIntoOtherCallsOrCrossAccountAccess() throws Exception {
    var record = record();
    for (String path : new String[]{"https://evil.example/order", "/orders/refund", "//evil.example"}) {
      record.put("query_path", path);
      credentials.saveEncryptedRecord(directory, "order-session.enc", record);
      assertThrows(IllegalStateException.class, () -> credentials.loadOrderSession(directory,
          "fixture-mall", "fixture-account"));
    }
    record.remove("query_path");
    credentials.saveEncryptedRecord(directory, "order-session.enc", record);
    assertThrows(IllegalStateException.class, () -> credentials.loadOrderSession(directory,
        "another-mall", "fixture-account"));
    credentials.saveEncryptedRecord(directory, "credentials.enc", Map.of("changed", true));
    assertThrows(IllegalStateException.class, () -> credentials.loadOrderSession(directory,
        "fixture-mall", "fixture-account"));
  }
}

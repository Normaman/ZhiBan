package com.pddcopilot.core;

import static com.pddcopilot.core.support.WorkflowValues.sha256;
import static org.junit.jupiter.api.Assertions.*;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.api.dto.KnowledgeArchive;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.service.KnowledgeService;
import com.pddcopilot.core.service.KnowledgeTransferService;
import com.pddcopilot.core.support.ShopScope;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "copilot.session-store=memory",
      "spring.datasource.url=jdbc:h2:mem:knowledge-transfer;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "copilot.reviewer-password=test-reviewer-password-123",
      "copilot.internal-token=test-internal-token-with-32-characters",
      "copilot.registration-invite=test-invite-code-32-characters-long",
      "copilot.model-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "copilot.worker-enabled=false",
      "copilot.redis-enabled=false",
      "copilot.mode=demo"
    })
class KnowledgeTransferTest {
  @Autowired BusinessStore store;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired KnowledgeService knowledge;
  @Autowired KnowledgeTransferService transfer;
  @Autowired TestRestTemplate http;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.pddcopilot.core.service.LoginRateLimiter loginLimiter;

  String shop;
  String workspace;
  ShopScope scope;

  @BeforeEach
  void setup() {
    shop = UUID.randomUUID().toString();
    workspace = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO workspaces(id,name,created_at) VALUES (?,?,0)", workspace, "迁移测试空间");
    jdbc.update(
        "INSERT INTO"
            + " shops(id,name,connector_account_id,platform_shop_id,mode,workspace_id,binding_state)"
            + " VALUES (?,?,?,?,'live',?,'bound')",
        shop,
        "目标店铺",
        "test-account-" + shop,
        "platform-" + shop,
        workspace);
    String admin =
        jdbc.queryForObject("SELECT id FROM app_users WHERE username='reviewer'", String.class);
    jdbc.update("INSERT INTO workspace_memberships(workspace_id,user_id,role,created_at) VALUES (?,?,'ADMIN',0)", workspace, admin);
    scope = ShopScope.open(shop);
  }

  @AfterEach
  void closeScope() {
    scope.close();
  }

  Map<String, Object> rule(String id, String text) {
    return new LinkedHashMap<>(
        Map.of(
            "source_id", id, "source_type", "shop_policy", "topics", List.of("faq"), "text", text));
  }

  KnowledgeArchive archive(List<Map<String, Object>> entries) {
    String json = store.toJson(entries);
    return new KnowledgeArchive(
        KnowledgeTransferService.FORMAT,
        1,
        Instant.now().toString(),
        new KnowledgeArchive.Source(
            "source-shop", "开发店铺", "live", "platform-" + shop, "source-version"),
        json,
        sha256(json));
  }

  KnowledgeArchive.Migration input(
      KnowledgeArchive archive, boolean products, String policy, String base) {
    return new KnowledgeArchive.Migration(archive, products, policy, base);
  }

  String publish(List<Map<String, Object>> entries) {
    String base =
        Objects.toString(
            jdbc.queryForObject(
                "SELECT knowledge_version FROM shops WHERE id=?", String.class, shop),
            "");
    return knowledge
        .publish(
            shop, new Contracts.Knowledge("test-" + UUID.randomUUID(), entries, base), "reviewer")
        .get("knowledge_version")
        .toString();
  }

  int versions() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM knowledge_versions WHERE shop_id=?", Integer.class, shop);
  }

  @Test
  void exportPreservesSourceMetadataAndDoesNotIncludeAccountSecrets() {
    var entry = rule("guidelines", "先回答结论，保留条件与例外。");
    entry.put("topics", List.of("response_guidelines"));
    entry.put("title", "回答规范");
    entry.put("document_name", "接待规范.md");
    entry.put("document_sha256", "a".repeat(64));
    entry.put("source_location", "第 2 段");
    entry.put("classification_method", "manual");
    publish(List.of(entry));
    var backup = transfer.exportArchive(shop);
    assertEquals(List.of(entry), store.fromJson(backup.snapshotJson()));
    assertEquals(sha256(backup.snapshotJson()), backup.snapshotSha256());
    assertEquals("platform-" + shop, backup.source().platformShopId());
    assertFalse(store.toJson(backup).contains("test-account-"));
    assertFalse(store.toJson(backup).contains("password"));
  }

  @Test
  void mergeKeepsTargetConflictsSkipsContentDuplicatesAndReplaysWithoutNewVersion() {
    String base = publish(List.of(rule("existing", "目标已有答复"), rule("duplicate", "相同的固定问答")));
    var backup =
        archive(
            List.of(
                rule("existing", "开发版不同答复"),
                rule("new", "新增固定问答"),
                rule("different-id", "相同的固定问答")));
    var request = input(backup, true, "keep_target", base);
    var preview = transfer.preview(shop, request);
    assertEquals(1, preview.get("added"));
    assertEquals(1, preview.get("conflicts"));
    assertEquals(2, preview.get("skipped"));
    var result = transfer.migrate(shop, request, "reviewer");
    assertEquals(2, versions());
    assertTrue(transfer.exportArchive(shop).snapshotJson().contains("目标已有答复"));
    assertFalse(transfer.exportArchive(shop).snapshotJson().contains("开发版不同答复"));
    var repeated = transfer.migrate(shop, request, "reviewer");
    assertEquals(true, repeated.get("unchanged"));
    assertEquals(result.get("knowledge_version"), repeated.get("knowledge_version"));
    assertEquals(2, versions());
  }

  @Test
  void replacingMatchingEntriesRetainsUnrelatedTargetEntriesAndOldVersion() {
    String base = publish(List.of(rule("same", "旧内容"), rule("other", "保留的其他资料")));
    var request = input(archive(List.of(rule("same", "新内容"))), true, "replace_matches", base);
    var result = transfer.migrate(shop, request, "reviewer");
    assertEquals(1, result.get("updated"));
    String next = transfer.exportArchive(shop).snapshotJson();
    assertTrue(next.contains("新内容"));
    assertTrue(next.contains("保留的其他资料"));
    assertTrue(
        jdbc.queryForObject(
                "SELECT snapshot FROM knowledge_versions WHERE id=?", String.class, base)
            .contains("旧内容"));
  }

  @Test
  void concurrentPublicationRejectsStaleMigration() {
    String base = publish(List.of(rule("original", "原资料")));
    var request = input(archive(List.of(rule("incoming", "准备迁移的资料"))), true, "keep_target", base);
    transfer.preview(shop, request);
    publish(List.of(rule("current", "其他客服刚刚发布的资料")));
    var error =
        assertThrows(
            ResponseStatusException.class, () -> transfer.migrate(shop, request, "reviewer"));
    assertEquals("KNOWLEDGE_VERSION_CONFLICT", error.getReason());
    assertEquals(2, versions());
    assertFalse(transfer.exportArchive(shop).snapshotJson().contains("准备迁移的资料"));
  }

  @Test
  void missingProductBlocksAllWritesUnlessRulesOnlyWasExplicitlySelected() {
    var product =
        new LinkedHashMap<String, Object>(
            Map.of(
                "source_id",
                "product",
                "source_type",
                "product",
                "goods_id",
                "goods-1",
                "topics",
                List.of("spec"),
                "text",
                "每件一张"));
    var backup = archive(List.of(rule("rule", "已核实规则"), product));
    var all = input(backup, true, "keep_target", "");
    assertEquals(Set.of("goods-1"), transfer.preview(shop, all).get("missing_goods_ids"));
    assertEquals(false, transfer.preview(shop, all).get("can_import"));
    assertThrows(ResponseStatusException.class, () -> transfer.migrate(shop, all, "reviewer"));
    assertEquals(0, versions());
    var rules = input(backup, false, "keep_target", "");
    assertEquals(1, transfer.preview(shop, rules).get("excluded_products"));
    transfer.migrate(shop, rules, "reviewer");
    assertEquals(1, ((List<?>) store.fromJson(transfer.exportArchive(shop).snapshotJson())).size());
  }

  @Test
  void productBindingIsPreservedWhenTargetCatalogContainsTheExactProduct() {
    jdbc.update(
        "INSERT INTO product_snapshots(id,shop_id,products,created_at,checked_at) VALUES"
            + " (?,?,?,1,1)",
        UUID.randomUUID().toString(),
        shop,
        "[{\"goods_id\":\"goods-1\"}]");
    var product =
        new LinkedHashMap<String, Object>(
            Map.of(
                "source_id",
                "product",
                "source_type",
                "product",
                "goods_id",
                "goods-1",
                "topics",
                List.of("spec"),
                "text",
                "每件一张"));
    var request = input(archive(List.of(product)), true, "keep_target", "");
    transfer.migrate(shop, request, "reviewer");
    assertEquals(List.of(product), store.fromJson(transfer.exportArchive(shop).snapshotJson()));
  }

  @Test
  void invalidChecksumAndOversizedMergeCannotChangeTarget() {
    var valid = archive(List.of(rule("incoming", "内容")));
    var invalid =
        new KnowledgeArchive(
            valid.format(),
            1,
            valid.exportedAt(),
            valid.source(),
            valid.snapshotJson() + " ",
            valid.snapshotSha256());
    assertThrows(
        ResponseStatusException.class,
        () -> transfer.preview(shop, input(invalid, true, "keep_target", "")));
    var existing = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 500; i++) existing.add(rule("existing-" + i, "已有内容" + i));
    String base = publish(existing);
    var request = input(valid, true, "keep_target", base);
    assertEquals(false, transfer.preview(shop, request).get("can_import"));
    assertThrows(ResponseStatusException.class, () -> transfer.migrate(shop, request, "reviewer"));
    assertEquals(1, versions());
  }

  @Test
  void archiveSourceIdentityCannotGrantAccessToAnotherShop() {
    String other = "other-shop";
    var request = input(archive(List.of(rule("source", "资料"))), true, "keep_target", "");
    assertEquals(
        HttpStatus.FORBIDDEN,
        assertThrows(ResponseStatusException.class, () -> transfer.preview(other, request))
            .getStatusCode());
    assertEquals(
        HttpStatus.FORBIDDEN,
        assertThrows(ResponseStatusException.class, () -> transfer.exportArchive(other))
            .getStatusCode());
    assertEquals(
        HttpStatus.FORBIDDEN,
        assertThrows(
                ResponseStatusException.class, () -> transfer.migrate(other, request, "reviewer"))
            .getStatusCode());
  }

  @Test
  void malformedEntryTypesReturnValidationErrorsInsteadOfInternalErrors() {
    var entry = rule("broken", "资料");
    entry.put("source_type", null);
    assertEquals(
        "INVALID_KNOWLEDGE_ENTRY",
        assertThrows(
                ResponseStatusException.class,
                () ->
                    transfer.preview(shop, input(archive(List.of(entry)), true, "keep_target", "")))
            .getReason());
    entry.put("source_type", "shop_policy");
    entry.put("topics", Arrays.asList("faq", null));
    assertEquals(
        "INVALID_KNOWLEDGE_TOPICS",
        assertThrows(
                ResponseStatusException.class,
                () ->
                    transfer.preview(shop, input(archive(List.of(entry)), true, "keep_target", "")))
            .getReason());
    assertEquals(0, versions());
  }

  @Test
  void onlyAdministratorCanDownloadArchiveOverHttp() {
    publish(List.of(rule("rule", "受控的店铺资料")));
    String username = "staff-" + UUID.randomUUID().toString().substring(0, 8);
    String user = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO app_users(id,username,password_hash,shop_id,role,enabled,created_at) VALUES"
            + " (?,?,?,?,?,TRUE,0)",
        user,
        username,
        new BCryptPasswordEncoder().encode("test-staff-password-123"),
        shop,
        "REVIEWER");
    jdbc.update("INSERT INTO workspace_memberships(workspace_id,user_id,role,created_at) VALUES (?,?,'REVIEWER',0)", workspace, user);
    var headers = new HttpHeaders();
    headers.set("X-Shop-Id", shop);
    String path = "/api/v1/admin/shops/" + shop + "/knowledge/archive";
    var forbidden =
        BrowserTestClient.login(http, username, "test-staff-password-123")
            .exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
    var allowed =
        BrowserTestClient.login(http, "reviewer", "test-reviewer-password-123")
            .exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertEquals(HttpStatus.OK, allowed.getStatusCode());
    assertTrue(allowed.getHeaders().getFirst("Content-Disposition").contains("attachment"));
    assertTrue(allowed.getHeaders().getCacheControl().contains("no-store"));
    assertTrue(allowed.getBody().contains("snapshot_sha256"));
  }
}

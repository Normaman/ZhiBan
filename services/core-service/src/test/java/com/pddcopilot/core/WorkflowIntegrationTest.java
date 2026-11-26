package com.pddcopilot.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.api.dto.ModelConfigurationInput;
import com.pddcopilot.core.bootstrap.Bootstrap;
import com.pddcopilot.core.client.AgentClient;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.service.AgentRunService;
import com.pddcopilot.core.service.ConnectionService;
import com.pddcopilot.core.service.ConversationService;
import com.pddcopilot.core.service.InboxService;
import com.pddcopilot.core.service.JobService;
import com.pddcopilot.core.service.KnowledgeService;
import com.pddcopilot.core.service.ModelConfigurationService;
import com.pddcopilot.core.service.ReadReceiptService;
import com.pddcopilot.core.service.ReplyApprovalService;
import com.pddcopilot.core.service.ReplySendService;
import com.pddcopilot.core.service.UserService;
import com.pddcopilot.core.support.ModelSecretCipher;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "copilot.session-store=memory",
      "spring.datasource.url=jdbc:h2:mem:copilot;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "copilot.reviewer-password=test-reviewer-password-123",
      "copilot.internal-token=test-internal-token-with-32-characters",
      "copilot.registration-invite=test-invite-code-32-characters-long",
      "copilot.model-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "copilot.allow-local-model-endpoints=true",
      "copilot.model-allowed-hosts=model.example,another.example,api.deepseek.com",
      "copilot.worker-enabled=false",
      "copilot.reply-debounce-ms=0",
      "copilot.shop-id=fixture-shop",
      "copilot.live-send-enabled=true",
      "copilot.live-send-shop=fixture-shop",
      "copilot.live-send-conversation=*",
      "copilot.live-send-expires-at=-1",
    })
class WorkflowIntegrationTest {
  @Autowired BusinessStore store;
  @Autowired com.pddcopilot.core.mapper.ApprovalMapper approvalMapper;
  @Autowired com.pddcopilot.core.mapper.ReplyBatchMapper replyBatchMapper;
  @Autowired com.pddcopilot.core.mapper.AgentRunMapper agentRunMapper;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired InboxService inbox;
  @Autowired ConnectionService connections;
  @Autowired ConversationService conversations;
  @Autowired ReplyApprovalService approvals;
  @Autowired ReplySendService sends;
  @Autowired com.pddcopilot.core.service.ManualReplyService manual;
  @Autowired KnowledgeService knowledge;
  @Autowired AgentRunService runs;
  @Autowired JobService jobs;
  @Autowired Bootstrap bootstrap;
  @Autowired TransactionTemplate tx;
  @Autowired TestRestTemplate http;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.pddcopilot.core.service.LoginRateLimiter loginLimiter;

  @Autowired ModelConfigurationService models;
  @Autowired UserService users;
  @Autowired ReadReceiptService receipts;
  @Autowired com.pddcopilot.core.service.DraftCardService draftCards;
  @Autowired com.pddcopilot.core.service.WorkspaceService workspaces;
  @Autowired com.pddcopilot.core.job.TaskWorker worker;
  @Autowired com.pddcopilot.core.service.ProductService products;
  @Autowired com.pddcopilot.core.repository.PddSendJournal pddSendJournal;
  @MockitoSpyBean com.pddcopilot.core.service.SendPolicy sendPolicy;
  @MockitoBean AgentClient agent;
  @MockitoBean com.pddcopilot.core.platform.pdd.PddCommerceService pddCommerce;

  @BeforeEach
  void reset() {
    for (String table :
        List.of(
            "authentication_events",
            "agent_execution_results",
            "job_outbox",
            "reply_command_messages",
            "reply_run_messages",
            "reply_questions",
            "product_sync_items",
            "product_sync_runs",
            "product_details",
            "workspace_invitations",
            "workspace_memberships",
            "connector_bindings",
            "message_reads",
            "platform_read_markers",
            "app_users",
            "model_settings",
            "model_configurations",
            "product_snapshots",
            "audit_events",
            "jobs",
            "pdd_send_parts",
            "send_commands",
            "manual_replies",
            "decisions",
            "drafts",
            "agent_runs",
            "messages",
            "conversations",
            "knowledge_versions",
            "shops",
            "workspaces")) jdbc.update("DELETE FROM " + table);
    bootstrap.run(null);
    jdbc.update(
        "UPDATE shops SET"
            + " connector_account_id='fixture-account',platform_shop_id='fixture-platform-shop',binding_state='bound'"
            + " WHERE id='fixture-shop'");
    jdbc.update(
        "INSERT INTO connector_bindings(platform_shop_id,shop_id) VALUES"
            + " ('fixture-platform-shop','fixture-shop')");
    connections.update("fixture-shop", new Contracts.Connection("test-epoch", 1, "online"));
  }

  Contracts.Inbound input(String mid, String content) {
    return new Contracts.Inbound(
        UUID.randomUUID().toString(),
        "fixture-shop",
        "fixture-platform-shop",
        "fixture-account",
        mid,
        "buyer-001",
        "inbound",
        "text",
        content,
        OffsetDateTime.parse("2026-10-27T08:00:00Z"),
        OffsetDateTime.now(),
        "reply");
  }

  Map<String, Object> receive(String mid) {
    return inbox.receive(input(mid, "什么时候发货？"), "pdd:fixture-shop:" + mid);
  }

  Map<String, Object> draft(String mid, String outcome) {
    var message = receive(mid);
    var run =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList(
                "SELECT * FROM agent_runs WHERE message_id=?", message.get("message_id")));
    tx.executeWithoutResult(
        status ->
            runs.complete(
                (String) run.get("id"),
                Map.of(
                    "run_id",
                    run.get("id"),
                    "message_id",
                    message.get("message_id"),
                    "outcome",
                    outcome,
                    "content",
                    "您好，48 小时内发货。",
                    "citations",
                    List.of(),
                    "risk_codes",
                    List.of())));
    return com.pddcopilot.core.repository.DatabaseRows.required(
        jdbc.queryForList("SELECT * FROM drafts WHERE message_id=?", message.get("message_id")));
  }

  Map<String, Object> cardPreview() {
    return Map.of(
        "goods_id",
        "fixture-goods-1",
        "title",
        "轻行保温杯",
        "image_url",
        "https://img.pddpic.com/test.jpg",
        "price",
        "89.00",
        "fetched_at",
        "2026-10-28T12:00:00Z");
  }

  Map<String, Object> cardDraft(String message) {
    var value = draft(message, "draft");
    jdbc.update(
        "UPDATE drafts SET product_cards=? WHERE id=?",
        store.toJson(List.of(cardPreview())),
        value.get("id"));
    return value;
  }

  Map<String, Object> approveCard(Map<String, Object> value) {
    approvals.decide(
        value.get("id").toString(),
        new Contracts.Decision("approve", 1, "有的，您可以看看这款。", List.of("fixture-goods-1")),
        "reviewer");
    return com.pddcopilot.core.repository.DatabaseRows.required(
        jdbc.queryForList("SELECT * FROM send_command_context WHERE draft_id=?", value.get("id")));
  }

  @Test
  void cardDecisionsFreezeOnlyExplicitSelectionsAndRejectChangedReplay() {
    var value = cardDraft("card-approval");
    String id = value.get("id").toString();
    assertThrows(
        ResponseStatusException.class,
        () ->
            approvals.decide(
                id,
                new Contracts.Decision("approve", 1, "有货。", List.of("foreign-goods")),
                "reviewer"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            approvals.decide(
                id,
                new Contracts.Decision(
                    "approve", 1, "有货。", List.of("fixture-goods-1", "fixture-goods-1")),
                "reviewer"));
    var command = approveCard(value);
    assertEquals(List.of(cardPreview()), store.fromJson(command.get("product_cards").toString()));
    approvals.decide(
        id,
        new Contracts.Decision("approve", 1, "有的，您可以看看这款。", List.of("fixture-goods-1")),
        "reviewer");
    assertThrows(
        ResponseStatusException.class,
        () ->
            approvals.decide(
                id, new Contracts.Decision("approve", 1, "有的，您可以看看这款。", List.of()), "reviewer"));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM send_commands", Integer.class));
  }

  @Test
  void olderClientsDoNotSendUnselectedCards() {
    var value = cardDraft("card-old-client");
    approvals.decide(
        value.get("id").toString(), new Contracts.Decision("approve", 1, "有货。"), "reviewer");
    assertEquals(
        "[]",
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT product_cards FROM send_command_context"))
            .get("product_cards"));
  }

  @Test
  void replacingCardsReadsThisShopAndInvalidatesOlderApprovalVersions() {
    var value = cardDraft("card-edit");
    when(pddCommerce.products())
        .thenReturn(
            Map.of(
                "shop_id",
                "fixture-shop",
                "products",
                List.of(
                    Map.of(
                        "goods_id",
                        "replacement",
                        "goods_name",
                        "替换商品",
                        "quantity",
                        2,
                        "fetched_at",
                        "2026-10-28T12:01:00Z",
                        "price",
                        "23.00"))));
    String id = value.get("id").toString();
    draftCards.replace(id, new Contracts.Cards(1, List.of("replacement"), "我已编辑的回复。"), "reviewer");
    var current =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT * FROM drafts WHERE id=?", id));
    assertEquals(2L, ((Number) current.get("revision")).longValue());
    assertEquals("我已编辑的回复。", current.get("content"));
    assertEquals(true, current.get("cards_edited"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            approvals.decide(
                id,
                new Contracts.Decision("approve", 1, "有货。", List.of("replacement")),
                "reviewer"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            draftCards.replace(
                id, new Contracts.Cards(2, List.of("foreign-goods"), null), "reviewer"));
    when(pddCommerce.products())
        .thenReturn(Map.of("shop_id", "foreign-shop", "products", List.of()));
    assertThrows(
        ResponseStatusException.class,
        () ->
            draftCards.replace(
                id, new Contracts.Cards(2, List.of("replacement"), null), "reviewer"));
  }

  @Test
  void partialDeliveryRecordsTextOnceAndResolvesConsultationOnlyAfterCardSuccess() {
    var value = cardDraft("card-partial");
    var command = approveCard(value);
    String cid = command.get("conversation_id").toString(), id = command.get("id").toString();
    var text =
        Map.<String, Object>of(
            "key", "text", "kind", "text", "state", "sent", "platform_message_id", "71001");
    sends.complete(
        id,
        Map.of(
            "state",
            "send_failed",
            "parts",
            List.of(
                text,
                Map.of(
                    "key",
                    "card:fixture-goods-1",
                    "kind",
                    "product_card",
                    "goods_id",
                    "fixture-goods-1",
                    "state",
                    "send_failed"))));
    assertEquals(
        1, ((List<?>) conversations.detail(cid, "reviewer").get("pending_questions")).size());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM messages WHERE direction='outbound'", Integer.class));
    sends.retry(id, "reviewer");
    var result =
        Map.<String, Object>of(
            "state",
            "sent",
            "parts",
            List.of(
                text,
                Map.of(
                    "key",
                    "card:fixture-goods-1",
                    "kind",
                    "product_card",
                    "goods_id",
                    "fixture-goods-1",
                    "state",
                    "sent",
                    "platform_message_id",
                    "71002")));
    sends.complete(id, result);
    sends.complete(id, result);
    assertEquals(
        0, ((List<?>) conversations.detail(cid, "reviewer").get("pending_questions")).size());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM messages WHERE direction='outbound'", Integer.class));
    var base = input("71002", "unused");
    String nativeCard =
        store.toJson(
            Map.of("goods_id", "fixture-goods-1", "goods_name", "平台实际商品名称", "goods_price", "89"));
    var echo =
        new Contracts.Inbound(
            base.eventId(),
            base.shopId(),
            base.platformShopId(),
            base.connectorAccountId(),
            "71002",
            base.buyerRef(),
            "outbound",
            "goods_card",
            nativeCard,
            base.occurredAt(),
            base.receivedAt(),
            "context_only");
    inbox.receive(echo, "pdd:fixture-shop:71002");
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM messages WHERE direction='outbound'", Integer.class));
    assertEquals(
        nativeCard,
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT content FROM messages WHERE platform_message_id='71002'"))
            .get("content"));
  }

  @Test
  void cardAcknowledgementWithoutIdentifierWaitsForNativeEchoWithoutInventingMessage() {
    var value = cardDraft("card-no-id");
    var command = approveCard(value);
    sends.complete(
        command.get("id").toString(),
        Map.of(
            "state",
            "sent",
            "parts",
            List.of(
                Map.of("key", "text", "kind", "text", "state", "sent"),
                Map.of(
                    "key",
                    "card:fixture-goods-1",
                    "kind",
                    "product_card",
                    "goods_id",
                    "fixture-goods-1",
                    "state",
                    "sent"))));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM messages WHERE message_type='goods_card'", Integer.class));
    assertEquals(
        "sent",
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT state FROM send_commands WHERE id=?", command.get("id")))
            .get("state"));
  }

  @Test
  void missingPartCannotMarkCompositeReplySent() {
    var command = approveCard(cardDraft("card-missing-part"));
    assertThrows(
        IllegalStateException.class,
        () ->
            sends.complete(
                command.get("id").toString(),
                Map.of(
                    "state",
                    "sent",
                    "parts",
                    List.of(Map.of("key", "text", "kind", "text", "state", "sent")))));
    assertEquals(
        "approved",
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT state FROM send_commands WHERE id=?", command.get("id")))
            .get("state"));
  }

  @Test
  void generatedCardsRequireMatchingLivePlatformEvidence() {
    var received = receive("card-evidence");
    var run =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList(
                "SELECT * FROM agent_runs WHERE message_id=?", received.get("message_id")));
    var result = new LinkedHashMap<String, Object>();
    result.put("run_id", run.get("id"));
    result.put("message_id", received.get("message_id"));
    result.put("outcome", "draft");
    result.put("content", "有的，您可以看看这款。");
    result.put("citations", List.of());
    result.put("risk_codes", List.of());
    result.put("product_cards", List.of(cardPreview()));
    assertThrows(
        IllegalStateException.class, () -> runs.complete(run.get("id").toString(), result));
    result.put(
        "citations",
        List.of(
            Map.of(
                "source_type",
                "platform_product",
                "source_id",
                "fixture-goods-1",
                "source_version",
                "2026-10-28T12:00:00Z",
                "fetched_at",
                "2026-10-28T12:00:00Z",
                "evidence_text",
                "轻行保温杯，库存32。")));
    runs.complete(run.get("id").toString(), result);
    assertEquals(
        List.of(cardPreview()),
        store.fromJson(
            com.pddcopilot.core.repository.DatabaseRows.required(
                    jdbc.queryForList(
                        "SELECT product_cards FROM drafts WHERE message_id=?",
                        received.get("message_id")))
                .get("product_cards")
                .toString()));
  }

  @Test
  void burstUsesOneGenerationAndOnlyDeliveryResolvesCoveredConsultations() {
    receive("burst-1");
    receive("burst-2");
    var last = receive("burst-3");
    String cid = last.get("conversation_id").toString();
    assertEquals(
        3, ((List<?>) conversations.detail(cid, "reviewer").get("pending_questions")).size());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM jobs WHERE state='pending' AND kind='generate'", Integer.class));
    var job = jobs.claim();
    assertNotNull(job);
    assertNull(jobs.claim());
    String run = job.get("reference_id").toString();
    assertEquals(3, ((List<?>) runs.context(run).get("pending_questions")).size());
    jobs.complete(
        job,
        Map.of(
            "run_id",
            run,
            "message_id",
            last.get("message_id"),
            "outcome",
            "draft",
            "content",
            "这是一份覆盖三条咨询的待审批回复。",
            "citations",
            List.of(),
            "risk_codes",
            List.of()));
    var draft =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT * FROM drafts WHERE run_id=?", run));
    assertEquals(
        3, ((List<?>) conversations.detail(cid, "reviewer").get("pending_questions")).size());
    approvals.decide(
        draft.get("id").toString(), new Contracts.Decision("approve", 1, "已核对三条咨询。"), "reviewer");
    var command =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT * FROM send_commands WHERE draft_id=?", draft.get("id")));
    sends.complete(command.get("id").toString(), Map.of("state", "send_failed"));
    assertEquals(
        3, ((List<?>) conversations.detail(cid, "reviewer").get("pending_questions")).size());
    sends.retry(command.get("id").toString(), "reviewer");
    var newer = receive("burst-4");
    sends.complete(command.get("id").toString(), Map.of("state", "sent"));
    sends.complete(command.get("id").toString(), Map.of("state", "sent"));
    var pending =
        (List<Map<String, Object>>) conversations.detail(cid, "reviewer").get("pending_questions");
    assertEquals(1, pending.size());
    assertEquals(newer.get("message_id"), pending.get(0).get("message_id"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM messages WHERE direction='outbound'", Integer.class));
  }

  @Test
  void simultaneousClaimsSerializeConversationAndAllowAnotherBuyer() throws Exception {
    var first = receive("serial-1");
    var pool = Executors.newFixedThreadPool(6);
    Map<String, Object> running = null;
    try {
      var tasks = new ArrayList<Callable<Map<String, Object>>>();
      for (int i = 0; i < 6; i++) tasks.add(() -> jobs.claim());
      int claimed = 0;
      for (var future : pool.invokeAll(tasks)) {
        var result = future.get();
        if (result != null) {
          claimed++;
          running = result;
        }
      }
      assertEquals(1, claimed);
    } finally {
      pool.shutdownNow();
    }
    receive("serial-2");
    assertNull(jobs.claim());
    var other =
        new Contracts.Inbound(
            UUID.randomUUID().toString(),
            "fixture-shop",
            "fixture-platform-shop",
            "fixture-account",
            "other-mid",
            "other-buyer",
            "inbound",
            "text",
            "库存是多少？",
            OffsetDateTime.now(),
            OffsetDateTime.now(),
            "reply");
    var second = inbox.receive(other, "pdd:fixture-shop:other-mid");
    var parallel = jobs.claim();
    assertNotNull(parallel);
    assertEquals(
        second.get("message_id"),
        runs.context(parallel.get("reference_id").toString()).get("message_id"));
    String oldRun = running.get("reference_id").toString();
    jobs.complete(
        running,
        Map.of(
            "run_id",
            oldRun,
            "message_id",
            first.get("message_id"),
            "outcome",
            "draft",
            "content",
            "旧结果",
            "citations",
            List.of(),
            "risk_codes",
            List.of()));
    assertEquals(
        "superseded",
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT state FROM agent_runs WHERE id=?", oldRun))
            .get("state"));
    var next = jobs.claim();
    assertNotNull(next);
    assertEquals(
        2,
        ((List<?>) runs.context(next.get("reference_id").toString()).get("pending_questions"))
            .size());
  }

  @Test
  void ancientPendingQuestionsAreMemoryNotNewBatchAndCanBeRetriedAlone() {
    var old = receive("yesterday");
    jdbc.update(
        "UPDATE messages SET created_at=created_at-64800000 WHERE id=?", old.get("message_id"));
    var fresh = receive("today");
    var job = jobs.claim();
    var questions =
        (List<Map<String, Object>>)
            runs.context(job.get("reference_id").toString()).get("pending_questions");
    assertEquals(
        List.of(fresh.get("message_id")),
        questions.stream().map(q -> q.get("message_id")).toList());
    jobs.complete(
        job,
        Map.of(
            "run_id",
            job.get("reference_id"),
            "message_id",
            fresh.get("message_id"),
            "outcome",
            "needs_human",
            "content",
            "需核实",
            "citations",
            List.of(),
            "risk_codes",
            List.of()));
    assertEquals(
        2,
        ((List<?>)
                conversations
                    .detail(old.get("conversation_id").toString(), "reviewer")
                    .get("pending_questions"))
            .size());
    runs.regenerate(old.get("message_id").toString(), "reviewer", true);
    var retry = jobs.claim();
    var selected =
        (List<Map<String, Object>>)
            runs.context(retry.get("reference_id").toString()).get("pending_questions");
    assertEquals(
        List.of(old.get("message_id")), selected.stream().map(q -> q.get("message_id")).toList());
  }

  @Test
  void partialAnswerOnlyResolvesItsExplicitCoverageAfterDelivery() {
    var a = receive("partial-supported");
    var b = receive("partial-missing");
    var job = jobs.claim();
    String run = job.get("reference_id").toString();
    var result = new LinkedHashMap<String, Object>();
    result.put("run_id", run);
    result.put("message_id", b.get("message_id"));
    result.put("outcome", "draft");
    result.put("content", "仅回复第一个问题。");
    result.put("citations", List.of());
    result.put("risk_codes", List.of());
    result.put("answered_message_ids", List.of("foreign-message"));
    assertThrows(IllegalStateException.class, () -> runs.complete(run, result));
    result.put("answered_message_ids", List.of(a.get("message_id")));
    jobs.complete(job, result);
    var draft =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT * FROM drafts WHERE run_id=?", run));
    approvals.decide(
        draft.get("id").toString(), new Contracts.Decision("approve", 1, "仅回复第一个问题。"), "reviewer");
    var send = jobs.claim();
    jobs.complete(send, Map.of("state", "sent"));
    var remaining =
        (List<Map<String, Object>>)
            conversations
                .detail(a.get("conversation_id").toString(), "reviewer")
                .get("pending_questions");
    assertEquals(
        List.of(b.get("message_id")), remaining.stream().map(q -> q.get("message_id")).toList());
  }

  @Test
  void manualCoverageIsExplicitImmutableAndClearedOnlyOnSuccess() {
    var a = receive("coverage-a");
    var b = receive("coverage-b");
    String cid = a.get("conversation_id").toString();
    conversations.transfer(cid, "reviewer");
    long rev = ((Number) conversations.detail(cid, "reviewer").get("revision")).longValue();
    var request =
        new Contracts.ManualReply(
            UUID.randomUUID().toString(),
            rev,
            "先回答第一个问题。",
            List.of(a.get("message_id").toString()));
    var accepted = manual.submit(cid, request, "reviewer");
    assertEquals(accepted, manual.submit(cid, request, "reviewer"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            manual.submit(
                cid,
                new Contracts.ManualReply(
                    request.requestId(),
                    rev,
                    request.content(),
                    List.of(b.get("message_id").toString())),
                "reviewer"));
    String command = accepted.get("send_command_id").toString();
    sends.complete(command, Map.of("state", "send_unknown"));
    assertEquals(
        2, ((List<?>) conversations.detail(cid, "reviewer").get("pending_questions")).size());
    sends.complete(command, Map.of("state", "sent"));
    var pending =
        (List<Map<String, Object>>) conversations.detail(cid, "reviewer").get("pending_questions");
    assertEquals(
        List.of(b.get("message_id")), pending.stream().map(q -> q.get("message_id")).toList());
  }

  @Test
  void queuedBatchWaitsForPriorSendAndDropsAlreadyAnsweredQuestions() {
    var d = draft("sending-first", "draft");
    String cid =
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList(
                    "SELECT conversation_id FROM messages WHERE id=?", d.get("message_id")))
            .get("conversation_id")
            .toString();
    approvals.decide(
        d.get("id").toString(), new Contracts.Decision("approve", 1, "您好，48小时内发货。"), "reviewer");
    var sending = jobs.claim();
    assertEquals("send", sending.get("kind"));
    var next = receive("sending-second");
    assertNull(jobs.claim());
    jobs.complete(sending, Map.of("state", "sent"));
    // A platform echo/context update advances the conversation after the queued request.
    jdbc.update("UPDATE conversations SET revision=revision+1 WHERE id=?", cid);
    var generation = jobs.claim();
    assertNotNull(generation);
    var ctx = runs.context(generation.get("reference_id").toString());
    var covered = (List<Map<String, Object>>) ctx.get("pending_questions");
    assertEquals(1, covered.size());
    assertEquals(next.get("message_id"), covered.get(0).get("message_id"));
    assertEquals(
        conversations.detail(cid, "reviewer").get("revision"), ctx.get("conversation_revision"));
  }

  @Test
  void orderReferenceUsesSameBuyerAndQuestionTimeWithoutBorrowingLaterOrders() {
    var first = receive("order-old");
    String cid = first.get("conversation_id").toString();
    jdbc.update(
        "UPDATE messages SET message_type='order_info',content=?,created_at=1000 WHERE id=?",
        "{\"order_id\":12345678901234567890}",
        first.get("message_id"));
    var question = receive("order-question");
    jdbc.update("UPDATE messages SET created_at=2000 WHERE id=?", question.get("message_id"));
    String run =
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList(
                    "SELECT id FROM agent_runs WHERE message_id=?", question.get("message_id")))
            .get("id")
            .toString();
    var later = receive("order-later");
    jdbc.update(
        "UPDATE messages SET message_type='order_info',content=?,created_at=3000 WHERE id=?",
        "{\"order_id\":\"222222-222222222222222\"}",
        later.get("message_id"));
    var other =
        inbox.receive(
            shopInput("fixture-shop", "order-other-buyer", "other-buyer"),
            "pdd:fixture-shop:order-other-buyer");
    jdbc.update(
        "UPDATE messages SET message_type='order_info',content=?,created_at=1900 WHERE id=?",
        "{\"order_id\":\"333333-333333333333333\"}",
        other.get("message_id"));
    var context = runs.context(run);
    assertEquals(
        "12345678901234567890", ((Map<?, ?>) context.get("order_reference")).get("order_id"));
    assertEquals(store.scopedConversation(cid, false).get("buyer_ref"), context.get("buyer_ref"));
    assertFalse(
        context.containsKey(
            "order_context")); // References require platform ownership verification.
  }

  @Test
  void multipleOrderNumbersInOneMessageAreNotSilentlyBoundToTheFirst() {
    var message = receive("order-ambiguous");
    jdbc.update(
        "UPDATE messages SET content=? WHERE id=?",
        "订单111111-111111111111111和222222-222222222222222",
        message.get("message_id"));
    var run =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList(
                "SELECT id FROM agent_runs WHERE message_id=?", message.get("message_id")));
    assertEquals(
        true,
        ((Map<?, ?>) runs.context(run.get("id").toString()).get("order_reference"))
            .get("ambiguous"));
  }

  @Test
  void debounceHasQuietPeriodAndHardDeadline() {
    var first = receive("window-1");
    String cid = first.get("conversation_id").toString();
    var batching =
        new AgentRunService(
            store,
            new com.pddcopilot.core.repository.AgentRunRepository(agentRunMapper, store),
            new com.pddcopilot.core.service.ProductContextResolver(
                store,
                new com.pddcopilot.core.repository.AgentRunRepository(agentRunMapper, store)),
            new com.pddcopilot.core.service.OrderContextResolver(
                new com.pddcopilot.core.repository.AgentRunRepository(agentRunMapper, store),
                store),
            new com.pddcopilot.core.repository.ReplyBatchRepository(replyBatchMapper, store),
            2000);
    long before = System.currentTimeMillis();
    tx.executeWithoutResult(
        t ->
            batching.start(
                first.get("message_id").toString(), store.scopedConversation(cid, true), null));
    var j =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT * FROM jobs WHERE state='pending'"));
    assertTrue(((Number) j.get("available_at")).longValue() >= before + 2000);
    long burstStart = System.currentTimeMillis() - 7500;
    jdbc.update(
        "UPDATE agent_runs SET batch_started_at=? WHERE id=?", burstStart, j.get("reference_id"));
    tx.executeWithoutResult(
        t ->
            batching.start(
                first.get("message_id").toString(), store.scopedConversation(cid, true), null));
    var bounded =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT * FROM jobs WHERE state='pending'"));
    assertEquals(burstStart + 8000, ((Number) bounded.get("available_at")).longValue());
    assertNull(jobs.claim());
  }

  @Test
  void manualReplyIsIndependentIdempotentAndKeepsAgentPaused() throws Exception {
    String cid = receive("manual-start").get("conversation_id").toString();
    conversations.transfer(cid, "reviewer");
    long revision = ((Number) conversations.detail(cid, "reviewer").get("revision")).longValue();
    var input = new Contracts.ManualReply(UUID.randomUUID().toString(), revision, "我来帮您核实，请稍等。");
    var pool = Executors.newFixedThreadPool(5);
    try {
      var tasks = new ArrayList<Callable<Map<String, Object>>>();
      for (int i = 0; i < 5; i++) tasks.add(() -> manual.submit(cid, input, "reviewer"));
      var results = pool.invokeAll(tasks);
      var first = results.get(0).get();
      for (var result : results) assertEquals(first, result.get());
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM manual_replies", Integer.class));
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM send_commands", Integer.class));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM drafts", Integer.class));
      var command = sends.command(first.get("send_command_id").toString());
      assertEquals("manual", command.get("origin"));
      assertEquals("transferred", command.get("conversation_state"));
      assertEquals(input.content(), command.get("final_content"));
      assertNull(command.get("draft_id"));
      assertEquals(
          "SEND_IN_PROGRESS",
          assertThrows(
                  ResponseStatusException.class,
                  () -> conversations.resume(cid, revision + 1, "reviewer"))
              .getReason());
      var job = jobs.claim();
      assertEquals("send", job.get("kind"));
      jobs.complete(job, Map.of("state", "sent"));
      var replay = manual.submit(cid, input, "reviewer");
      assertEquals("sent", replay.get("state"));
      assertEquals(first.get("manual_reply_id"), replay.get("manual_reply_id"));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM messages WHERE direction='outbound'", Integer.class));
      receive("manual-followup");
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM agent_runs", Integer.class));
      long latest = ((Number) conversations.detail(cid, "reviewer").get("revision")).longValue();
      conversations.resume(cid, latest, "reviewer");
      receive("agent-followup");
      assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM agent_runs", Integer.class));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void manualReplyRejectsWrongModeStaleRevisionAndCrossShop() {
    String cid = receive("manual-guards").get("conversation_id").toString();
    var input = new Contracts.ManualReply(UUID.randomUUID().toString(), 1, "人工输入");
    assertEquals(
        "MANUAL_TAKEOVER_REQUIRED",
        assertThrows(ResponseStatusException.class, () -> manual.submit(cid, input, "reviewer"))
            .getReason());
    conversations.transfer(cid, "reviewer");
    assertEquals(
        "CONVERSATION_VERSION_CONFLICT",
        assertThrows(ResponseStatusException.class, () -> manual.submit(cid, input, "reviewer"))
            .getReason());
    var current = new Contracts.ManualReply(input.requestId(), 2, input.content());
    try (var scope = com.pddcopilot.core.support.ShopScope.open("another-shop")) {
      assertEquals(
          "SHOP_FORBIDDEN",
          assertThrows(ResponseStatusException.class, () -> manual.submit(cid, current, "reviewer"))
              .getReason());
    }
    connections.update("fixture-shop", new Contracts.Connection("test-epoch", 2, "disconnected"));
    assertEquals(
        "CONNECTOR_OFFLINE",
        assertThrows(ResponseStatusException.class, () -> manual.submit(cid, current, "reviewer"))
            .getReason());
    connections.update("fixture-shop", new Contracts.Connection("test-epoch", 3, "online"));
    doReturn(false).when(sendPolicy).shopEnabled();
    assertEquals(
        "LIVE_SEND_NOT_ENABLED",
        assertThrows(ResponseStatusException.class, () -> manual.submit(cid, current, "reviewer"))
            .getReason());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM manual_replies", Integer.class));
  }

  @Test
  void manualReplyRetriesOnlyDefiniteFailureAndBlocksUnknownResults() {
    String cid = receive("manual-retry").get("conversation_id").toString();
    conversations.transfer(cid, "reviewer");
    var input = new Contracts.ManualReply(UUID.randomUUID().toString(), 2, "人工回复");
    var result = manual.submit(cid, input, "reviewer");
    assertEquals(
        "IDEMPOTENCY_PAYLOAD_CONFLICT",
        assertThrows(
                ResponseStatusException.class,
                () ->
                    manual.submit(
                        cid, new Contracts.ManualReply(input.requestId(), 2, "改写内容"), "reviewer"))
            .getReason());
    var first = jobs.claim();
    jobs.complete(first, Map.of("state", "send_failed"));
    sends.retry(result.get("send_command_id").toString(), "reviewer");
    var retry = jobs.claim();
    assertEquals(
        2,
        ((Number) sends.command(result.get("send_command_id").toString()).get("attempt"))
            .intValue());
    jobs.complete(retry, Map.of("state", "send_unknown"));
    assertEquals(
        "SEND_RESULT_UNRESOLVED",
        assertThrows(
                ResponseStatusException.class,
                () ->
                    manual.submit(
                        cid,
                        new Contracts.ManualReply(UUID.randomUUID().toString(), 3, "下一条"),
                        "reviewer"))
            .getReason());
    assertEquals(
        "SEND_RESULT_UNRESOLVED",
        assertThrows(ResponseStatusException.class, () -> conversations.resume(cid, 3, "reviewer"))
            .getReason());
    assertEquals(
        "SEND_NOT_RETRYABLE",
        assertThrows(
                ResponseStatusException.class,
                () -> sends.retry(result.get("send_command_id").toString(), "reviewer"))
            .getReason());
  }

  @Test
  void pddSendJournalMakesSuccessfulAndUnknownPartsNonReplayable() {
    var draft = cardDraft("journal-protection");
    var command = approveCard(draft);
    String commandId = command.get("id").toString();
    String digest = "a".repeat(64);

    var first = pddSendJournal.begin(commandId, 1, "text", digest);
    assertTrue(first.created());
    assertEquals("sending", first.state());
    pddSendJournal.finish(
        commandId,
        1,
        "text",
        Map.of("state", "sent", "platform_message_id", "12345", "mode", "live"));
    var duplicate = pddSendJournal.begin(commandId, 1, "text", digest);
    assertFalse(duplicate.created());
    assertEquals("sent", duplicate.state());
    assertEquals("12345", duplicate.receipt().get("platform_message_id"));

    var uncertain = pddSendJournal.begin(commandId, 1, "card:fixture-goods-1", digest);
    assertTrue(uncertain.created());
    pddSendJournal.finish(
        commandId,
        1,
        "card:fixture-goods-1",
        Map.of("state", "send_unknown", "error_code", "UNRECOGNIZED_CARD_RECEIPT"));
    var blocked = pddSendJournal.begin(commandId, 1, "card:fixture-goods-1", digest);
    assertFalse(blocked.created());
    assertEquals("send_unknown", blocked.state());
    assertThrows(
        IllegalStateException.class,
        () -> pddSendJournal.begin(commandId, 1, "text", "b".repeat(64)));
  }

  @Test
  void liveSendScopeIsLimitedToOneConversationAndExpires() {
    String cid = receive("scope-test").get("conversation_id").toString();
    jdbc.update("UPDATE shops SET mode='live'");
    var repo = new com.pddcopilot.core.repository.ApprovalRepository(approvalMapper, store);
    var allowed =
        new com.pddcopilot.core.service.SendPolicy(
            store, repo, true, "fixture-shop", cid, System.currentTimeMillis() + 60000);
    var persistent =
        new com.pddcopilot.core.service.SendPolicy(store, repo, true, "fixture-shop", cid, -1);
    assertTrue(persistent.enabledFor(cid));
    assertFalse(persistent.enabledFor("another-conversation"));
    assertFalse(
        new com.pddcopilot.core.service.SendPolicy(store, repo, false, "fixture-shop", cid, -1)
            .enabledFor(cid));
    assertFalse(
        new com.pddcopilot.core.service.SendPolicy(store, repo, true, "wrong-shop", cid, -1)
            .enabledFor(cid));
    assertTrue(allowed.enabledFor(cid));
    assertFalse(allowed.enabledFor("another-conversation"));
    assertFalse(
        new com.pddcopilot.core.service.SendPolicy(
                store, repo, true, "wrong-shop", cid, System.currentTimeMillis() + 60000)
            .enabledFor(cid));
    assertFalse(
        new com.pddcopilot.core.service.SendPolicy(store, repo, true, "fixture-shop", cid, 1)
            .enabledFor(cid));
    assertFalse(
        new com.pddcopilot.core.service.SendPolicy(
                store, repo, false, "fixture-shop", cid, System.currentTimeMillis() + 60000)
            .enabledFor(cid));
  }

  @Test
  void operationRecordsDistinguishDraftsMissingKnowledgeAndModelFailure() {
    draft("audit-ready", "draft");
    draft("audit-missing", "insufficient_evidence");
    draft("audit-human", "needs_human");
    var message = receive("audit-failed");
    var run =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList(
                "SELECT id FROM agent_runs WHERE message_id=?", message.get("message_id")));
    tx.executeWithoutResult(
        status ->
            runs.complete(
                run.get("id").toString(),
                Map.of(
                    "run_id",
                    run.get("id"),
                    "message_id",
                    message.get("message_id"),
                    "outcome",
                    "needs_human",
                    "content",
                    "模型处理未完成",
                    "citations",
                    List.of(),
                    "risk_codes",
                    List.of("MODEL_REQUEST_FAILED"))));
    for (String type :
        List.of("draft.ready", "draft.insufficient_evidence", "draft.needs_human", "agent.failed"))
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM audit_events WHERE event_type=?", Integer.class, type));
  }

  @Test
  void agentProductContextUsesHistoricalSourceAndNeverAFutureProductCard() {
    var base = input("source-a", "商品来源");
    var source =
        new Contracts.Inbound(
            base.eventId(),
            base.shopId(),
            base.platformShopId(),
            base.connectorAccountId(),
            base.platformMessageId(),
            base.buyerRef(),
            "inbound",
            "system_hint",
            base.content(),
            base.occurredAt(),
            base.receivedAt(),
            "context_only",
            new Contracts.Media("product", "https://img.pddpic.com/a.jpg", "goods-a", "商品甲"));
    var first = inbox.receive(source, "pdd:fixture-shop:source-a");
    var question = receive("stock-question");
    var future =
        new Contracts.Inbound(
            "source-b-event",
            base.shopId(),
            base.platformShopId(),
            base.connectorAccountId(),
            "source-b",
            base.buyerRef(),
            "inbound",
            "system_hint",
            "另一次来源",
            base.occurredAt(),
            base.receivedAt(),
            "context_only",
            new Contracts.Media("product", "https://img.pddpic.com/b.jpg", "goods-b", "商品乙"));
    var last = inbox.receive(future, "pdd:fixture-shop:source-b");
    jdbc.update("UPDATE messages SET created_at=10 WHERE id=?", first.get("message_id"));
    jdbc.update("UPDATE messages SET created_at=20 WHERE id=?", question.get("message_id"));
    jdbc.update("UPDATE messages SET created_at=30 WHERE id=?", last.get("message_id"));
    var run =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList(
                "SELECT id FROM agent_runs WHERE message_id=?", question.get("message_id")));
    var product = (Map<?, ?>) runs.context(run.get("id").toString()).get("product_context");
    assertEquals("goods-a", product.get("goods_id"));
    assertEquals(first.get("message_id"), product.get("source_message_id"));
    assertFalse(product.containsKey("quantity"));
  }

  @Test
  void newerOrderCardSelectsProductForFollowupWithoutBorrowingFutureOrOtherBuyerCards() {
    var old = receive("order-product-old");
    jdbc.update(
        "UPDATE messages SET media_json=?,created_at=10 WHERE id=?",
        "{\"kind\":\"product\",\"goods_id\":\"old-goods\",\"title\":\"先前商品\"}",
        old.get("message_id"));
    var card = receive("order-product-card");
    String orderContent =
        "{\"order_id\":\"111111-111111111111111\",\"goods_id\":585807043618,\"goods_name\":\"订单商品\"}";
    jdbc.update(
        "UPDATE messages SET message_type='order_info',content=?,created_at=20 WHERE id=?",
        orderContent,
        card.get("message_id"));
    var question = receive("order-product-question");
    jdbc.update("UPDATE messages SET created_at=30 WHERE id=?", question.get("message_id"));
    var future = receive("order-product-future");
    jdbc.update(
        "UPDATE messages SET message_type='order_info',content=?,created_at=40 WHERE id=?",
        "{\"goods_id\":999999,\"goods_name\":\"未来商品\"}",
        future.get("message_id"));
    var other =
        inbox.receive(
            shopInput("fixture-shop", "other-order-product", "other-buyer"),
            "pdd:fixture-shop:other-order-product");
    jdbc.update(
        "UPDATE messages SET message_type='order_info',content=?,created_at=25 WHERE id=?",
        "{\"goods_id\":888888,\"goods_name\":\"其他买家商品\"}",
        other.get("message_id"));
    jdbc.update(
        "INSERT INTO"
            + " product_details(shop_id,goods_id,payload,category_path,sku_count,attributes_count,updated_at)"
            + " VALUES ('fixture-shop','585807043618',?,'[]',0,0,0)",
        "{\"goods_id\":\"585807043618\",\"goods_name\":\"订单商品\",\"description\":\"已同步参数\"}");
    var run =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList(
                "SELECT id FROM agent_runs WHERE message_id=?", question.get("message_id")));
    var context = runs.context(run.get("id").toString());
    var product = (Map<?, ?>) context.get("product_context");
    assertEquals("585807043618", product.get("goods_id"));
    assertEquals(card.get("message_id"), product.get("source_message_id"));
    assertFalse(product.containsKey("quantity"));
    assertEquals(
        "585807043618", ((Map<?, ?>) context.get("platform_product_facts")).get("goods_id"));
    assertEquals(
        "585807043618",
        ((Map<?, ?>) ((Map<?, ?>) context.get("question_contexts")).get(question.get("message_id")))
            .get("goods_id"));
    var cardRun =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList(
                "SELECT id FROM agent_runs WHERE message_id=?", card.get("message_id")));
    assertEquals(
        "585807043618",
        ((Map<?, ?>) runs.context(cardRun.get("id").toString()).get("product_context"))
            .get("goods_id"));

    jdbc.update(
        "UPDATE messages SET"
            + " message_type='system_hint',media_json=?,related_platform_message_id=?,created_at=35"
            + " WHERE id=?",
        "{\"kind\":\"product\",\"goods_id\":\"linked-goods\",\"title\":\"本题关联商品\"}",
        "order-product-question",
        future.get("message_id"));
    assertEquals(
        "linked-goods",
        ((Map<?, ?>) runs.context(run.get("id").toString()).get("product_context"))
            .get("goods_id"));
    jdbc.update(
        "UPDATE messages SET related_platform_message_id=NULL WHERE id=?",
        future.get("message_id"));

    jdbc.update(
        "UPDATE messages SET content=? WHERE id=?",
        "{\"goods_id\":{\"invalid\":123}}",
        card.get("message_id"));
    assertEquals(
        "old-goods",
        ((Map<?, ?>) runs.context(run.get("id").toString()).get("product_context"))
            .get("goods_id"));
  }

  @Test
  void linkedSourceAfterQuestionWinsOverPreviousProductAndRetainsPerQuestionFacts() {
    var base = input("300", "来源");
    var old =
        inbox.receive(
            new Contracts.Inbound(
                "old-source",
                base.shopId(),
                base.platformShopId(),
                base.connectorAccountId(),
                "300",
                base.buyerRef(),
                "inbound",
                "system_hint",
                "旧商品",
                base.occurredAt(),
                base.receivedAt(),
                "context_only",
                new Contracts.Media("product", null, "goods-old", "旧商品")),
            "pdd:fixture-shop:300");
    var question = receive("301");
    var source =
        new Contracts.Inbound(
            "linked-source",
            base.shopId(),
            base.platformShopId(),
            base.connectorAccountId(),
            "302",
            base.buyerRef(),
            "inbound",
            "system_hint",
            "当前来源",
            base.occurredAt(),
            base.receivedAt(),
            "context_only",
            new Contracts.Media("product", null, "goods-correct", "本题商品", "301"));
    var linked = inbox.receive(source, "pdd:fixture-shop:302");
    jdbc.update("UPDATE messages SET created_at=10 WHERE id=?", old.get("message_id"));
    jdbc.update("UPDATE messages SET created_at=20 WHERE id=?", question.get("message_id"));
    jdbc.update("UPDATE messages SET created_at=33 WHERE id=?", linked.get("message_id"));
    var row =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList(
                "SELECT id FROM agent_runs WHERE message_id=?", question.get("message_id")));
    jdbc.update("UPDATE agent_runs SET context_at=40 WHERE id=?", row.get("id"));
    String facts =
        store.toJson(
            Map.of("goods_id", "goods-correct", "description", "每件一张", "goods_name", "本题商品"));
    jdbc.update(
        "INSERT INTO"
            + " product_details(shop_id,goods_id,payload,category_path,sku_count,attributes_count,updated_at)"
            + " VALUES ('fixture-shop','goods-correct',?,'[]',0,0,0)",
        facts);
    var context = runs.context(row.get("id").toString());
    assertEquals("goods-correct", ((Map<?, ?>) context.get("product_context")).get("goods_id"));
    assertEquals(
        "goods-correct",
        ((Map<?, ?>) ((Map<?, ?>) context.get("question_contexts")).get(question.get("message_id")))
            .get("goods_id"));
    assertEquals("每件一张", ((Map<?, ?>) context.get("platform_product_facts")).get("description"));
    assertEquals(
        "每件一张",
        ((Map<?, ?>)
                ((Map<?, ?>) context.get("question_product_facts")).get(question.get("message_id")))
            .get("description"));
    jdbc.update("UPDATE agent_runs SET context_at=25 WHERE id=?", row.get("id"));
    assertEquals(
        "goods-old",
        ((Map<?, ?>) runs.context(row.get("id").toString()).get("product_context"))
            .get("goods_id"));
  }

  @Test
  void linkedBuyerProductCardIsContextForQuestionInsteadOfASecondConsultation() {
    var question = inbox.receive(input("51001", "这个是真的吗"), "pdd:fixture-shop:51001");
    var base = input("51002", "商品来源");
    var card =
        new Contracts.Inbound(
            "linked-buyer-card",
            base.shopId(),
            base.platformShopId(),
            base.connectorAccountId(),
            "51002",
            base.buyerRef(),
            "inbound",
            "goods_inquiry",
            "{\"goods_id\":123,\"goods_name\":\"本题商品\"}",
            base.occurredAt(),
            base.receivedAt(),
            "reply",
            new Contracts.Media(
                "product", "https://img.pddpic.com/current.jpg", "123", "本题商品", "51001"));
    var received = inbox.receive(card, "pdd:fixture-shop:51002");
    var generation = jobs.claim();
    assertNotNull(generation);
    var ctx = runs.context(generation.get("reference_id").toString());
    assertEquals(question.get("message_id"), ctx.get("message_id"));
    assertEquals("123", ((Map<?, ?>) ctx.get("product_context")).get("goods_id"));
    assertEquals(1, ((List<?>) ctx.get("pending_questions")).size());
    var history = (List<Map<String, Object>>) ctx.get("history");
    assertTrue(history.stream().noneMatch(m -> received.get("message_id").equals(m.get("id"))));
    var detail = conversations.detail(question.get("conversation_id").toString(), "reviewer");
    assertEquals(1, ((List<?>) detail.get("pending_questions")).size());
    var messages = (List<Map<String, Object>>) detail.get("messages");
    assertEquals(
        true,
        messages.stream()
            .filter(m -> received.get("message_id").equals(m.get("id")))
            .findFirst()
            .orElseThrow()
            .get("context_only"));
    int count = jdbc.queryForObject("SELECT count(*) FROM agent_runs", Integer.class);
    long revision = ((Number) detail.get("revision")).longValue();
    assertEquals(received, inbox.receive(card, "pdd:fixture-shop:51002"));
    assertEquals(count, jdbc.queryForObject("SELECT count(*) FROM agent_runs", Integer.class));
    assertEquals(
        revision,
        ((Number)
                conversations
                    .detail(question.get("conversation_id").toString(), "reviewer")
                    .get("revision"))
            .longValue());
  }

  @Test
  void linkedProductCardCannotBorrowOtherBuyersQuestionOrRestartAlreadyApprovedAnswer() {
    var d = draft("52001", "draft");
    approvals.decide(
        d.get("id").toString(), new Contracts.Decision("approve", 1, "已核实。"), "reviewer");
    int runsBefore = jdbc.queryForObject("SELECT count(*) FROM agent_runs", Integer.class);
    var base = input("52002", "商品来源");
    var card =
        new Contracts.Inbound(
            "approved-source-card",
            base.shopId(),
            base.platformShopId(),
            base.connectorAccountId(),
            "52002",
            base.buyerRef(),
            "inbound",
            "goods_inquiry",
            "{\"goods_id\":123,\"goods_name\":\"商品\"}",
            base.occurredAt(),
            base.receivedAt(),
            "reply",
            new Contracts.Media("product", null, "123", "商品", "52001"));
    inbox.receive(card, "pdd:fixture-shop:52002");
    assertEquals(runsBefore, jdbc.queryForObject("SELECT count(*) FROM agent_runs", Integer.class));
    var other =
        new Contracts.Inbound(
            "other-buyer-card",
            base.shopId(),
            base.platformShopId(),
            base.connectorAccountId(),
            "52003",
            "other-buyer",
            "inbound",
            "goods_inquiry",
            card.content(),
            base.occurredAt(),
            base.receivedAt(),
            "reply",
            card.media());
    var accepted = inbox.receive(other, "pdd:fixture-shop:52003");
    var detail = conversations.detail(accepted.get("conversation_id").toString(), "reviewer");
    assertEquals(1, ((List<?>) detail.get("pending_questions")).size());
    assertEquals(
        false, ((List<Map<String, Object>>) detail.get("messages")).get(0).get("context_only"));
  }

  @Test
  void enrichingOldTrackedCardDoesNotMarkItAnsweredOrLeaveItAsAPendingQuestion() {
    var question = inbox.receive(input("53001", "这个是真的吗"), "pdd:fixture-shop:53001");
    var base = input("53002", "商品来源");
    var original =
        new Contracts.Inbound(
            "legacy-card",
            base.shopId(),
            base.platformShopId(),
            base.connectorAccountId(),
            "53002",
            base.buyerRef(),
            "inbound",
            "goods_inquiry",
            "{\"goods_id\":123,\"goods_name\":\"新商品\"}",
            base.occurredAt(),
            base.receivedAt(),
            "reply",
            new Contracts.Media("product", null, "123", "新商品"));
    var card = inbox.receive(original, "pdd:fixture-shop:53002");
    assertEquals(
        2,
        ((List<?>)
                conversations
                    .detail(question.get("conversation_id").toString(), "reviewer")
                    .get("pending_questions"))
            .size());
    var enriched =
        new Contracts.Inbound(
            original.eventId(),
            original.shopId(),
            original.platformShopId(),
            original.connectorAccountId(),
            original.platformMessageId(),
            original.buyerRef(),
            original.direction(),
            original.messageType(),
            original.content(),
            original.occurredAt(),
            original.receivedAt(),
            original.action(),
            new Contracts.Media("product", null, "123", "新商品", "53001"));
    assertEquals(card, inbox.receive(enriched, "pdd:fixture-shop:53002"));
    var detail = conversations.detail(question.get("conversation_id").toString(), "reviewer");
    assertEquals(1, ((List<?>) detail.get("pending_questions")).size());
    assertNull(
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList(
                    "SELECT answered_at FROM reply_questions WHERE message_id=?",
                    card.get("message_id")))
            .get("answered_at"));
    runs.regenerate(question.get("message_id").toString(), "reviewer");
    var run =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList(
                "SELECT id FROM agent_runs WHERE message_id=? AND state='queued'",
                question.get("message_id")));
    assertEquals(
        1, ((List<?>) runs.context(run.get("id").toString()).get("pending_questions")).size());
    assertEquals(
        "123",
        ((Map<?, ?>) runs.context(run.get("id").toString()).get("product_context"))
            .get("goods_id"));
  }

  @Test
  void sourceArrivingAfterGenerationInvalidatesWrongContextAndQueuesOnlyOneRepair() {
    var draft = draft("401", "draft");
    var base = input("402", "来源补充");
    var source =
        new Contracts.Inbound(
            "late-source",
            base.shopId(),
            base.platformShopId(),
            base.connectorAccountId(),
            "402",
            base.buyerRef(),
            "inbound",
            "system_hint",
            base.content(),
            base.occurredAt(),
            base.receivedAt(),
            "context_only",
            new Contracts.Media("product", null, "correct-goods", "正确商品", "401"));
    inbox.receive(source, "pdd:fixture-shop:402");
    assertEquals(
        "outdated",
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT state FROM drafts WHERE id=?", draft.get("id")))
            .get("state"));
    var repair =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT id FROM agent_runs WHERE state='queued'"));
    assertEquals(
        "correct-goods",
        ((Map<?, ?>) runs.context(repair.get("id").toString()).get("product_context"))
            .get("goods_id"));
    inbox.receive(source, "pdd:fixture-shop:402");
    assertEquals(
        1,
        jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE state='queued'", Integer.class));
  }

  @Test
  void successfulSendLinksPlatformReceiptAndDeduplicatesEchoInEitherOrder() {
    for (boolean echoFirst : List.of(false, true)) {
      var draft = draft("read-question-" + echoFirst, "draft");
      String content = "已审批测试回复" + echoFirst;
      approvals.decide(
          draft.get("id").toString(), new Contracts.Decision("approve", 1, content), "reviewer");
      var command =
          com.pddcopilot.core.repository.DatabaseRows.required(
              jdbc.queryForList("SELECT id FROM send_commands WHERE draft_id=?", draft.get("id")));
      String platformId = echoFirst ? "900719925474099312346" : "900719925474099312345";
      var base = input(platformId, content);
      var echo =
          new Contracts.Inbound(
              base.eventId(),
              base.shopId(),
              base.platformShopId(),
              base.connectorAccountId(),
              platformId,
              base.buyerRef(),
              "outbound",
              "mall_cs",
              content,
              base.occurredAt(),
              base.receivedAt(),
              "context_only");
      if (echoFirst) inbox.receive(echo, "pdd:fixture-shop:" + platformId);
      sends.complete(
          command.get("id").toString(), Map.of("state", "sent", "platform_message_id", platformId));
      inbox.receive(echo, "pdd:fixture-shop:" + platformId);
      sends.complete(
          command.get("id").toString(), Map.of("state", "sent", "platform_message_id", platformId));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM messages WHERE platform_message_id=?",
              Integer.class,
              platformId));
      receipts.receive(
          new Contracts.ReadReceipt(
              "fixture-shop",
              "fixture-platform-shop",
              "fixture-account",
              "buyer-001",
              platformId,
              "100",
              OffsetDateTime.now()));
      var message =
          com.pddcopilot.core.repository.DatabaseRows.required(
              jdbc.queryForList("SELECT * FROM messages WHERE platform_message_id=?", platformId));
      assertEquals("read", ReadReceiptService.state(platformId, receipts.marker("buyer-001")));
      var rendered =
          (List<Map<String, Object>>)
              conversations
                  .detail(message.get("conversation_id").toString(), "reviewer")
                  .get("messages");
      assertEquals(
          "read",
          rendered.stream()
              .filter(m -> m.get("id").equals(message.get("id")))
              .findFirst()
              .orElseThrow()
              .get("buyer_read_state"));
    }
  }

  @Test
  void liveProductKnowledgeRequiresAnOwnedProductAndMatchingTopic() {
    jdbc.update("UPDATE shops SET mode='live' WHERE id=?", store.shopId());
    knowledge.saveProducts(
        store.shopId(),
        Map.of("shop_id", store.shopId(), "products", List.of(Map.of("goods_id", "owned-goods"))),
        "reviewer");
    for (String goods : List.of("", "foreign-goods")) {
      var error =
          assertThrows(
              ResponseStatusException.class,
              () ->
                  knowledge.publish(
                      store.shopId(),
                      new Contracts.Knowledge(
                          "bad-" + goods,
                          List.of(
                              Map.of(
                                  "source_id",
                                  "spec",
                                  "source_type",
                                  "product",
                                  "topics",
                                  List.of("spec"),
                                  "text",
                                  "已核实规格",
                                  "goods_id",
                                  goods))),
                      "reviewer"));
      assertEquals("KNOWLEDGE_PRODUCT_REQUIRED", error.getReason());
    }
    knowledge.publish(
        store.shopId(),
        new Contracts.Knowledge(
            "owned-version",
            List.of(
                Map.of(
                    "source_id",
                    "owned-spec",
                    "title",
                    "商品参数",
                    "source_type",
                    "product",
                    "topics",
                    List.of("spec"),
                    "goods_id",
                    "owned-goods",
                    "text",
                    "已核实的商品参数"))),
        "reviewer");
    var mismatch =
        assertThrows(
            ResponseStatusException.class,
            () ->
                knowledge.publish(
                    store.shopId(),
                    new Contracts.Knowledge(
                        "wrong-type",
                        List.of(
                            Map.of(
                                "source_id",
                                "wrong",
                                "source_type",
                                "shop_policy",
                                "topics",
                                List.of("spec"),
                                "text",
                                "错误类型"))),
                    "reviewer"));
    assertEquals("INVALID_KNOWLEDGE_TOPICS", mismatch.getReason());
  }

  @Test
  void resumeKeepsTheConversationRetiresLateResultsAndOnlyNewMessagesStartRuns() {
    var before = receive("before-handoff");
    String cid = before.get("conversation_id").toString();
    var run =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT * FROM agent_runs"));
    conversations.transfer(cid, "reviewer");
    receive("while-human");
    long revision = ((Number) store.scopedConversation(cid, false).get("revision")).longValue();
    assertEquals(
        "CONVERSATION_VERSION_CONFLICT",
        assertThrows(
                ResponseStatusException.class,
                () -> conversations.resume(cid, revision - 1, "reviewer"))
            .getReason());
    var resumed = conversations.resume(cid, revision, "reviewer");
    assertEquals(resumed, conversations.resume(cid, revision, "reviewer"));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM agent_runs", Integer.class));
    tx.executeWithoutResult(
        status ->
            runs.complete(
                run.get("id").toString(),
                Map.of(
                    "run_id",
                    run.get("id"),
                    "message_id",
                    before.get("message_id"),
                    "outcome",
                    "draft",
                    "content",
                    "过期结果",
                    "citations",
                    List.of(),
                    "risk_codes",
                    List.of())));
    assertEquals(
        "superseded",
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT state FROM agent_runs WHERE id=?", run.get("id")))
            .get("state"));
    receive("after-resume");
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM agent_runs", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM send_commands", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE event_type='conversation.resumed'",
            Integer.class));
  }

  @Test
  void workbenchReadsArePerUserIdempotentAndDoNotAcknowledgeNewerMessages() {
    var first = receive("view-first");
    var second = receive("view-second");
    String cid = first.get("conversation_id").toString();
    conversations.markRead(cid, List.of(first.get("message_id").toString()), "reviewer");
    conversations.markRead(cid, List.of(first.get("message_id").toString()), "reviewer");
    assertEquals(
        1L, ((Number) conversations.list("reviewer").get(0).get("unread_count")).longValue());
    assertEquals(
        2L, ((Number) conversations.list("other-reviewer").get(0).get("unread_count")).longValue());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM message_reads", Integer.class));
    var result =
        conversations.markRead(cid, List.of(second.get("message_id").toString()), "reviewer");
    assertEquals(false, result.get("platform_read_synced"));
    assertEquals(0L, ((Number) result.get("unread_count")).longValue());
    assertThrows(
        ResponseStatusException.class,
        () -> conversations.markRead(cid, List.of("foreign-or-missing-message"), "reviewer"));
  }

  @Test
  void buyerReceiptsMergeOutOfOrderWithoutCreatingMessagesOrReversingReadState() {
    receipts.receive(
        new Contracts.ReadReceipt(
            "fixture-shop",
            "fixture-platform-shop",
            "fixture-account",
            "buyer-001",
            "200",
            "100",
            OffsetDateTime.now()));
    receipts.receive(
        new Contracts.ReadReceipt(
            "fixture-shop",
            "fixture-platform-shop",
            "fixture-account",
            "buyer-001",
            "150",
            "90",
            OffsetDateTime.now().minusMinutes(1)));
    var marker = receipts.marker("buyer-001");
    assertEquals("200", marker.get("user_last_read"));
    assertEquals("unknown", ReadReceiptService.state("100", marker));
    assertEquals("read", ReadReceiptService.state("180", marker));
    assertEquals("unread", ReadReceiptService.state("201", marker));
    assertEquals("unknown", ReadReceiptService.state("non-numeric-id", marker));
    assertEquals("unknown", ReadReceiptService.state(null, marker));
    assertEquals("unknown", ReadReceiptService.state("180", null));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM agent_runs", Integer.class));
    assertThrows(
        ResponseStatusException.class,
        () ->
            receipts.receive(
                new Contracts.ReadReceipt(
                    "fixture-shop",
                    "another-shop",
                    "another-account",
                    "buyer-001",
                    "900",
                    "100",
                    OffsetDateTime.now())));
  }

  @Test
  void unresolvedSendPreventsResumingAndOldFailedSendStaysRetiredAfterResume() {
    var d = draft("resume-unknown", "draft");
    approvals.decide(
        d.get("id").toString(), new Contracts.Decision("approve", 1, "审批原文"), "reviewer");
    var command =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT * FROM send_commands"));
    tx.executeWithoutResult(
        status -> sends.complete(command.get("id").toString(), Map.of("state", "send_unknown")));
    String cid =
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT id FROM conversations"))
            .get("id")
            .toString();
    conversations.transfer(cid, "reviewer");
    long revision = ((Number) store.scopedConversation(cid, false).get("revision")).longValue();
    assertEquals(
        "SEND_RESULT_UNRESOLVED",
        assertThrows(
                ResponseStatusException.class,
                () -> conversations.resume(cid, revision, "reviewer"))
            .getReason());
    tx.executeWithoutResult(
        status -> sends.complete(command.get("id").toString(), Map.of("state", "send_failed")));
    // A late failure can update the draft after handoff. Its old approval revision
    // must still stay retired when the conversation is resumed.
    conversations.resume(cid, revision, "reviewer");
    assertEquals(
        "SEND_NOT_RETRYABLE",
        assertThrows(
                ResponseStatusException.class,
                () -> sends.retry(command.get("id").toString(), "reviewer"))
            .getReason());
  }

  @Test
  void mediaEnrichmentKeepsMessageIdentityAndCannotOverwriteKnownMedia() {
    var original = input("media-id", "原始咨询");
    var saved = inbox.receive(original, "pdd:fixture-shop:media-id");
    var media =
        new Contracts.Media("product", "https://img.pddpic.com/product.jpg", "goods-1", "测试商品");
    var enriched =
        new Contracts.Inbound(
            original.eventId(),
            original.shopId(),
            original.platformShopId(),
            original.connectorAccountId(),
            original.platformMessageId(),
            original.buyerRef(),
            original.direction(),
            original.messageType(),
            original.content(),
            original.occurredAt(),
            original.receivedAt(),
            original.action(),
            media);
    assertEquals(saved, inbox.receive(enriched, "pdd:fixture-shop:media-id"));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM agent_runs", Integer.class));
    assertEquals(
        1L,
        ((Number)
                com.pddcopilot.core.repository.DatabaseRows.required(
                        jdbc.queryForList("SELECT revision FROM conversations"))
                    .get("revision"))
            .longValue());
    var detail = conversations.detail(saved.get("conversation_id").toString(), "reviewer");
    var messages = (java.util.List<Map<String, Object>>) detail.get("messages");
    assertEquals(
        "https://img.pddpic.com/product.jpg",
        ((Map<?, ?>) messages.get(0).get("media")).get("image_url"));
    var other =
        new Contracts.Inbound(
            original.eventId(),
            original.shopId(),
            original.platformShopId(),
            original.connectorAccountId(),
            original.platformMessageId(),
            original.buyerRef(),
            original.direction(),
            original.messageType(),
            original.content(),
            original.occurredAt(),
            original.receivedAt(),
            original.action(),
            new Contracts.Media("product", "https://img.pddpic.com/other.jpg", "goods-2", "其他商品"));
    assertEquals(
        "IDEMPOTENCY_MEDIA_CONFLICT",
        assertThrows(
                ResponseStatusException.class,
                () -> inbox.receive(other, "pdd:fixture-shop:media-id"))
            .getReason());
  }

  @Test
  void realReceiveOnlyWorkspaceRejectsApprovalBeforeCreatingSendCommand() {
    var draft = draft("real-receive-only", "draft");
    doReturn(false).when(sendPolicy).shopEnabled();
    var error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                approvals.decide(
                    draft.get("id").toString(),
                    new Contracts.Decision("approve", 1, "人工确认内容"),
                    "reviewer"));
    assertEquals("LIVE_SEND_NOT_ENABLED", error.getReason());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM decisions", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM send_commands", Integer.class));
    assertEquals(false, connections.shops().get(0).get("send_enabled"));
  }

  @Test
  void platformFailureDiagnosticIsPersistedAndDoesNotClaimOnline() {
    connections.update(
        "fixture-shop",
        new Contracts.Connection("test-epoch", 2, "failed", "WS_PROTOCOL_UNSUPPORTED"));
    var shop = connections.shops().get(0);
    assertEquals("failed", shop.get("connection_state"));
    assertEquals("WS_PROTOCOL_UNSUPPORTED", shop.get("connection_error_code"));
    connections.update("fixture-shop", new Contracts.Connection("test-epoch", 3, "online"));
    assertNull(connections.shops().get(0).get("connection_error_code"));
  }

  @Test
  void duplicateInboundIsDurableAndPayloadConflictIsRejected() {
    var first = receive("same");
    assertEquals(first, receive("same"));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM messages", Integer.class));
    assertThrows(
        ResponseStatusException.class,
        () -> inbox.receive(input("same", "修改内容"), "pdd:fixture-shop:same"));
  }

  @Test
  void concurrentDeliveryCreatesOneMessageAndOneRun() throws Exception {
    var pool = Executors.newFixedThreadPool(6);
    try {
      var tasks = new ArrayList<Callable<Map<String, Object>>>();
      for (int i = 0; i < 12; i++) tasks.add(() -> receive("concurrent"));
      var results = pool.invokeAll(tasks);
      String mid = (String) results.get(0).get().get("message_id");
      for (var result : results) assertEquals(mid, result.get().get("message_id"));
      assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs", Integer.class));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void approvalRetryReturnsOneCommandAndFinalContentIsImmutable() {
    var d = draft("approve", "draft");
    var decision = new Contracts.Decision("approve", 1, "人工编辑后的回复");
    var first = approvals.decide((String) d.get("id"), decision, "reviewer");
    assertEquals(first, approvals.decide((String) d.get("id"), decision, "reviewer"));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM send_commands", Integer.class));
    assertEquals(
        "人工编辑后的回复",
        sends
            .command(
                (String)
                    com.pddcopilot.core.repository.DatabaseRows.required(
                            jdbc.queryForList("SELECT id FROM send_commands"))
                        .get("id"))
            .get("final_content"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            approvals.decide(
                (String) d.get("id"), new Contracts.Decision("approve", 1, "篡改"), "reviewer"));
  }

  @Test
  void prohibitedEditedAndManualRepliesNeverCreateSendCommands() {
    var d = draft("unsafe-edit", "draft");
    var failure =
        assertThrows(
            ResponseStatusException.class,
            () ->
                approvals.decide(
                    d.get("id").toString(),
                    new Contracts.Decision("approve", 1, "加我微信私下付款"),
                    "reviewer"));
    assertEquals("REPLY_CONTENT_PROHIBITED", failure.getReason());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM decisions", Integer.class));
    String cid =
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT id FROM conversations"))
            .get("id")
            .toString();
    conversations.transfer(cid, "reviewer");
    long revision = ((Number) conversations.detail(cid, "reviewer").get("revision")).longValue();
    var manualFailure =
        assertThrows(
            ResponseStatusException.class,
            () ->
                manual.submit(
                    cid,
                    new Contracts.ManualReply(UUID.randomUUID().toString(), revision, "五星好评返现五元"),
                    "reviewer"));
    assertEquals("REPLY_CONTENT_PROHIBITED", manualFailure.getReason());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM manual_replies", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM send_commands", Integer.class));
  }

  @Test
  void inboundMessageInvalidatesOldDraftAndWrongRevisionConflicts() {
    var d = draft("old", "draft");
    receive("new");
    var ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                approvals.decide(
                    (String) d.get("id"), new Contracts.Decision("approve", 1, "回复"), "reviewer"));
    assertEquals(409, ex.getStatusCode().value());
    assertEquals(
        "outdated",
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT * FROM drafts WHERE id=?", d.get("id")))
            .get("state"));
  }

  @Test
  void offlineAndRiskDraftCannotBeApproved() {
    var d = draft("risk", "needs_human");
    assertThrows(
        ResponseStatusException.class,
        () ->
            approvals.decide(
                (String) d.get("id"), new Contracts.Decision("approve", 1, "回复"), "reviewer"));
    approvals.decide((String) d.get("id"), new Contracts.Decision("transfer", 1, ""), "reviewer");
    assertEquals(
        "transferred",
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT * FROM conversations"))
            .get("state"));
    var other = input("offline", "什么时候发货？");
    // New conversation to exercise disconnection independently of a transferred conversation.
    jdbc.update("UPDATE conversations SET state='open'");
    var safe = draft("safe", "draft");
    connections.update("fixture-shop", new Contracts.Connection("test-epoch", 2, "disconnected"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            approvals.decide(
                (String) safe.get("id"), new Contracts.Decision("approve", 1, "回复"), "reviewer"));
  }

  @Test
  void missingPlatformIdNeverStartsAgent() {
    var i = input(null, "什么时候发货？");
    inbox.receive(i, "event:fixture-shop:" + i.eventId());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs", Integer.class));
  }

  @Test
  void documentPreviewDoesNotPublishAndRequiresShopAccess() {
    when(agent.previewDocument(any()))
        .thenReturn(Map.of("filename", "rules.txt", "chunks", List.of()));
    int before = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_versions", Integer.class);
    var input = Map.of("filename", "rules.txt", "content_base64", "SGVsbG8=");
    var ok =
        BrowserTestClient.login(http, "reviewer", "test-reviewer-password-123")
            .postForEntity("/api/v1/shops/fixture-shop/knowledge/preview", input, Map.class);
    assertEquals(200, ok.getStatusCode().value());
    assertEquals(
        before, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_versions", Integer.class));
    var forbidden =
        BrowserTestClient.login(http, "reviewer", "test-reviewer-password-123")
            .postForEntity("/api/v1/shops/other-shop/knowledge/preview", input, Map.class);
    assertEquals(403, forbidden.getStatusCode().value());
    verify(agent, times(1)).previewDocument(any());
  }

  @Test
  void documentPublicationPreservesSourcesAndRejectsStaleBaseVersion() {
    knowledge.publish(
        "fixture-shop",
        new Contracts.Knowledge(
            "fixture-document-base",
            List.of(
                Map.of(
                    "source_id", "existing-policy",
                    "source_type", "shop_policy",
                    "topics", List.of("shipping"),
                    "text", "Fixture policy."))),
        "reviewer");
    String base =
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT knowledge_version FROM shops WHERE id='fixture-shop'"))
            .get("knowledge_version")
            .toString();
    var entry =
        Map.<String, Object>of(
            "source_id",
            "doc-test-1",
            "source_type",
            "shop_policy",
            "topics",
            List.of("shipping"),
            "text",
            "48小时发货",
            "document_name",
            "发货.txt",
            "document_sha256",
            "a".repeat(64),
            "source_location",
            "第 1 段");
    knowledge.publish(
        "fixture-shop", new Contracts.Knowledge("document-v1", List.of(entry), base), "reviewer");
    assertTrue(
        knowledge.versions("fixture-shop").stream()
            .filter(v -> "document-v1".equals(v.get("version_label")))
            .findFirst()
            .orElseThrow()
            .get("snapshot")
            .toString()
            .contains("发货.txt"));
    var error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                knowledge.publish(
                    "fixture-shop",
                    new Contracts.Knowledge("stale-v2", List.of(entry), base),
                    "reviewer"));
    assertEquals(409, error.getStatusCode().value());
    assertEquals("KNOWLEDGE_VERSION_CONFLICT", error.getReason());
  }

  @Test
  void responseGuidelinesAreStoreWideAndCannotBeMixedWithFactTopics() {
    var entry =
        new HashMap<String, Object>(
            Map.of(
                "source_id",
                "tone",
                "source_type",
                "shop_policy",
                "topics",
                List.of("response_guidelines"),
                "text",
                "称呼使用您好，回答简洁。"));
    knowledge.publish(
        "fixture-shop", new Contracts.Knowledge("guidelines-v1", List.of(entry)), "reviewer");
    assertTrue(
        knowledge
            .versions("fixture-shop")
            .get(0)
            .get("snapshot")
            .toString()
            .contains("response_guidelines"));
    entry.put("topics", List.of("response_guidelines", "shipping"));
    assertEquals(
        422,
        assertThrows(
                ResponseStatusException.class,
                () ->
                    knowledge.publish(
                        "fixture-shop",
                        new Contracts.Knowledge("mixed-v1", List.of(entry)),
                        "reviewer"))
            .getStatusCode()
            .value());
    entry.put("topics", List.of("response_guidelines"));
    entry.put("source_type", "product");
    entry.put("goods_id", "fixture-goods-1");
    assertEquals(
        422,
        assertThrows(
                ResponseStatusException.class,
                () ->
                    knowledge.publish(
                        "fixture-shop",
                        new Contracts.Knowledge("product-guideline", List.of(entry)),
                        "reviewer"))
            .getStatusCode()
            .value());
  }

  @Test
  void commonAnswersCanBePublishedWithoutProductBindingButNotAsResponseGuidelines() {
    var entry =
        new HashMap<String, Object>(
            Map.of(
                "source_id",
                "authenticity-faq",
                "source_type",
                "shop_policy",
                "topics",
                List.of("faq"),
                "text",
                "问：保真吗？答：先核对具体商品的授权资料。"));
    knowledge.publish(
        "fixture-shop", new Contracts.Knowledge("faq-v1", List.of(entry)), "reviewer");
    assertTrue(
        knowledge.versions("fixture-shop").get(0).get("snapshot").toString().contains("保真吗"));
    entry.put("topics", List.of("faq", "response_guidelines"));
    var error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                knowledge.publish(
                    "fixture-shop",
                    new Contracts.Knowledge("mixed-faq", List.of(entry)),
                    "reviewer"));
    assertEquals("INVALID_KNOWLEDGE_TOPICS", error.getReason());
  }

  @Test
  void removingLastKnowledgeEntryPublishesEmptySnapshotWithoutErasingHistory() {
    knowledge.publish(
        "fixture-shop",
        new Contracts.Knowledge(
            "fixture-kb-before-removal",
            List.of(
                Map.of(
                    "source_id", "fixture-policy",
                    "source_type", "shop_policy",
                    "topics", List.of("shipping"),
                    "text", "Fixture policy before removal."))),
        "reviewer");
    var old =
        knowledge.versions("fixture-shop").stream()
            .filter(v -> Boolean.TRUE.equals(v.get("active")))
            .findFirst()
            .orElseThrow();
    String base = old.get("id").toString();
    String snapshot = old.get("snapshot").toString();
    var client = BrowserTestClient.login(http, "reviewer", "test-reviewer-password-123");
    var missingBase =
        client.postForEntity(
            "/api/v1/shops/fixture-shop/knowledge",
            Map.of("version", "empty-without-base", "entries", List.of()),
            Map.class);
    assertEquals(409, missingBase.getStatusCode().value());
    var cleared =
        client.postForEntity(
            "/api/v1/shops/fixture-shop/knowledge",
            Map.of("version", "removed-all", "entries", List.of(), "base_version", base),
            Map.class);
    assertEquals(200, cleared.getStatusCode().value());
    String current = cleared.getBody().get("knowledge_version").toString();
    assertNotEquals(base, current);
    var versions = knowledge.versions("fixture-shop");
    assertEquals(
        "[]",
        versions.stream()
            .filter(v -> current.equals(v.get("id")))
            .findFirst()
            .orElseThrow()
            .get("snapshot"));
    assertEquals(
        snapshot,
        versions.stream()
            .filter(v -> base.equals(v.get("id")))
            .findFirst()
            .orElseThrow()
            .get("snapshot"));
    assertTrue(
        Boolean.TRUE.equals(
            versions.stream()
                .filter(v -> current.equals(v.get("id")))
                .findFirst()
                .orElseThrow()
                .get("active")));
    var stale =
        client.postForEntity(
            "/api/v1/shops/fixture-shop/knowledge",
            Map.of("version", "stale-removal", "entries", List.of(), "base_version", base),
            Map.class);
    assertEquals(409, stale.getStatusCode().value());
    var foreign =
        client.postForEntity(
            "/api/v1/shops/other-shop/knowledge",
            Map.of("version", "foreign-removal", "entries", List.of(), "base_version", ""),
            Map.class);
    assertEquals(403, foreign.getStatusCode().value());
    var missingEntries =
        client.postForEntity(
            "/api/v1/shops/fixture-shop/knowledge",
            Map.of("version", "missing-entries", "base_version", current),
            Map.class);
    assertEquals(400, missingEntries.getStatusCode().value());
    assertEquals(versions.size(), knowledge.versions("fixture-shop").size());
  }

  @Test
  void knowledgeVersionCannotBeOverwritten() {
    knowledge.publish(
        "fixture-shop",
        new Contracts.Knowledge(
            "fixture-kb-v1",
            List.of(
                Map.of(
                    "source_id", "initial-policy",
                    "source_type", "shop_policy",
                    "topics", List.of("shipping"),
                    "text", "Fixture shipping policy."))),
        "reviewer");
    assertThrows(
        ResponseStatusException.class,
        () ->
            knowledge.publish(
                "fixture-shop",
                new Contracts.Knowledge(
                    "fixture-kb-v1",
                    List.of(
                        Map.of(
                            "source_id",
                            "x",
                            "source_type",
                            "shop_policy",
                            "topics",
                            List.of("shipping"),
                            "text",
                            "新内容"))),
                "reviewer"));
  }

  @Test
  void uncertainSendCannotBeRetriedButKnownFailureCan() {
    var d = draft("uncertain", "draft");
    approvals.decide((String) d.get("id"), new Contracts.Decision("approve", 1, "回复"), "reviewer");
    String command =
        (String)
            com.pddcopilot.core.repository.DatabaseRows.required(
                    jdbc.queryForList("SELECT id FROM send_commands"))
                .get("id");
    tx.executeWithoutResult(status -> sends.complete(command, Map.of("state", "send_unknown")));
    assertThrows(ResponseStatusException.class, () -> sends.retry(command, "reviewer"));
    tx.executeWithoutResult(status -> sends.complete(command, Map.of("state", "send_failed")));
    sends.retry(command, "reviewer");
    assertEquals(2, ((Number) sends.command(command).get("attempt")).intValue());
  }

  @Test
  void httpAuthenticationAndCrossShopScopeAreEnforced() {
    assertEquals(401, http.getForEntity("/api/v1/bootstrap", String.class).getStatusCode().value());
    assertEquals(
        200,
        BrowserTestClient.login(http, "reviewer", "test-reviewer-password-123")
            .getForEntity("/api/v1/bootstrap", String.class)
            .getStatusCode()
            .value());
    assertEquals(
        401,
        http.getForEntity("/internal/v1/send-commands/nonexistent", String.class)
            .getStatusCode()
            .value());
    assertThrows(ResponseStatusException.class, () -> store.requireShopAccess("other-shop"));
  }

  @Test
  void expiredJobLeaseCanBeReclaimedWithoutAcceptingOldWorkerResult() {
    receive("lease");
    var first = jobs.claim();
    assertNotNull(first);
    jdbc.update("UPDATE jobs SET locked_until=0 WHERE id=?", first.get("id"));
    var second = jobs.claim();
    assertNotNull(second);
    assertEquals(first.get("id"), second.get("id"));
    tx.executeWithoutResult(
        status -> {
          assertFalse(jobs.owns(first));
          assertTrue(jobs.owns(second));
        });
  }

  @Test
  void humanTakeoverPreventsRetryingAnEarlierFailedCommand() {
    var d = draft("takeover", "draft");
    approvals.decide((String) d.get("id"), new Contracts.Decision("approve", 1, "回复"), "reviewer");
    String command =
        (String)
            com.pddcopilot.core.repository.DatabaseRows.required(
                    jdbc.queryForList("SELECT id FROM send_commands"))
                .get("id");
    String cid = (String) sends.command(command).get("conversation_id");
    assertThrows(ResponseStatusException.class, () -> conversations.transfer(cid, "reviewer"));
    tx.executeWithoutResult(status -> sends.complete(command, Map.of("state", "send_failed")));
    conversations.transfer(cid, "reviewer");
    assertThrows(ResponseStatusException.class, () -> sends.retry(command, "reviewer"));
  }

  @Test
  void platformSourceHintDoesNotSupersedeReplyAndCannotStartItsOwnRun() {
    var original = draft("source-hint-base", "draft");
    long revision =
        ((Number)
                com.pddcopilot.core.repository.DatabaseRows.required(
                        jdbc.queryForList("SELECT revision FROM conversations"))
                    .get("revision"))
            .longValue();
    var hint =
        new Contracts.Inbound(
            "source-hint-event",
            "fixture-shop",
            "fixture-platform-shop",
            "fixture-account",
            "source-hint-message",
            "buyer-001",
            "inbound",
            "system_hint",
            "平台来源提示",
            null,
            OffsetDateTime.now(),
            "context_only");
    var saved = inbox.receive(hint, "pdd:fixture-shop:source-hint-message");
    assertEquals(
        revision,
        ((Number)
                com.pddcopilot.core.repository.DatabaseRows.required(
                        jdbc.queryForList("SELECT revision FROM conversations"))
                    .get("revision"))
            .longValue());
    assertEquals(
        "waiting_human",
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT state FROM drafts WHERE id=?", original.get("id")))
            .get("state"));
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> runs.regenerate(saved.get("message_id").toString(), "reviewer"));
    assertEquals("MESSAGE_NOT_GENERATABLE", error.getReason());
  }

  @Test
  void ignoredOperationalEventDoesNotInvalidateWaitingDraft() {
    var d = draft("ignore-base", "draft");
    var event =
        new Contracts.Inbound(
            "read-event",
            "fixture-shop",
            "fixture-platform-shop",
            "fixture-account",
            "read-mid",
            "buyer-001",
            "system",
            "system_status",
            "",
            null,
            OffsetDateTime.now(),
            "ignore");
    inbox.receive(event, "pdd:fixture-shop:read-mid");
    assertEquals(
        "waiting_human",
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT state FROM drafts WHERE id=?", d.get("id")))
            .get("state"));
  }

  @Test
  void workbenchExposesModelExecutionMetadataAndChronologicalHistory() {
    var first = receive("history-first");
    var second = receive("history-second");
    jdbc.update("UPDATE messages SET created_at=1 WHERE id=?", first.get("message_id"));
    jdbc.update("UPDATE messages SET created_at=2 WHERE id=?", second.get("message_id"));
    var run =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList(
                "SELECT * FROM agent_runs WHERE message_id=?", second.get("message_id")));
    var result = new LinkedHashMap<String, Object>();
    result.put("run_id", run.get("id"));
    result.put("message_id", second.get("message_id"));
    result.put("outcome", "draft");
    result.put("content", "您好，48小时内发货。");
    result.put("citations", List.of());
    result.put("risk_codes", List.of());
    result.put("engine", "llm_agent");
    result.put("model", "fixture-tool-model");
    result.put("model_calls", 3);
    result.put(
        "tool_trace",
        List.of(
            Map.of("name", "search_customer_service_knowledge", "status", "ok", "duration_ms", 2)));
    result.put("private_prompt", "must-not-be-exposed");
    tx.executeWithoutResult(status -> runs.complete((String) run.get("id"), result));
    var detail = conversations.detail((String) second.get("conversation_id"), "reviewer");
    @SuppressWarnings("unchecked")
    var exposed =
        ((List<Map<String, Object>>) detail.get("agent_runs"))
            .stream().filter(r -> run.get("id").equals(r.get("id"))).findFirst().orElseThrow();
    assertEquals("llm_agent", exposed.get("engine"));
    assertEquals(3, exposed.get("model_calls"));
    assertFalse(exposed.containsKey("result"));
    assertFalse(exposed.containsKey("private_prompt"));
    @SuppressWarnings("unchecked")
    var history = (List<Map<String, Object>>) runs.context((String) run.get("id")).get("history");
    assertEquals(
        List.of(first.get("message_id"), second.get("message_id")),
        history.stream().map(m -> m.get("id")).toList());
  }

  @Test
  void administratorCanTestActivateAndDisableEncryptedConfiguration() {
    String key = "test-provider-secret-not-real-1234";
    var admin = BrowserTestClient.login(http, "reviewer", "test-reviewer-password-123");
    var saved =
        admin.postForEntity(
            "/api/v1/admin/model-config",
            new ModelConfigurationInput(0, "https://model.example/v1", "fixture-model", key, 4, 60),
            Map.class);
    assertEquals(200, saved.getStatusCode().value());
    assertEquals("no-store", saved.getHeaders().getFirst("Cache-Control"));
    assertFalse(saved.getBody().toString().contains(key));
    var row =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT * FROM model_configurations"));
    assertNotEquals(key, row.get("encrypted_api_key"));
    assertFalse(row.get("encrypted_api_key").toString().contains(key));
    String id = row.get("id").toString();
    assertEquals(
        409,
        admin
            .postForEntity(
                "/api/v1/admin/model-config/" + id + "/activate",
                Map.of("revision", 1),
                String.class)
            .getStatusCode()
            .value());
    when(agent.testModel(anyMap())).thenReturn(Map.of("passed", true));
    assertEquals(
        200,
        admin
            .postForEntity(
                "/api/v1/admin/model-config/" + id + "/test", Map.of("revision", 1), String.class)
            .getStatusCode()
            .value());
    assertEquals(
        200,
        admin
            .postForEntity(
                "/api/v1/admin/model-config/" + id + "/activate",
                Map.of("revision", 1),
                String.class)
            .getStatusCode()
            .value());
    assertEquals(key, models.effective("fixture-shop").get("api_key"));
    assertEquals(id, models.effective("fixture-shop").get("configuration_id"));
    assertFalse(admin.getForObject("/api/v1/admin/model-config", String.class).contains(key));
    assertEquals(
        200,
        admin
            .postForEntity(
                "/api/v1/admin/model-config/disable", Map.of("revision", 2), String.class)
            .getStatusCode()
            .value());
    assertEquals(Map.of("managed", true, "enabled", false), models.effective("fixture-shop"));
    assertEquals(
        401,
        http.getForEntity("/internal/v1/shops/fixture-shop/model-config", String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void invitedReviewerCannotManageOrReadModelConfiguration() {
    users.register(
        "ordinary-reviewer",
        "reviewer-password-for-test",
        workspaces.invite("reviewer").get("invite_code").toString());
    var reviewer = BrowserTestClient.login(http, "ordinary-reviewer", "reviewer-password-for-test");
    assertEquals(
        200, reviewer.getForEntity("/api/v1/bootstrap", String.class).getStatusCode().value());
    assertEquals(
        403,
        reviewer.getForEntity("/api/v1/admin/model-config", String.class).getStatusCode().value());
    for (String path :
        List.of(
            "/api/v1/admin/model-config",
            "/api/v1/admin/model-config/disable",
            "/api/v1/admin/model-config/id/test",
            "/api/v1/admin/model-config/id/activate")) {
      assertEquals(
          403,
          reviewer
              .postForEntity(path, Map.of("revision", 0), String.class)
              .getStatusCode()
              .value());
    }
  }

  @Test
  void staleConfigurationDoesNotOverwriteAndBlankKeyOnlyRetainsSameOrigin() {
    models.save(
        new ModelConfigurationInput(
            0, "https://model.example/v1", "model-a", "test-key-long-enough", 4, 60),
        "reviewer");
    assertThrows(
        ResponseStatusException.class,
        () ->
            models.save(
                new ModelConfigurationInput(
                    0, "https://model.example/v1", "stale", "another-test-key", 4, 60),
                "reviewer"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            models.save(
                new ModelConfigurationInput(
                    1, "https://another.example/v1", "model-b", null, 4, 60),
                "reviewer"));
    String first =
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT encrypted_api_key FROM model_configurations"))
            .get("encrypted_api_key")
            .toString();
    models.save(
        new ModelConfigurationInput(1, "https://model.example/v1", "model-b", null, 4, 60),
        "reviewer");
    var latest =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT * FROM model_configurations WHERE version=2"));
    assertNotEquals(first, latest.get("encrypted_api_key"));
    assertEquals("untested", latest.get("test_state"));
  }

  @Test
  void outdatedModelTestCannotMarkNewVersionAsPassed() {
    models.save(
        new ModelConfigurationInput(
            0, "https://model.example/v1", "first", "test-key-long-enough", 4, 60),
        "reviewer");
    String id =
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT id FROM model_configurations"))
            .get("id")
            .toString();
    when(agent.testModel(anyMap()))
        .thenAnswer(
            invocation -> {
              models.save(
                  new ModelConfigurationInput(1, "https://model.example/v1", "second", null, 4, 60),
                  "reviewer");
              return Map.of("passed", true);
            });
    var error = assertThrows(ResponseStatusException.class, () -> models.test(id, 1, "reviewer"));
    assertEquals(409, error.getStatusCode().value());
    assertEquals(2L, jdbc.queryForObject("SELECT revision FROM model_settings", Long.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM model_configurations WHERE test_state='passed'", Integer.class));
  }

  @Test
  void failedModelTestCannotEnableAndDoesNotExposeProviderError() {
    models.save(
        new ModelConfigurationInput(
            0, "https://model.example/v1", "model", "test-key-long-enough", 4, 60),
        "reviewer");
    String id =
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT id FROM model_configurations"))
            .get("id")
            .toString();
    when(agent.testModel(anyMap()))
        .thenThrow(new RuntimeException("test-key-long-enough raw provider body"));
    var result = models.test(id, 1, "reviewer");
    assertFalse(result.toString().contains("test-key-long-enough"));
    assertEquals(
        "failed",
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT test_state FROM model_configurations"))
            .get("test_state"));
    assertThrows(ResponseStatusException.class, () -> models.activate(id, 1, "reviewer"));
  }

  @Test
  void encryptedModelKeyIsBoundToConfigurationAndRequiresDeploymentKey() {
    var cipher = new ModelSecretCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    String encrypted = cipher.encrypt("test-key-long-enough", "shop:version-one");
    assertEquals("test-key-long-enough", cipher.decrypt(encrypted, "shop:version-one"));
    assertThrows(
        ResponseStatusException.class, () -> cipher.decrypt(encrypted, "another-shop:version-one"));
    assertThrows(
        ResponseStatusException.class, () -> new ModelSecretCipher("").encrypt("secret", "shop"));
  }

  @Test
  void invitedAccountIsStoredHashedAndCanLogin() {
    var input =
        Map.of(
            "username",
            "new-reviewer",
            "password",
            "test-password-for-user",
            "invite_code",
            workspaces.invite("reviewer").get("invite_code").toString());
    assertEquals(
        201,
        BrowserTestClient.login(http, "reviewer", "test-reviewer-password-123")
            .postForEntity("/api/v1/auth/register", input, String.class)
            .getStatusCode()
            .value());
    var user =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT * FROM app_users WHERE username=?", "new-reviewer"));
    assertNotEquals("test-password-for-user", user.get("password_hash"));
    assertTrue(
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
            .matches("test-password-for-user", (String) user.get("password_hash")));
    assertEquals("REVIEWER", user.get("role"));
    assertEquals(
        200,
        BrowserTestClient.login(http, "new-reviewer", "test-password-for-user")
            .getForEntity("/api/v1/bootstrap", String.class)
            .getStatusCode()
            .value());
    assertEquals(
        409,
        BrowserTestClient.login(http, "reviewer", "test-reviewer-password-123")
            .postForEntity("/api/v1/auth/register", input, String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void registrationRequiresCorrectInvitationAndStrongPassword() {
    assertEquals(
        403,
        BrowserTestClient.login(http, "reviewer", "test-reviewer-password-123")
            .postForEntity(
                "/api/v1/auth/register",
                Map.of(
                    "username",
                    "tester",
                    "password",
                    "test-password-for-user",
                    "invite_code",
                    "wrong-invite"),
                String.class)
            .getStatusCode()
            .value());
    assertEquals(
        400,
        BrowserTestClient.login(http, "reviewer", "test-reviewer-password-123")
            .postForEntity(
                "/api/v1/auth/register",
                Map.of(
                    "username",
                    "tester",
                    "password",
                    "short",
                    "invite_code",
                    "test-invite-code-32-characters-long"),
                String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void orderVerificationRequiresShopAdministrationButStaffCanReadStatus() {
    String invite = workspaces.invite("reviewer").get("invite_code").toString();
    users.register("order-staff", "staff-password-123", invite);
    var input = Map.of("order_id", "fixture-order", "buyer_ref", "fixture-buyer");
    when(pddCommerce.retryVerifiedOrder("fixture-order", "fixture-buyer"))
        .thenReturn(Map.of("order", Map.of("order_status", "cancelled")));
    assertEquals(
        403,
        scopedRequest(
                "order-staff",
                "staff-password-123",
                "fixture-shop",
                "/api/v1/admin/shop/order-query/verification",
                input)
            .getStatusCode()
            .value());
    assertEquals(
        403,
        scopedRequest(
                "order-staff",
                "staff-password-123",
                "fixture-shop",
                "/api/v1/admin/shop/order-query/close",
                Map.of())
            .getStatusCode()
            .value());
    verify(pddCommerce, never()).orderVerification(anyString(), anyString());
    verify(pddCommerce, never()).closeOrderVerification();
    assertEquals(
        200,
        scopedRequest(
                "order-staff",
                "staff-password-123",
                "fixture-shop",
                "/api/v1/shop/order-query/retry",
                input)
            .getStatusCode()
            .value());
    assertEquals(
        204,
        scopedRequest(
                "reviewer",
                "test-reviewer-password-123",
                "fixture-shop",
                "/api/v1/admin/shop/order-query/close",
                Map.of())
            .getStatusCode()
            .value());
  }

  @Test
  void orderQueryCannotCrossShopScopeOrAcceptMissingBuyer() {
    String other =
        users
            .register(
                "other-order-owner", "owner-password-123", "test-invite-code-32-characters-long")
            .get("shop_id")
            .toString();
    var input = Map.of("order_id", "fixture-order", "buyer_ref", "fixture-buyer");
    assertEquals(
        403,
        scopedRequest(
                "reviewer",
                "test-reviewer-password-123",
                other,
                "/api/v1/admin/shop/order-query/verification",
                input)
            .getStatusCode()
            .value());
    assertEquals(
        403,
        scopedRequest(
                "reviewer",
                "test-reviewer-password-123",
                other,
                "/api/v1/shop/order-query/retry",
                input)
            .getStatusCode()
            .value());
    assertEquals(
        400,
        scopedRequest(
                "reviewer",
                "test-reviewer-password-123",
                "fixture-shop",
                "/api/v1/shop/order-query/retry",
                Map.of("order_id", "fixture-order"))
            .getStatusCode()
            .value());
    verifyNoInteractions(pddCommerce);
  }

  ResponseEntity<Map> scopedRequest(
      String username, String password, String shop, String path, Object body) {
    var headers = new HttpHeaders();
    headers.set("X-Shop-Id", shop);
    return BrowserTestClient.login(http, username, password)
        .exchange(
            path,
            body == null ? HttpMethod.GET : HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class);
  }

  Contracts.Inbound shopInput(String shop, String mid, String buyer) {
    var binding =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT * FROM shops WHERE id=?", shop));
    return new Contracts.Inbound(
        UUID.randomUUID().toString(),
        shop,
        binding.get("platform_shop_id").toString(),
        binding.get("connector_account_id").toString(),
        mid,
        buyer,
        "inbound",
        "text",
        "什么时候发货？",
        OffsetDateTime.parse("2026-10-27T08:00:00Z"),
        OffsetDateTime.now(),
        "reply");
  }

  @Test
  void merchantRegistrationCreatesPrivateWorkspaceAndForeignResourcesAreForbidden() {
    var prior = receive("private-existing-message");
    var result =
        users.register(
            "merchant-a", "merchant-password-123", "test-invite-code-32-characters-long");
    String shop = result.get("shop_id").toString();
    assertEquals(
        403,
        scopedRequest("merchant-a", "merchant-password-123", shop, "/actuator/prometheus", null)
            .getStatusCode()
            .value());
    assertNotEquals("fixture-shop", shop);
    var own = scopedRequest("merchant-a", "merchant-password-123", shop, "/api/v1/bootstrap", null);
    assertEquals(200, own.getStatusCode().value());
    assertEquals(shop, own.getBody().get("active_shop_id"));
    assertEquals(0, ((List<?>) own.getBody().get("conversations")).size());
    assertEquals(1, ((List<?>) own.getBody().get("shops")).size());
    assertEquals(
        403,
        scopedRequest(
                "merchant-a", "merchant-password-123", "fixture-shop", "/api/v1/bootstrap", null)
            .getStatusCode()
            .value());
    assertEquals(
        403,
        scopedRequest(
                "merchant-a",
                "merchant-password-123",
                shop,
                "/api/v1/conversations/" + prior.get("conversation_id"),
                null)
            .getStatusCode()
            .value());
    for (String path :
        List.of(
            "/api/v1/shops/fixture-shop/knowledge",
            "/api/v1/shops/fixture-shop/products",
            "/api/v1/shops/fixture-shop/products/sync")) {
      var response =
          BrowserTestClient.login(http, "merchant-a", "merchant-password-123")
              .exchange(
                  path,
                  path.endsWith("sync") ? HttpMethod.POST : HttpMethod.GET,
                  new HttpEntity<>(
                      Map.of(),
                      new HttpHeaders() {
                        {
                          set("X-Shop-Id", shop);
                        }
                      }),
                  String.class);
      assertEquals(403, response.getStatusCode().value());
    }
    var model =
        scopedRequest(
            "merchant-a", "merchant-password-123", shop, "/api/v1/admin/model-config", null);
    assertEquals(200, model.getStatusCode().value());
    assertNull(model.getBody().get("latest"));
    try (var scope = com.pddcopilot.core.support.ShopScope.open(shop)) {
      assertEquals(Map.of("managed", true, "enabled", false), models.effective(shop));
      assertTrue(knowledge.versions(shop).isEmpty());
    }
    assertEquals(
        403,
        scopedRequest("reviewer", "test-reviewer-password-123", shop, "/api/v1/bootstrap", null)
            .getStatusCode()
            .value());
  }

  @Test
  void shopSwitchKeepsMessagesKnowledgeJobsProductsAndAuditsInOriginalScope() {
    String second = workspaces.addShop("第二家店铺", "reviewer").get("shop_id").toString();
    String rootVersion =
        knowledge
            .publish(
                "fixture-shop",
                new Contracts.Knowledge(
                    "same-label",
                    List.of(
                        Map.of(
                            "source_id",
                            "s",
                            "source_type",
                            "shop_policy",
                            "topics",
                            List.of("shipping"),
                            "text",
                            "第一家规则"))),
                "reviewer")
            .get("knowledge_version")
            .toString();
    var original = receive("same-platform-id");
    Map<String, Object> other;
    String run;
    try (var scope = com.pddcopilot.core.support.ShopScope.open(second)) {
      String secondVersion =
          knowledge
              .publish(
                  second,
                  new Contracts.Knowledge(
                      "same-label",
                      List.of(
                          Map.of(
                              "source_id",
                              "s",
                              "source_type",
                              "shop_policy",
                              "topics",
                              List.of("shipping"),
                              "text",
                              "第二家规则"))),
                  "reviewer")
              .get("knowledge_version")
              .toString();
      assertNotEquals(rootVersion, secondVersion);
      other =
          inbox.receive(
              shopInput(second, "same-platform-id", "buyer-001"),
              "pdd:" + second + ":same-platform-id");
      run =
          com.pddcopilot.core.repository.DatabaseRows.required(
                  jdbc.queryForList(
                      "SELECT id FROM agent_runs WHERE message_id=?", other.get("message_id")))
              .get("id")
              .toString();
      assertEquals(second, runs.context(run).get("shop_id"));
      assertEquals(
          second,
          com.pddcopilot.core.repository.DatabaseRows.required(
                  jdbc.queryForList("SELECT shop_id FROM jobs WHERE reference_id=?", run))
              .get("shop_id"));
      knowledge.saveProducts(
          second,
          Map.of("shop_id", second, "products", List.of(Map.of("goods_id", "private-goods"))),
          "reviewer");
      assertEquals(1, conversations.list("reviewer").size());
      assertTrue(
          conversations.auditEvents().stream().allMatch(e -> second.equals(e.get("shop_id"))));
    }
    assertNotEquals(original.get("conversation_id"), other.get("conversation_id"));
    assertThrows(ResponseStatusException.class, () -> runs.context(run));
    assertEquals(1, conversations.list("reviewer").size());
    assertTrue(knowledge.latestProducts("fixture-shop").isEmpty());
    var selected =
        scopedRequest("reviewer", "test-reviewer-password-123", second, "/api/v1/bootstrap", null);
    assertEquals(200, selected.getStatusCode().value());
    assertEquals(second, selected.getBody().get("active_shop_id"));
    assertEquals(2, ((List<?>) selected.getBody().get("shops")).size());
    assertEquals("fixture-shop", store.shopId());
  }

  @Test
  void invitationsAreScopedSingleUseRevocableAndExpiring() {
    String code = workspaces.invite("reviewer").get("invite_code").toString();
    var joined = users.register("team-reviewer", "team-password-123", code);
    assertEquals("fixture-shop", joined.get("shop_id"));
    assertThrows(
        ResponseStatusException.class,
        () -> users.register("team-reuse", "team-password-123", code));
    String other =
        users
            .register(
                "separate-merchant", "merchant-password-123", "test-invite-code-32-characters-long")
            .get("shop_id")
            .toString();
    assertEquals(
        403,
        scopedRequest("team-reviewer", "team-password-123", other, "/api/v1/bootstrap", null)
            .getStatusCode()
            .value());
    var revoked = workspaces.invite("reviewer");
    workspaces.revokeInvite(revoked.get("id").toString(), "reviewer");
    assertThrows(
        ResponseStatusException.class,
        () ->
            users.register(
                "revoked-user", "team-password-123", revoked.get("invite_code").toString()));
    var expired = workspaces.invite("reviewer");
    jdbc.update("UPDATE workspace_invitations SET expires_at=0 WHERE id=?", expired.get("id"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            users.register(
                "expired-user", "team-password-123", expired.get("invite_code").toString()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM app_users WHERE username IN"
                + " ('team-reuse','revoked-user','expired-user')",
            Integer.class));
    assertTrue(workspaces.overview("reviewer").toString().indexOf(code) < 0);
  }

  @Test
  void queuedForeignShopResultsCommitUnderPinnedScopeEvenWhenCallerSelectedAnotherShop() {
    String second = workspaces.addShop("任务店铺", "reviewer").get("shop_id").toString();
    String message, run;
    try (var scope = com.pddcopilot.core.support.ShopScope.open(second)) {
      var result =
          inbox.receive(
              shopInput(second, "job-message", "buyer-job"), "pdd:" + second + ":job-message");
      message = result.get("message_id").toString();
      run =
          com.pddcopilot.core.repository.DatabaseRows.required(
                  jdbc.queryForList("SELECT id FROM agent_runs WHERE message_id=?", message))
              .get("id")
              .toString();
    }
    var job = jobs.claim();
    assertEquals(second, job.get("shop_id"));
    when(agent.generate(eq(run), anyMap()))
        .thenAnswer(
            invocation -> {
              assertEquals(second, store.shopId());
              Map<String, Object> context = invocation.getArgument(1);
              assertEquals(second, context.get("shop_id"));
              return Map.of(
                  "run_id",
                  run,
                  "message_id",
                  message,
                  "outcome",
                  "draft",
                  "content",
                  "任务所属店铺的草稿",
                  "citations",
                  List.of(),
                  "risk_codes",
                  List.of());
            });
    worker.process(job);
    assertEquals(
        "done",
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT state FROM jobs WHERE id=?", job.get("id")))
            .get("state"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM drafts d JOIN messages m ON m.id=d.message_id WHERE"
                + " m.shop_id='fixture-shop'",
            Integer.class));
    assertEquals("fixture-shop", store.shopId());
  }

  @Test
  void connectorEnrollmentRequiresDeploymentAuthorizationAndCannotClaimAnotherShop() {
    String first = workspaces.addShop("待接入一", "reviewer").get("shop_id").toString();
    String second = workspaces.addShop("待接入二", "reviewer").get("shop_id").toString();
    jdbc.update(
        "UPDATE shops SET"
            + " mode='live',binding_state='unbound',platform_shop_id='unbound',connector_account_id='unbound'"
            + " WHERE id IN (?,?)",
        first,
        second);
    var denied =
        scopedRequest(
            "reviewer",
            "test-reviewer-password-123",
            first,
            "/api/v1/admin/shop/connector-binding",
            Map.of(
                "platform_shop_id",
                "native-fixture-shop",
                "connector_account_id",
                "native-fixture-account"));
    assertEquals(403, denied.getStatusCode().value());
    assertEquals("CONNECTOR_ENROLLMENT_REQUIRED", denied.getBody().get("code"));
    try (var scope = com.pddcopilot.core.support.ShopScope.open(first)) {
      var binding =
          workspaces.bindConnector("native-fixture-shop", "native-fixture-account", "reviewer");
      assertEquals("bound", binding.get("binding_state"));
      assertEquals(
          binding,
          workspaces.bindConnector("native-fixture-shop", "native-fixture-account", "reviewer"));
      assertThrows(
          ResponseStatusException.class,
          () -> workspaces.bindConnector("other-native-shop", "other-account", "reviewer"));
    }
    try (var scope = com.pddcopilot.core.support.ShopScope.open(second)) {
      assertThrows(
          ResponseStatusException.class,
          () ->
              workspaces.bindConnector(
                  "native-fixture-shop", "native-fixture-account", "reviewer"));
      assertEquals("unbound", workspaces.connectorBinding().get("binding_state"));
    }
  }

  @Test
  void merchantModelEndpointsCannotTargetArbitraryHostsOrDevelopmentLoopback() {
    String shop =
        users
            .register(
                "endpoint-owner", "endpoint-password-123", "test-invite-code-32-characters-long")
            .get("shop_id")
            .toString();
    try (var scope = com.pddcopilot.core.support.ShopScope.open(shop)) {
      for (String url :
          List.of(
              "https://127.0.0.1:8080",
              "http://localhost:9999",
              "https://internal.example/v1",
              "https://169.254.169.254/v1",
              "https://api.deepseek.com:8443/v1")) {
        assertThrows(
            ResponseStatusException.class,
            () ->
                models.save(
                    new ModelConfigurationInput(
                        0, url, "fixture-model", "fixture-secret-key", 4, 60),
                    "endpoint-owner"));
      }
      assertNull(models.overview().get("latest"));
      models.save(
          new ModelConfigurationInput(
              0, "https://api.deepseek.com/v1", "fixture-model", "fixture-secret-key", 4, 60),
          "endpoint-owner");
      assertNotNull(models.overview().get("latest"));
    }
    assertNull(models.overview().get("latest"));
  }

  @Test
  void regenerationFromStalePageCannotCreateAnOldQuestionDraftAtTheNewConversationRevision() {
    var old = draft("older-question", "draft");
    receive("newer-question");
    int before = jdbc.queryForObject("SELECT count(*) FROM agent_runs", Integer.class);
    var conflict =
        assertThrows(
            ResponseStatusException.class,
            () -> runs.regenerate(old.get("message_id").toString(), "reviewer"));
    assertEquals("MESSAGE_SUPERSEDED", conflict.getReason());
    assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM agent_runs", Integer.class));
    assertEquals(
        "outdated",
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT state FROM drafts WHERE id=?", old.get("id")))
            .get("state"));
  }

  Map<String, Object> detailResult(String shop, String goods) {
    return Map.of(
        "shop_id",
        shop,
        "detail",
        Map.of(
            "goods_id",
            goods,
            "goods_name",
            "测试商品",
            "category_path",
            List.of("演示分类", "杯具"),
            "attributes",
            List.of(Map.of("name", "容量", "values", List.of("500mL"))),
            "attributes_status",
            "synced",
            "detail_schema_version",
            3,
            "freight",
            Map.of("status", "synced"),
            "sku_count",
            2,
            "skus",
            List.of(),
            "fetched_at",
            "2026-10-27T10:00:00Z"));
  }

  void seedProducts(String shop, String... goods) {
    knowledge.saveProducts(
        shop,
        Map.of(
            "shop_id",
            shop,
            "products",
            java.util.Arrays.stream(goods)
                .map(id -> Map.of("goods_id", id, "goods_name", "测试商品"))
                .toList()),
        "reviewer");
  }

  @Test
  void profileUpdatesRealIdentityPreservesAliasesAndRejectsOtherAccounts() {
    jdbc.update("UPDATE shops SET name='我的店铺' WHERE id='fixture-shop'");
    connections.updateProfile(
        "fixture-shop",
        new Contracts.ShopProfile(
            "fixture-platform-shop",
            "fixture-account",
            "平台店名",
            "https://img.pddpic.com/logo.png",
            "主营类目"));
    var shop = connections.shops().get(0);
    assertEquals("平台店名", shop.get("name"));
    assertEquals("https://img.pddpic.com/logo.png", shop.get("avatar_url"));
    workspaces.renameShop("自定义别名", "reviewer");
    connections.updateProfile(
        "fixture-shop",
        new Contracts.ShopProfile(
            "fixture-platform-shop", "fixture-account", "平台更新店名", null, "主营类目"));
    assertEquals("自定义别名", connections.shops().get(0).get("name"));
    assertEquals("平台更新店名", connections.shops().get(0).get("platform_name"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            connections.updateProfile(
                "fixture-shop",
                new Contracts.ShopProfile(
                    "foreign-platform", "foreign-account", "其他店铺", null, null)));
  }

  @Test
  void productMetadataSyncIsDurableIdempotentAndRunsWithBoundedShopConcurrency() {
    seedProducts("fixture-shop", "goods-a", "goods-b");
    var run = products.start("reviewer");
    assertEquals(run.get("id"), products.start("reviewer").get("id"));
    var first = jobs.claim();
    assertEquals("product_detail", first.get("kind"));
    var second = jobs.claim();
    assertEquals("product_detail", second.get("kind"));
    assertNull(jobs.claim());
    var item = products.item(first.get("reference_id").toString());
    tx.executeWithoutResult(
        status ->
            jobs.complete(first, detailResult("fixture-shop", item.get("goods_id").toString())));
    var secondItem = products.item(second.get("reference_id").toString());
    tx.executeWithoutResult(
        status ->
            jobs.complete(
                second, detailResult("fixture-shop", secondItem.get("goods_id").toString())));
    assertEquals(2, ((Number) products.status().get("completed")).intValue());
    assertEquals("completed", products.status().get("state"));
    assertNotNull(products.detail("goods-a").get("detail"));
    assertTrue(
        knowledge
            .latestProducts("fixture-shop")
            .get(0)
            .get("products")
            .toString()
            .contains("category_path"));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM send_commands", Integer.class));
  }

  @Test
  void unchangedCatalogReusesSnapshotButUpdatesCheckTime() {
    seedProducts("fixture-shop", "goods-a", "goods-b");
    var old = knowledge.latestProducts("fixture-shop").get(0);
    jdbc.update("UPDATE product_snapshots SET checked_at=1 WHERE id=?", old.get("id"));
    var result =
        knowledge.saveProducts(
            "fixture-shop",
            Map.of(
                "shop_id",
                "fixture-shop",
                "products",
                List.of(
                    Map.of("goods_id", "goods-b", "goods_name", "测试商品", "fetched_at", "later"),
                    Map.of("goods_id", "goods-a", "goods_name", "测试商品", "fetched_at", "later"))),
            "reviewer");
    assertEquals(true, result.get("unchanged"));
    assertEquals(old.get("id"), result.get("snapshot_id"));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM product_snapshots", Integer.class));
    var checked = knowledge.latestProducts("fixture-shop").get(0);
    assertTrue(((Number) checked.get("checked_at")).longValue() > 1);
    assertEquals(old.get("created_at"), checked.get("created_at"));
  }

  @Test
  void oldGenerationDoesNotBlockNewGenerationAfterTakeoverAndResume() {
    var message = receive("generation-before-takeover");
    String cid = message.get("conversation_id").toString();
    conversations.transfer(cid, "reviewer");
    conversations.resume(cid, 2, "reviewer");
    assertEquals(
        "agent_running",
        runs.regenerate(message.get("message_id").toString(), "reviewer").get("state"));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM agent_runs", Integer.class));
    assertEquals(
        "RUN_IN_PROGRESS",
        assertThrows(
                ResponseStatusException.class,
                () -> runs.regenerate(message.get("message_id").toString(), "reviewer"))
            .getReason());
  }

  @Test
  void unchangedRecentDetailsSkipRequestsButChangedExpiredAndForcedDetailsRefresh() {
    seedProducts("fixture-shop", "goods-a");
    products.start("reviewer");
    var job = jobs.claim();
    jobs.complete(job, detailResult("fixture-shop", "goods-a"));
    var previous =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList("SELECT * FROM product_details"));
    var reused = products.start("reviewer");
    assertEquals("completed", reused.get("state"));
    assertEquals(1, ((Number) reused.get("skipped")).intValue());
    assertNull(jobs.claim());
    assertEquals(
        previous.get("updated_at"),
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT * FROM product_details"))
            .get("updated_at"));
    products.start("reviewer", true);
    var forced = jobs.claim();
    assertNotNull(forced);
    jobs.complete(forced, detailResult("fixture-shop", "goods-a"));
    jdbc.update("UPDATE product_details SET updated_at=1");
    products.start("reviewer");
    var expired = jobs.claim();
    assertNotNull(expired);
    jobs.complete(expired, detailResult("fixture-shop", "goods-a"));
    knowledge.saveProducts(
        "fixture-shop",
        Map.of(
            "shop_id",
            "fixture-shop",
            "products",
            List.of(Map.of("goods_id", "goods-a", "goods_name", "商品名称变更"))),
        "reviewer");
    products.start("reviewer");
    assertNotNull(jobs.claim());
  }

  @Test
  void restoredAgentCanRegenerateAndRememberSuccessfulManualReplyInOrder() {
    var d = draft("human-memory-question", "draft");
    String mid = d.get("message_id").toString();
    String cid =
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList("SELECT conversation_id FROM messages WHERE id=?", mid))
            .get("conversation_id")
            .toString();
    conversations.transfer(cid, "reviewer");
    long revision = ((Number) conversations.detail(cid, "reviewer").get("revision")).longValue();
    manual.submit(
        cid,
        new Contracts.ManualReply(UUID.randomUUID().toString(), revision, "人工已说明：正在核实这个商品。"),
        "reviewer");
    var send = jobs.claim();
    jobs.complete(send, Map.of("state", "sent"));
    conversations.resume(cid, revision + 1, "reviewer");
    runs.regenerate(mid, "reviewer");
    var run =
        com.pddcopilot.core.repository.DatabaseRows.required(
            jdbc.queryForList(
                "SELECT * FROM agent_runs WHERE message_id=? AND state='queued'", mid));
    var history = (List<Map<String, Object>>) runs.context(run.get("id").toString()).get("history");
    assertEquals(2, history.size());
    assertEquals("inbound", history.get(0).get("direction"));
    assertEquals("text", history.get(0).get("message_type"));
    assertEquals("人工已说明：正在核实这个商品。", history.get(1).get("content"));
    String future = receive("future-question").get("message_id").toString();
    jdbc.update(
        "UPDATE messages SET created_at=? WHERE id=?",
        ((Number) run.get("created_at")).longValue() + 100,
        future);
    var pinned = (List<Map<String, Object>>) runs.context(run.get("id").toString()).get("history");
    assertEquals(2, pinned.size());
    assertTrue(pinned.stream().noneMatch(m -> future.equals(m.get("id"))));
    jdbc.update("UPDATE messages SET state='send_failed' WHERE direction='outbound'");
    var failed = (List<Map<String, Object>>) runs.context(run.get("id").toString()).get("history");
    assertEquals(1, failed.size());
  }

  @Test
  void agentWorkHasPriorityOverMetadataAndTerminalDetailFailureLeavesRetryableRun() {
    seedProducts("fixture-shop", "goods-a");
    products.start("reviewer");
    receive("new-question-priority");
    var generation = jobs.claim();
    assertEquals("generate", generation.get("kind"));
    var metadata = jobs.claim();
    assertEquals("product_detail", metadata.get("kind"));
    metadata.put("attempts", 4);
    jobs.fail(metadata);
    assertEquals("completed_with_errors", products.status().get("state"));
    assertEquals(1, ((Number) products.status().get("failed")).intValue());
    assertNull(products.detail("goods-a").get("detail"));
    assertEquals("running", products.start("reviewer").get("state"));
  }

  @Test
  void metadataResultsAndCachedDetailsCannotCrossShop() {
    seedProducts("fixture-shop", "same-goods");
    products.start("reviewer");
    var job = jobs.claim();
    String item = job.get("reference_id").toString();
    assertThrows(
        IllegalStateException.class,
        () -> products.complete(item, detailResult("foreign-shop", "same-goods")));
    String other = workspaces.addShop("其他店铺", "reviewer").get("shop_id").toString();
    try (var scope = com.pddcopilot.core.support.ShopScope.open(other)) {
      seedProducts(other, "same-goods");
      assertThrows(ResponseStatusException.class, () -> products.item(item));
      assertNull(products.detail("same-goods").get("detail"));
    }
  }

  @Test
  void syncedProductFactsReachAgentWithoutSnapshotInventoryOrImages() {
    seedProducts("fixture-shop", "goods-a");
    products.start("reviewer");
    var job = jobs.claim();
    var result = new LinkedHashMap<String, Object>(detailResult("fixture-shop", "goods-a"));
    var detail = new LinkedHashMap<String, Object>((Map<String, Object>) result.get("detail"));
    detail.put(
        "skus",
        List.of(Map.of("specs", List.of(Map.of("name", "颜色", "value", "白色")), "quantity", 9999)));
    detail.put("images", List.of("https://img.pddpic.com/test.png"));
    result.put("detail", detail);
    tx.executeWithoutResult(status -> jobs.complete(job, result));
    var base = input("product-metadata-source", "商品来源");
    inbox.receive(
        new Contracts.Inbound(
            base.eventId(),
            base.shopId(),
            base.platformShopId(),
            base.connectorAccountId(),
            base.platformMessageId(),
            base.buyerRef(),
            "inbound",
            "system_hint",
            base.content(),
            base.occurredAt(),
            base.receivedAt(),
            "context_only",
            new Contracts.Media("product", null, "goods-a", "测试商品")),
        "pdd:fixture-shop:product-metadata-source");
    var message = receive("product-metadata-question");
    String run =
        com.pddcopilot.core.repository.DatabaseRows.required(
                jdbc.queryForList(
                    "SELECT id FROM agent_runs WHERE message_id=?", message.get("message_id")))
            .get("id")
            .toString();
    var facts = (Map<?, ?>) runs.context(run).get("platform_product_facts");
    assertEquals("goods-a", facts.get("goods_id"));
    assertTrue(facts.toString().contains("500mL"));
    assertFalse(facts.toString().contains("quantity"));
    assertFalse(facts.containsKey("images"));
  }
}

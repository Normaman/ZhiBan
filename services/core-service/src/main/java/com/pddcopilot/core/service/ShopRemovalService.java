package com.pddcopilot.core.service;

import com.pddcopilot.core.client.AgentClient;
import com.pddcopilot.core.mapper.ShopRemovalMapper;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.WorkspaceRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Removes a shop only after stopping its connector and atomically deleting shop-scoped data. */
@Service
public class ShopRemovalService {
  private final BusinessStore store;
  private final WorkspaceRepository workspaces;
  private final ConnectorRegistryService connectors;
  private final AgentClient agent;
  private final com.pddcopilot.core.platform.pdd.PddConnectionManager connections;
  private final ShopRemovalMapper mapper;
  private final Path dataRoot;
  private final String defaultShop;
  private final TransactionTemplate transaction;

  public ShopRemovalService(
      BusinessStore store,
      WorkspaceRepository workspaces,
      ConnectorRegistryService connectors,
      AgentClient agent,
      com.pddcopilot.core.platform.pdd.PddConnectionManager connections,
      ShopRemovalMapper mapper,
      PlatformTransactionManager transactions,
      @Value("${PDD_DATA_DIR:/data}") String dataRoot,
      @Value("${copilot.shop-id:initial-shop}") String defaultShop) {
    this.store = store;
    this.workspaces = workspaces;
    this.connectors = connectors;
    this.agent = agent;
    this.connections = connections;
    this.mapper = mapper;
    this.dataRoot = Path.of(dataRoot).toAbsolutePath().normalize();
    this.defaultShop = defaultShop;
    this.transaction = new TransactionTemplate(transactions);
  }

  public void validateRemoval(String shopId) {
    String currentShop = store.shopId();
    var current = workspaces.shop(currentShop, false);
    var target = workspaces.shop(shopId, false);
    String workspace = current.get("workspace_id").toString();
    if (!workspace.equals(target.get("workspace_id"))) throw notFound();
    if (shopId.equals(currentShop)) throw conflict("CURRENT_SHOP_CANNOT_BE_REMOVED");
    if (shopId.equals(defaultShop)) throw conflict("DEFAULT_SHOP_CANNOT_BE_REMOVED");
    if (workspaces.shopCount(workspace) <= 1) throw conflict("LAST_SHOP_CANNOT_BE_REMOVED");
    if (mapper.hasActiveJobs(shopId)) throw conflict("SHOP_HAS_ACTIVE_JOBS");
    if (mapper.hasUnresolvedSends(shopId)) throw conflict("SHOP_HAS_UNRESOLVED_SENDS");
  }

  public void remove(String shopId, String actor) {
    validateRemoval(shopId);
    String currentShop = store.shopId();
    Path registeredDirectory = connectors.remove(shopId);
    try {
      connections.stopForShopRemoval(shopId);
      agent.stopShop(shopId);
      transaction.executeWithoutResult(status -> deleteData(shopId, currentShop, actor));
    } catch (RuntimeException error) {
      connectors.restore(shopId, registeredDirectory);
      throw error;
    }
    deleteDataDirectory(shopId, registeredDirectory);
  }

  private void deleteData(String shopId, String fallbackShop, String actor) {
    // Recheck after stopping the connector; the shop lock also serializes inbound writes.
    var target = workspaces.shop(shopId, true);
    var fallback = workspaces.shop(fallbackShop, false);
    if (!target.get("workspace_id").equals(fallback.get("workspace_id"))) throw notFound();
    if (mapper.hasActiveJobs(shopId)) throw conflict("SHOP_HAS_ACTIVE_JOBS");
    if (mapper.hasUnresolvedSends(shopId)) throw conflict("SHOP_HAS_UNRESOLVED_SENDS");
    mapper.deleteEventSpool(shopId);
    mapper.deleteSendParts(shopId);
    mapper.deleteJobOutbox(shopId);
    mapper.deleteKnowledgeProposals(shopId);
    mapper.deleteReplyCommandMessages(shopId);
    mapper.deleteReplyRunMessages(shopId);
    mapper.clearAnswerReferences(shopId);
    mapper.deleteReplyQuestions(shopId);
    mapper.deleteSendCommands(shopId);
    mapper.deleteDecisions(shopId);
    mapper.deleteDrafts(shopId);
    mapper.deleteManualReplies(shopId);
    mapper.deleteAgentRuns(shopId);
    mapper.deleteMessages(shopId);
    mapper.deleteConversations(shopId);
    mapper.deleteJobs(shopId);
    mapper.deleteProductSyncItems(shopId);
    mapper.deleteProductSyncRuns(shopId);
    mapper.deleteProductDetails(shopId);
    mapper.deleteProductSnapshots(shopId);
    mapper.deleteKnowledgeVersions(shopId);
    mapper.deletePlatformReadMarkers(shopId);
    mapper.deleteModelSettings(shopId);
    mapper.deleteModelConfigurations(shopId);
    mapper.deleteConnectorBindings(shopId);
    mapper.reassignUsers(fallbackShop, shopId);
    mapper.recordRemoval(
        java.util.UUID.randomUUID().toString(),
        fallbackShop,
        "shop.removed",
        shopId,
        actor,
        java.util.UUID.randomUUID().toString(),
        System.currentTimeMillis());
    if (mapper.deleteShops(shopId) != 1) throw notFound();
  }

  private void deleteDataDirectory(String shopId, Path registeredDirectory) {
    Path shopDirectory = dataRoot.resolve("shops").resolve(shopId).normalize();
    Path allowedRoot = dataRoot.resolve("shops").normalize();
    try {
      if (shopDirectory.startsWith(allowedRoot) && Files.exists(shopDirectory)) {
        try (var entries = Files.walk(shopDirectory)) {
          for (Path entry : entries.sorted(Comparator.reverseOrder()).toList())
            Files.deleteIfExists(entry);
        }
      }
      if (registeredDirectory != null
          && !registeredDirectory.toAbsolutePath().normalize().equals(shopDirectory)) {
        Path directory = registeredDirectory.toAbsolutePath().normalize();
        if (directory.startsWith(allowedRoot) && Files.exists(directory)) {
          try (var entries = Files.walk(directory)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList())
              Files.deleteIfExists(entry);
          }
        }
      }
    } catch (IOException error) {
      throw new IllegalStateException("SHOP_DATA_CLEANUP_FAILED", error);
    }
  }

  private ResponseStatusException conflict(String code) {
    return new ResponseStatusException(HttpStatus.CONFLICT, code);
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND");
  }
}

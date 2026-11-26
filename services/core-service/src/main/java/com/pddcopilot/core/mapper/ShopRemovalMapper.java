package com.pddcopilot.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for ShopRemovalService; transaction boundaries remain in the calling service. */
@Mapper
public interface ShopRemovalMapper {
  int deleteSendParts(@Param("shopId") String shopId);

  int deleteEventSpool(@Param("shopId") String shopId);

  int deleteJobOutbox(@Param("shopId") String shopId);

  int deleteKnowledgeProposals(@Param("shopId") String shopId);

  Boolean hasActiveJobs(@Param("shopId") String shopId);

  Boolean hasUnresolvedSends(@Param("shopId") String shopId);

  int deleteReplyCommandMessages(@Param("shopId") String shopId);

  int deleteReplyRunMessages(@Param("shopId") String shopId);

  int clearAnswerReferences(@Param("shopId") String shopId);

  int deleteReplyQuestions(@Param("shopId") String shopId);

  int deleteSendCommands(@Param("shopId") String shopId);

  int deleteDecisions(@Param("shopId") String shopId);

  int deleteDrafts(@Param("shopId") String shopId);

  int deleteManualReplies(@Param("shopId") String shopId);

  int deleteAgentRuns(@Param("shopId") String shopId);

  int deleteMessages(@Param("shopId") String shopId);

  int deleteConversations(@Param("shopId") String shopId);

  int deleteJobs(@Param("shopId") String shopId);

  int deleteProductSyncItems(@Param("shopId") String shopId);

  int deleteProductSyncRuns(@Param("shopId") String shopId);

  int deleteProductDetails(@Param("shopId") String shopId);

  int deleteProductSnapshots(@Param("shopId") String shopId);

  int deleteKnowledgeVersions(@Param("shopId") String shopId);

  int deletePlatformReadMarkers(@Param("shopId") String shopId);

  int deleteModelSettings(@Param("shopId") String shopId);

  int deleteModelConfigurations(@Param("shopId") String shopId);

  int deleteConnectorBindings(@Param("shopId") String shopId);

  int reassignUsers(@Param("fallbackShop") String fallbackShop, @Param("shopId") String shopId);

  int recordRemoval(
      @Param("value") String value,
      @Param("fallbackShop") String fallbackShop,
      @Param("value2") String value2,
      @Param("shopId") String shopId,
      @Param("actor") String actor,
      @Param("value3") String value3,
      @Param("now") long now);

  int deleteShops(@Param("shopId") String shopId);
}

package com.pddcopilot.core.support;

import java.text.Normalizer;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Last check on the exact text entering a send command, including human edits. */
public final class ReplySafety {
  private static final Pattern SOLICITATION = Pattern.compile(
      "(?:加|添加|联系).{0,8}(?:微信|vx|wechat|qq)|(?:微信|支付宝|私下|站外).{0,5}(?:转账|付款|交易)", Pattern.CASE_INSENSITIVE);
  private static final Pattern INCENTIVE = Pattern.compile(
      "(?:好评|五星|评价).{0,8}(?:返现|返款|返钱|红包|奖励)|(?:返现|红包).{0,8}(?:好评|五星)");
  private static final Pattern PROMISE = Pattern.compile(
      "(?:我|我们|本店|客服).{0,8}(?:同意|批准|给你|帮你).{0,5}(?:退款|退货|赔偿)|(?:已|已经).{0,5}(?:退款成功|同意退款|批准退货|修改地址|赔付)");

  private ReplySafety() {}

  public static void validate(String text, boolean agentReply) {
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replaceAll("[\\s\\p{Cf}]", "")
        .replaceAll("(?:禁止|不支持|不提供|不参与)(?:好评|五星评价)(?:返现|返款|返钱|红包)", "");
    if (SOLICITATION.matcher(normalized).find() || INCENTIVE.matcher(normalized).find()
        || (agentReply && PROMISE.matcher(normalized).find()))
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "REPLY_CONTENT_PROHIBITED");
  }
}

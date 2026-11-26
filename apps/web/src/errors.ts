import { ApiError } from "./api";

const connectionMessages: Record<string, string> = {
  PLATFORM_AUTH_REJECTED: "登录认证未通过，请重新登录。",
  AUTH_IDENTITY_MISMATCH: "店铺身份与连接不一致。",
  CREDENTIALS_MISSING: "缺少登录凭据。",
  WS_PROTOCOL_UNSUPPORTED: "当前平台协议不受支持，需要重新核验。",
  PLATFORM_SESSION_CHANGED: "平台会话已变化，请检查登录状态。",
  PLATFORM_SESSION_EXPIRED:
    "拼多多登录已过期，请重新登录；保存后连接器会重新建连。",
  TOKEN_UNAVAILABLE: "无法取得拼多多连接凭据，请重新登录。",
  WS_CONNECTION_FAILED: "连接失败，正在重试；可查看连接日志。",
};

export function connectionErrorMessage(code: string): string {
  return connectionMessages[code] || "连接未完成，请检查登录状态。";
}

const messages: Record<string, string> = {
  INVALID_KNOWLEDGE_VALIDITY: "知识生效时间无效，失效时间须晚于生效时间。",
  KNOWLEDGE_CONFLICT_REVIEW_REQUIRED:
    "存在可能重复或冲突的资料，请核对后再批准。",
  KNOWLEDGE_REVIEW_STATE_CONFLICT: "变更状态已更新，请重新打开查看。",
  CAMOUFOX_LOGIN_REQUIRED: "请在店铺设置中完成订单旁路的独立扫码授权。",
  CAMOUFOX_LOGIN_EXPIRED: "旁路授权已过期，请重新扫码。",
  CAMOUFOX_LOGIN_FAILED: "旁路登录未完成，请重试扫码授权。",
  CAMOUFOX_LOGIN_PAGE_FAILED: "旁路登录页面异常，请重新授权。",
  CAMOUFOX_LOGIN_CHALLENGE_REQUIRED:
    "平台要求额外验证，本次旁路已停止，请人工核实后重新授权。",
  CAMOUFOX_LOGIN_TIMEOUT: "旁路扫码或验证已超时，请重新开始。",
  CAMOUFOX_LOGIN_PAGE_UNSUPPORTED:
    "平台登录页跳转到未支持的地址，请管理员核对平台变化。",
  CAMOUFOX_LOGIN_ALREADY_RUNNING: "当前已有扫码授权，请继续当前流程。",
  CAMOUFOX_LOGIN_BUSY: "旁路授权暂时繁忙，请稍后再试。",
  CAMOUFOX_LOGIN_SESSION_INVALID: "本次扫码会话已结束，请重新开始。",
  CAMOUFOX_LOGIN_RELEASE_FAILED:
    "登录实例释放未得到确认，请联系管理员查看网关回收状态。",
  CAMOUFOX_SHOP_IDENTITY_MISMATCH: "扫码账号与当前店铺绑定不一致，授权未保存。",
  CAMOUFOX_GATEWAY_NOT_CONFIGURED: "管理员尚未配置订单旁路网关。",
  CAMOUFOX_GATEWAY_UNAVAILABLE: "订单旁路网关不可达，请检查网关部署。",
  CAMOUFOX_GATEWAY_HTTP_401: "旁路网关认证未通过，请管理员检查专用密钥。",
  CAMOUFOX_GATEWAY_HTTP_503: "旁路网关暂时不可用，请稍后重试。",
  CAMOUFOX_GATEWAY_TIMEOUT: "旁路网关响应超时，请稍后重试。",
  CAMOUFOX_LOGIN_STATE_UNAVAILABLE:
    "旁路授权无法读取，请检查加密配置或重新授权。",
  CAMOUFOX_RELEASE_NOT_CONFIRMED:
    "网关尚未确认实例释放，请管理员核对回收状态。",
  ORDER_BROWSER_REJECTED_40002: "平台拒绝了本次订单查询，请人工核实。",
  LOGIN_RATE_LIMITED: "尝试过于频繁，请稍后再试。",
  AUTH_SECURITY_UNAVAILABLE: "登录安全服务暂不可用，请稍后重试。",
  SESSION_EXPIRED: "登录已过期或被注销，请重新登录。",
  REAUTHENTICATION_REQUIRED: "请验证当前账号密码后继续。",
  REAUTHENTICATION_CANCELLED: "已取消本次操作。",
  PASSWORD_CHANGE_CONFLICT: "账号状态已更新，请重新登录后再修改密码。",
  REPLY_CONTENT_PROHIBITED:
    "回复含站外引流、评价激励或未经授权的售后承诺，请修改后再提交。",
  KNOWLEDGE_VERSION_CONFLICT:
    "知识库已更新，请关闭当前窗口，重新打开并核对资料后再操作。",
  KNOWLEDGE_VERSION_REQUIRED: "删除资料需要当前知识版本，请刷新后重试。",
  DOCUMENT_SIZE_LIMIT: "文件不能超过 5 MB。",
  DOCUMENT_TOO_COMPLEX:
    "资料过大或过于复杂，请拆分后再导入（最多 100 页 PDF、20 万字、500 段）。",
  DOCUMENT_FORMAT_UNSUPPORTED:
    "支持 TXT、Markdown、CSV、DOCX、XLSX 和文字型 PDF；旧版 DOC、XLS 请先转换。",
  DOCUMENT_ENCRYPTED: "暂不支持加密文件，请解除密码后导入。",
  DOCUMENT_ENCODING: "文本编码无法识别，请另存为 UTF-8 后导入。",
  DOCUMENT_NO_TEXT:
    "未提取到文字。扫描件和图片暂不支持，请转换为可复制文字的资料。",
  DOCUMENT_FORMULAS:
    "Excel 包含公式，请先复制并粘贴为数值，再导入，避免价格或规则被误读。",
  DOCUMENT_PARSE_FAILED:
    "文件解析失败，请检查文件是否损坏或另存为受支持的格式。",
  DOCUMENT_PARSER_BUSY: "同时解析的资料较多，请稍后重试。",
  DOCUMENT_PARSER_UNAVAILABLE: "资料解析服务暂不可用，请稍后重试。",
  INVALID_KNOWLEDGE_SOURCE: "资料来源格式有误，请重新导入。",
  PRODUCT_SNAPSHOT_REQUIRED: "请先同步商品列表，再同步分类与规格。",
  PRODUCT_NOT_FOUND: "当前店铺商品列表中未找到该商品，请重新同步商品。",
  INVALID_PRODUCT_CARDS: "商品卡片信息无效，请重新选择商品。",
  APPROVED_CARDS_INVALID: "审批中的商品卡片未通过核验，请人工核实。",
  PRODUCT_CARD_NOT_IN_DRAFT: "商品卡片已变化，请刷新草稿后重新勾选。",
  CARD_PRODUCT_UNAVAILABLE: "所选商品暂无库存或不在当前在售列表，请更换商品。",
  CARD_PRODUCT_CHECK_FAILED: "商品实时查询未完成，请稍后重试。",
  MESSAGE_SUPERSEDED: "买家已有新咨询，请基于最新消息重新生成草稿。",
  MODEL_HOST_NOT_ALLOWED:
    "此模型服务域名尚未开放，请联系部署管理员配置允许的服务商。",
  MEMBER_NOT_FOUND: "成员已移除或不属于当前工作空间，请刷新列表。",
  MEMBER_NOT_MANAGEABLE: "不能操作自己、所有者或管理员。",
  MEMBER_REVISION_CONFLICT: "成员状态已变化，请刷新后重试。",
  SHOP_FORBIDDEN: "你没有此店铺的访问权限，请选择自己的店铺。",
  KNOWLEDGE_ARCHIVE_INVALID: "知识备份格式或校验值不正确，请重新导出。",
  KNOWLEDGE_EXPORT_EMPTY: "当前店铺还没有生效的知识库可导出。",
  KNOWLEDGE_MIGRATION_BLOCKED:
    "知识迁移暂不能执行，请先核对缺失商品或条目数量。",
  KNOWLEDGE_LIMIT_EXCEEDED: "合并后超过 500 条知识资料，请先整理后再迁移。",
  CURRENT_SHOP_CANNOT_BE_REMOVED: "请先切换到其他店铺，再移除此店铺。",
  DEFAULT_SHOP_CANNOT_BE_REMOVED: "这是部署默认店铺，不能移除。",
  LAST_SHOP_CANNOT_BE_REMOVED: "至少要保留一家店铺。",
  SHOP_HAS_ACTIVE_JOBS: "这家店铺仍有任务正在处理，请完成后再移除。",
  SHOP_HAS_UNRESOLVED_SENDS: "这家店铺有发送结果待核实，请核实后再移除。",
  CONNECTOR_NOT_CONFIGURED: "此店铺尚未完成拼多多授权连接。",
  PDD_LOGIN_ALREADY_RUNNING: "已有一个拼多多授权流程正在进行，请先完成或取消。",
  PDD_SHOP_NOT_BOUND: "当前店铺尚未绑定拼多多账号。",
  ORDER_LIST_QUERY_REJECTED:
    "拼多多暂未返回订单列表，请确认店铺登录状态后重试。",
  ORDER_LIST_QUERY_FAILED: "订单列表读取失败，请稍后重试。",
  ORDER_LIST_FILTER_INVALID: "该订单筛选暂不支持，请刷新页面后重新选择。",
  ORDER_REFRESH_BUSY: "订单更新较多，请稍后重试。",
  ORDER_REFRESH_FAILED: "订单更新失败，已保留上次读取的数据。",
  ORDER_CACHE_UNAVAILABLE: "订单缓存暂时不可用，请稍后重试。",
  ORDER_LIST_SCHEMA_UNSUPPORTED: "拼多多返回了暂不支持的订单数据格式。",
  ORDER_LIST_SESSION_REFRESH_FAILED:
    "订单查询会话不可用，请重新登录拼多多店铺。",
  ORDER_LIST_CAPTURE_FAILED: "订单页未能完成读取，请确认拼多多登录状态后重试。",
  PDD_LOGIN_DIRECTORY_MISMATCH: "授权数据与当前店铺不匹配，不能覆盖。",
  PDD_LOGIN_SCOPE_MISMATCH: "授权流程与当前店铺不匹配，请重新打开店铺页面。",
  LOGIN_HELPER_START_FAILED:
    "拼多多授权页面未能启动，请稍后重试或联系部署管理员。",
  SHOP_ALREADY_BOUND: "此店铺已绑定其他平台账号，请添加新的店铺。",
  PLATFORM_SHOP_ALREADY_BOUND:
    "该拼多多店铺已关联到其他店铺记录，请切换到原店铺重新授权，不能重复绑定。",
  PDD_IDENTITY_INVALID: "没有取得完整的拼多多店铺身份，请重新登录。",
  CONNECTOR_VERIFICATION_MISMATCH: "登录资料核验未通过，请重新授权。",
  CONNECTOR_DIRECTORY_MISSING: "授权资料目录不存在，请重新授权。",
  CONNECTOR_DIRECTORY_CONFLICT: "该店铺已有不同的授权目录，请联系部署管理员。",
  CONNECTOR_DIRECTORY_ALREADY_REGISTERED: "该登录资料已绑定其他店铺。",
  CONNECTOR_CAPACITY_REACHED: "当前部署已达到连接店铺上限。",
  CONNECTOR_REGISTRY_WRITE_FAILED: "店铺已核验，但连接器登记失败，请重试授权。",
  CONNECTOR_REGISTRY_INVALID: "连接器登记资料异常，请联系部署管理员。",
  KNOWLEDGE_PRODUCT_REQUIRED: "请先同步商品，并为商品资料选择对应商品。",
  INVALID_KNOWLEDGE_TITLE: "资料名称不能为空或超过 120 字。",
  INVALID_KNOWLEDGE_TOPICS: "资料类型与适用问题不一致，请重新选择。",
  CONVERSATION_VERSION_CONFLICT: "会话已有更新，请核对最新消息后重新操作。",
  MANUAL_TAKEOVER_REQUIRED: "请先点击对话顶部的「人工接管」，再发送人工回复。",
  QUESTION_NOT_PENDING: "所选咨询已被回答或不属于当前会话，请刷新后重新勾选。",
  LIVE_SEND_SCOPE_EXPIRED:
    "测试发送授权已到期，请联系管理员重新开放该测试会话。",
  SEND_RESULT_UNRESOLVED:
    "上次回复是否送达尚未核实，请先人工核对，再恢复 Agent。",
  CSRF_INVALID: "页面会话已更新，请重试；仍未恢复时重新登录。",
  SESSION_UNAVAILABLE: "暂时无法恢复登录状态，请检查服务连接。",
  PLATFORM_PRODUCTS_UNAVAILABLE:
    "拼多多商品查询未完成，请联系部署管理员刷新商品查询会话后重试。",
  PRODUCT_QUERY_FAILED: "商品查询失败，请稍后重试。",
  PRODUCT_QUERY_LIMIT_EXCEEDED: "商品数量超过本次同步上限，请联系部署管理员。",
  UNRECOGNIZED_PRODUCT_LIST_SCHEMA:
    "拼多多商品数据格式已变化，当前未能安全解析。",
  PRODUCT_QUERY_REJECTED_3000000:
    "拼多多拒绝了商品查询，正在尝试刷新查询会话；请稍后重新同步。",
  PRODUCT_DETAIL_UNAVAILABLE: "商品详情暂不可用，请稍后重试。",
  PRODUCT_DETAIL_SCOPE_MISMATCH: "返回的商品详情与当前店铺不匹配，已停止同步。",
  PRODUCT_DETAIL_SCHEMA_UNSUPPORTED: "商品详情格式已变化，当前未能安全解析。",
  PRODUCT_SESSION_INVALID: "商品查询会话已失效，请重新打开商品同步后重试。",
  PRODUCT_SESSION_REFRESH_FAILED:
    "商品查询会话刷新失败，请确认拼多多登录仍有效后重试。",
  PRODUCT_SESSION_CAPTURE_FAILED:
    "未能从拼多多商家后台捕获查询授权材料，订单统计请求尚未发出。请确认商家后台可正常打开后重试。",
  ORDER_STATS_UNAVAILABLE: "订单状态统计暂时不可用，请稍后重试。",
  ORDER_STATS_SCHEMA_UNSUPPORTED:
    "拼多多订单统计格式已变化，暂时无法安全展示。",
  ORDER_STATS_INCONSISTENT: "拼多多返回的订单统计不一致，请稍后重试。",
  ORDER_STATS_SESSION_UNAVAILABLE:
    "订单查询授权材料缺失，请刷新重试；仍失败时重新登录拼多多店铺。",
  ORDER_STATS_SESSION_REFRESH_FAILED:
    "订单查询会话刷新失败，请检查拼多多登录状态。",
  ORDER_LIST_RATE_LIMITED: "拼多多订单列表查询过于频繁，请稍后再试。",
  PDD_CREDENTIALS_UNAVAILABLE: "拼多多登录资料不可用，请重新授权店铺。",
  ACCOUNT_LOOKUP_FAILED: "无法核对拼多多店铺身份，请重新登录。",
  ACCOUNT_IDENTITY_MISSING: "拼多多未返回完整的店铺身份，请重新登录。",
  AUTH_IDENTITY_MISMATCH: "拼多多登录身份与当前店铺不匹配，请检查绑定。",
  LIVE_SEND_NOT_ENABLED: "当前真实店铺仅开放接收，真实发送尚未启用。",
  MODEL_CONFIG_CONFLICT: "模型配置已被更新，请刷新配置后重试。",
  MODEL_KEY_REQUIRED: "请输入 API Key；更换模型服务域名时需要重新填写。",
  MODEL_KEY_INVALID: "API Key 格式不正确，请检查是否包含空格或换行。",
  MODEL_URL_INVALID:
    "接口地址不正确，请填写 HTTPS 基础地址，不含 /chat/completions。",
  MODEL_TEST_REQUIRED: "请先通过连接与工具调用测试，再启用配置。",
  MODEL_SECRET_UNAVAILABLE: "配置服务暂不可用，请联系系统部署管理员。",
  INVITE_INVALID: "邀请码不正确，请联系店铺管理员。",
  USERNAME_EXISTS: "该账号已存在，请更换账号或直接登录。",
  PASSWORD_TOO_LONG: "密码过长，请使用较短的密码。",
  REGISTRATION_DISABLED: "当前未开放注册，请联系店铺管理员。",
  REGISTRATION_RATE_LIMITED: "注册尝试过于频繁，请稍后再试。",
  REGISTRATION_LIMITER_UNAVAILABLE: "注册服务暂不可用，请稍后再试。",
  DRAFT_VERSION_CONFLICT: "草稿已更新，请查看最新版本后重新审核。",
  CONNECTOR_OFFLINE: "店铺连接已断开，请恢复连接后发送。",
  HUMAN_HANDLING_REQUIRED: "此咨询需要人工接管，不能直接批准 AI 回复。",
  SEND_NOT_RETRYABLE: "当前发送结果不允许重试，请先核实平台记录。",
  RUN_IN_PROGRESS: "草稿正在生成，请稍候。",
  SEND_IN_PROGRESS: "已有回复正在发送，请等待结果确认后再接管。",
  CONVERSATION_TRANSFERRED: "会话已由人工接管，不能再发送此回复。",
  DECISION_CONFLICT: "该版本已有审批结果，请刷新后查看。",
  DRAFT_ALREADY_DECIDED: "该草稿已有审批决定。",
  KNOWLEDGE_VERSION_IMMUTABLE: "知识版本已存在，请使用新的版本号。",
};

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return "登录已失效或账号密码不正确。";
    if (messages[error.code]) return messages[error.code];
  }
  return "操作未完成，请稍后重试。";
}

/** Workbench response models. IDs and timestamps follow packages/contracts/openapi.json. */
export type Decision = "approve" | "reject" | "transfer";
export type PerformAction = (
  work: () => Promise<unknown>,
  message?: string,
) => Promise<void>;

export interface Shop {
  platform_name?: string | null;
  avatar_url?: string | null;
  main_category?: string | null;
  profile_updated_at?: number;
  workspace_id: string;
  workspace_name?: string;
  binding_state: "bound" | "unbound";
  id: string;
  name: string;
  connection_state: string;
  knowledge_version: string | null;
  send_enabled?: boolean;
  connection_error_code?: string | null;
}

export interface ConversationSummary {
  id: string;
  buyer_ref: string;
  state: "open" | "transferred";
  revision: number;
  updated_at: number;
  preview: string | null;
  unread_count: number;
}

export interface Message {
  context_only?: boolean;
  id: string;
  message_type: string;
  direction: "inbound" | "outbound" | "system";
  content: string;
  state: string;
  occurred_at: string | null;
  received_at: string;
  media?: MessageMedia | null;
  workbench_read?: boolean;
  buyer_read_state?: "read" | "unread" | "unknown";
  buyer_read_reason?:
    | "message_id_missing"
    | "receipt_missing"
    | "outside_supported_range"
    | "platform_receipt";
  buyer_read_observed_at?: number | null;
}

export interface MessageMedia {
  kind: "image" | "product";
  image_url?: string | null;
  goods_id?: string | null;
  title?: string | null;
  related_platform_message_id?: string | null;
}

export interface ProductSnapshot {
  checked_at?: number;
  id: string;
  products: string;
  created_at: number;
}

export interface Product {
  category_path?: string[];
  sku_count?: number;
  attributes_count?: number;
  detail_updated_at?: number;
  goods_type?: string;
  goods_id: string | number;
  goods_name: string;
  thumb_url?: string;
  price?: string | null;
  quantity?: number | null;
}

export interface ProductDetail {
  shipping?: {
    mode: string;
    summary: string;
    shipment_limit_seconds: number | null;
    presale_deadline: string | null;
  };
  freight?: {
    status: string;
    free_regions: string[];
    undeliverable_regions: string[];
    paid_rules: {
      regions: string[];
      unit: "piece" | "gram";
      first_units: number;
      first_fee_cents: number;
      additional_units: number;
      additional_fee_cents: number;
      free_min_count: number | null;
      free_min_amount_cents: number | null;
    }[];
  };
  goods_id: string;
  goods_name: string;
  category_path: string[];
  description: string;
  goods_type: string;
  images: string[];
  detail_images: string[];
  sku_count: number;
  skus_truncated: boolean;
  skus: {
    presale_deadline?: string | null;
    shipment_limit_seconds?: number[];
    sku_id: string | null;
    group_price_cents?: number | null;
    single_price_cents?: number | null;
    on_sale?: boolean | null;
    specs: { name: string; value: string }[];
    quantity: number | null;
    thumb_url?: string | null;
  }[];
  attributes: { name: string; values: string[] }[];
  attributes_status: "synced" | "unavailable";
  fetched_at: string;
}

export interface ProductSync {
  skipped?: number;
  updated_at?: number;
  id?: string;
  state: "idle" | "running" | "completed" | "completed_with_errors";
  total: number;
  completed: number;
  failed: number;
}

export interface Citation {
  knowledge_topics?: string[];
  source_authority?: "shop_published";
  citation_role?: "response_guideline";
  document_name?: string;
  source_location?: string;
  title?: string;
  source_type:
    | "product"
    | "shop_policy"
    | "platform_product"
    | "platform_order";
  source_id: string;
  source_version: string;
  evidence_text: string;
  fetched_at?: string;
}

export interface PendingQuestion {
  message_id: string;
  content: string;
  message_type: string;
  created_at: number;
}

export interface ReplyDraft {
  product_cards?: ProductCard[];
  cards_edited?: boolean;
  risk_codes?: string[];
  question_results?: {
    message_id: string;
    outcome: string;
    risk_codes?: string[];
    verification?: VerificationSummary;
  }[];
  covered_questions?: PendingQuestion[];
  conversation_revision: number;
  id: string;
  message_id: string;
  run_id: string;
  revision: number;
  state: string;
  content: string;
  outcome: "draft" | "needs_human" | "insufficient_evidence";
  citations: Citation[];
  knowledge_version: string | null;
}

export interface SendCommand {
  product_cards?: ProductCard[];
  receipt?: {
    parts?: { key: string; kind: string; goods_id?: string; state: string }[];
  };
  error_code?: string | null;
  id: string;
  draft_id: string;
  state: string;
  attempt: number;
}

export interface ProductCard {
  goods_id: string;
  title: string;
  image_url: string | null;
  price: string | null;
  fetched_at: string;
}

export interface VerificationSummary {
  scope?: "non_factual" | "business_facts";
  checks?: {
    supported: boolean;
    issues: string[];
    question_is_social?: boolean;
    contains_business_claims?: boolean;
    contains_action_commitment?: boolean;
  }[];
  repair_attempted?: boolean;
}

export interface AgentRun {
  understanding?: {
    intents?: string[];
    risk_flags?: string[];
    needs_clarification?: boolean;
    requires_order?: boolean;
    order_verified?: boolean;
    product_scope?: "current" | "catalog";
    product_ambiguous?: boolean;
    product_keywords?: string[];
  };
  order_context?: {
    order_id?: string;
    goods_name?: string;
    spec?: string;
    purchased_quantity?: number;
    order_status?:
      | "unknown"
      | "cancelled"
      | "closed"
      | "unpaid"
      | "paid"
      | "awaiting_group"
      | "awaiting_shipment"
      | "shipped"
      | "signed"
      | "completed";
    status_text?: string | null;
    created_at?: string;
    fetched_at?: string;
    status_error_code?: string;
    missing_fields?: string[];
  };
  verification?: VerificationSummary;
  id: string;
  message_id: string;
  conversation_revision: number;
  state: string;
  engine?: string;
  model?: string;
  model_calls?: number;
  history_truncated?: boolean;
  missing_requirements?: string[];
  model_config_version?: string | null;
  tool_trace?: {
    name: string;
    action?: string;
    status: string;
    duration_ms: number;
    retrieval?: {
      mode: string;
      keyword_candidates?: number;
      vector_candidates?: number;
      reranked?: number;
    };
  }[];
}

export interface ConversationDetail extends ConversationSummary {
  pending_questions?: PendingQuestion[];
  send_enabled: boolean;
  manual_replies: {
    id: string;
    content: string;
    reviewer: string;
    created_at: number;
    command_id: string;
    state: string;
    error_code: string | null;
    conversation_revision: number;
  }[];
  messages: Message[];
  drafts: ReplyDraft[];
  send_commands: SendCommand[];
  agent_runs: AgentRun[];
}

export interface Bootstrap {
  active_shop_id: string;
  user: string;
  shops: Shop[];
  conversations: ConversationSummary[];
  roles: string[];
  ai: AiStatus;
}

export interface AiStatus {
  enabled: boolean;
  source: "admin" | "environment";
  model: string;
  configuration_id?: string;
}

export interface ModelVersion {
  id: string;
  version: number;
  base_url: string;
  model: string;
  key_hint: string;
  max_rounds: number;
  timeout_seconds: number;
  test_state: "untested" | "passed" | "failed";
  test_error_code: string | null;
}

export interface ModelSettings {
  revision: number;
  storage_ready: boolean;
  status: AiStatus;
  latest: ModelVersion | null;
  active_id: string | null;
}

export interface KnowledgeEntry {
  valid_from?: string;
  valid_until?: string;
  classification_method?: "rule" | "semantic" | "manual";
  document_name?: string;
  document_sha256?: string;
  source_location?: string;
  chunk_index?: number;
  title?: string;
  source_id: string;
  source_type: "product" | "shop_policy";
  topics: string[];
  text: string;
  goods_id?: string;
}

export interface KnowledgeVersion {
  active?: boolean;
  version_label?: string;
  id: string;
  snapshot: string;
  created_at: number;
}

export interface AuditEvent {
  id: string;
  created_at: number;
  event_type: string;
  actor: string;
  subject_id: string;
  trace_id: string;
}

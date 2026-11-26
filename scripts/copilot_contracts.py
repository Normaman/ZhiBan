"""Export the implemented service contract. Run with the agent-service environment."""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "services/agent-service"))
from copilot.contracts import (  # noqa: E402 - standalone script adds the service package path
    RunRequest,
)


def string(maximum=100):
    return {"type": "string", "minLength": 1, "maxLength": maximum}


def object_schema(properties, required):
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": properties,
        "required": required,
    }


schemas = {
    "Registration": object_schema(
        {
            "username": {"type": "string", "pattern": "^[a-zA-Z0-9_.-]{3,48}$"},
            "password": {"type": "string", "minLength": 12, "maxLength": 72, "writeOnly": True},
            "invite_code": {"type": "string", "minLength": 1, "maxLength": 256, "writeOnly": True},
        },
        ["username", "password", "invite_code"],
    ),
    "RunRequest": RunRequest.model_json_schema(),
    "ManualReply": object_schema(
        {
            "request_id": {"type": "string", "pattern": "^[a-zA-Z0-9_-]{16,100}$"},
            "revision": {"type": "integer", "minimum": 0},
            "content": string(4000),
            "answered_message_ids": {
                "type": "array",
                "maxItems": 200,
                "items": string(),
                "description": "本次人工回复覆盖的本会话待回复消息；仅发送成功后标记已回答，省略表示不清除任何咨询。",
            },
        },
        ["request_id", "revision", "content"],
    ),
    "InboundMessage": object_schema(
        {
            "event_id": string(),
            "shop_id": string(),
            "platform_shop_id": string(),
            "connector_account_id": string(),
            "platform_message_id": {"type": ["string", "null"], "maxLength": 200},
            "buyer_ref": string(200),
            "direction": {"enum": ["inbound", "outbound", "system"]},
            "message_type": string(40),
            "content": {"type": "string", "maxLength": 20000},
            "occurred_at": {"type": ["string", "null"], "format": "date-time"},
            "received_at": {"type": "string", "format": "date-time"},
            "action": {
                "enum": [
                    "reply",
                    "context_only",
                    "handoff",
                    "ignore",
                    "observe",
                    "unknown",
                ]
            },
        },
        [
            "event_id",
            "shop_id",
            "platform_shop_id",
            "connector_account_id",
            "buyer_ref",
            "direction",
            "message_type",
            "content",
            "received_at",
            "action",
        ],
    ),
    "Decision": object_schema(
        {
            "decision": {"enum": ["approve", "reject", "transfer"]},
            "revision": {"type": "integer", "minimum": 1},
            "final_content": {"type": ["string", "null"], "maxLength": 4000},
            "product_ids": {
                "type": "array",
                "maxItems": 3,
                "uniqueItems": True,
                "items": string(200),
                "description": "明确勾选的草稿商品卡片 ID；省略或空数组只发送文字。",
            },
        },
        ["decision", "revision"],
    ),
    "SendResult": object_schema(
        {
            "state": {"enum": ["sent", "send_failed", "send_unknown"]},
            "error_code": {"type": ["string", "null"]},
            "receipt_basis": {"type": "string"},
        },
        ["state"],
    ),
    "Citation": object_schema(
        {
            "knowledge_topics": {"type": "array", "items": {"type": "string"}},
            "source_authority": {"enum": ["shop_published"]},
            "source_type": {
                "enum": ["product", "shop_policy", "platform_product", "platform_order"]
            },
            "source_id": string(200),
            "source_version": string(200),
            "evidence_text": {"type": "string"},
            "fetched_at": {"type": "string", "format": "date-time"},
        },
        ["source_type", "source_id", "source_version", "evidence_text"],
    ),
    "DraftResult": object_schema(
        {
            "run_id": string(),
            "message_id": string(),
            "conversation_revision": {"type": "integer", "minimum": 0},
            "knowledge_version": {"type": ["string", "null"]},
            "outcome": {"enum": ["draft", "needs_human", "insufficient_evidence"]},
            "content": {"type": "string", "maxLength": 4000},
            "citations": {
                "type": "array",
                "items": {"$ref": "#/components/schemas/Citation"},
            },
            "risk_codes": {"type": "array", "items": {"type": "string"}},
            "engine": {
                "enum": [
                    "policy",
                    "extractive",
                    "extractive_fallback",
                    "llm_evidence_selection",
                    "llm_agent",
                ]
            },
            "model": {"type": ["string", "null"]},
            "model_config_version": {"type": ["string", "null"]},
            "model_calls": {"type": "integer", "minimum": 0},
            "history_truncated": {"type": "boolean"},
            "tool_trace": {
                "type": "array",
                "items": object_schema(
                    {
                        "name": {"type": "string"},
                        "status": {"type": "string"},
                        "duration_ms": {"type": "integer", "minimum": 0},
                    },
                    ["name", "status", "duration_ms"],
                ),
            },
        },
        [
            "run_id",
            "message_id",
            "conversation_revision",
            "knowledge_version",
            "outcome",
            "content",
            "citations",
            "risk_codes",
            "engine",
        ],
    ),
}

schemas["ProductCard"] = object_schema(
    {
        "goods_id": string(200),
        "title": string(1000),
        "image_url": {"type": ["string", "null"], "maxLength": 4000},
        "price": {"type": ["string", "null"], "maxLength": 100},
        "fetched_at": {"type": "string", "format": "date-time"},
    },
    ["goods_id", "title", "image_url", "price", "fetched_at"],
)
schemas["DraftCardReplacement"] = object_schema(
    {
        "revision": {"type": "integer", "minimum": 1},
        "product_ids": {"type": "array", "maxItems": 3, "uniqueItems": True, "items": string(200)},
        "content": {"type": ["string", "null"], "maxLength": 4000},
    },
    ["revision", "product_ids"],
)
schemas["DraftResult"]["properties"]["product_cards"] = {
    "type": "array",
    "maxItems": 3,
    "items": {"$ref": "#/components/schemas/ProductCard"},
}
schemas["SendResult"]["properties"]["parts"] = {
    "type": "array",
    "maxItems": 4,
    "items": object_schema(
        {
            "key": string(205),
            "kind": {"enum": ["text", "product_card"]},
            "goods_id": string(200),
            "state": {"enum": ["sent", "send_failed", "send_unknown"]},
            "error_code": {"type": ["string", "null"]},
            "receipt_basis": {"type": "string"},
            "platform_message_id": {"type": "string", "pattern": "^[0-9]{1,200}$"},
        },
        ["key", "kind", "state"],
    ),
}

schemas["ModelConfigurationInput"] = object_schema(
    {
        "revision": {"type": "integer", "minimum": 0},
        "base_url": {"type": "string", "minLength": 1, "maxLength": 1000},
        "model": string(200),
        "api_key": {"type": ["string", "null"], "maxLength": 4096, "writeOnly": True},
        "max_rounds": {"type": "integer", "minimum": 1, "maximum": 8},
        "timeout_seconds": {"type": "integer", "minimum": 5, "maximum": 70},
    },
    ["revision", "base_url", "model", "max_rounds", "timeout_seconds"],
)
schemas["ConfigurationRevision"] = object_schema(
    {"revision": {"type": "integer", "minimum": 0}}, ["revision"]
)
schemas["ModelConfigurationTest"] = object_schema(
    {
        key: value
        for key, value in schemas["ModelConfigurationInput"]["properties"].items()
        if key != "revision"
    },
    ["base_url", "model", "api_key", "max_rounds", "timeout_seconds"],
)


def endpoint(schema, response, internal=True, idempotent=False, params=None):
    op = {
        "security": [{"internalToken": []} if internal else {"reviewer": []}],
        "requestBody": {
            "required": True,
            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/" + schema}}},
        },
        "responses": {
            "200": {
                "description": "Accepted or replayed original result",
                "content": {"application/json": {"schema": response}},
            },
            "401": {"description": "Authentication required"},
            "403": {"description": "Shop or account forbidden"},
            "409": {"description": "Version / idempotency / state conflict"},
            "422": {"description": "Invalid request"},
        },
    }
    if params:
        op["parameters"] = params
    if idempotent:
        op.setdefault("parameters", []).append(
            {
                "name": "Idempotency-Key",
                "in": "header",
                "required": True,
                "schema": {"type": "string"},
            }
        )
    return {"post": op}


contract = {
    "openapi": "3.1.0",
    "info": {
        "title": "PDD Copilot internal and approval contracts",
        "version": "0.1.0",
        "description": "Internal identifiers are business fields, not claims about native PDD fields. See docs/copilot-runbook.md.",
    },
    "paths": {
        "/api/v1/auth/register": {
            "post": {
                "security": [],
                "requestBody": {
                    "required": True,
                    "content": {
                        "application/json": {
                            "schema": {"$ref": "#/components/schemas/Registration"}
                        }
                    },
                },
                "responses": {
                    "201": {
                        "description": "Merchant activation code creates a private workspace and owner; a single-use team invitation joins its workspace as reviewer"
                    },
                    "400": {
                        "description": "Invalid account or password; password UTF-8 length must be <=72 bytes"
                    },
                    "403": {"description": "Invalid invitation"},
                    "409": {"description": "Username exists"},
                    "429": {"description": "Registration attempt limit reached"},
                    "503": {"description": "Registration disabled or rate limiter unavailable"},
                },
            }
        },
        "/internal/v1/pdd/messages": endpoint(
            "InboundMessage",
            {
                "type": "object",
                "required": ["message_id", "conversation_id"],
                "properties": {"message_id": string(), "conversation_id": string()},
            },
            idempotent=True,
        ),
        "/internal/v1/agent-runs": endpoint(
            "RunRequest", {"$ref": "#/components/schemas/DraftResult"}
        ),
        "/api/v1/reply-drafts/{draft_id}/decisions": endpoint(
            "Decision",
            {"type": "object"},
            internal=False,
            params=[{"name": "draft_id", "in": "path", "required": True, "schema": string()}],
        ),
        "/api/v1/conversations/{conversation_id}/manual-replies": endpoint(
            "ManualReply",
            {"type": "object"},
            internal=False,
            params=[
                {"name": "conversation_id", "in": "path", "required": True, "schema": string()}
            ],
        ),
    },
    "components": {
        "securitySchemes": {
            "internalToken": {
                "type": "apiKey",
                "in": "header",
                "name": "X-Internal-Token",
            },
            "reviewer": {"type": "http", "scheme": "basic"},
        },
        "schemas": schemas,
    },
}

model_config_path = "/api/v1/admin/model-config"
view_response = {
    "description": "Redacted configuration metadata only; never contains API key or ciphertext"
}
contract["components"]["securitySchemes"]["administrator"] = {
    "type": "http",
    "scheme": "basic",
    "description": "Requires ADMIN role",
}
contract["paths"][model_config_path] = endpoint(
    "ModelConfigurationInput", {"type": "object"}, internal=False
)
contract["paths"][model_config_path]["get"] = {
    "security": [{"administrator": []}],
    "responses": {"200": view_response, "403": {"description": "Administrator required"}},
}
for action in ["test", "activate", "disable"]:
    path = model_config_path + ("/disable" if action == "disable" else "/{id}/" + action)
    params = (
        None
        if action == "disable"
        else [{"name": "id", "in": "path", "required": True, "schema": string()}]
    )
    contract["paths"][path] = endpoint(
        "ConfigurationRevision", {"type": "object"}, internal=False, params=params
    )
for path, methods in contract["paths"].items():
    if path.startswith(model_config_path):
        for operation in methods.values():
            operation["security"] = [{"administrator": []}]
contract["paths"]["/internal/v1/model-config/test"] = endpoint(
    "ModelConfigurationTest",
    {
        "type": "object",
        "properties": {"passed": {"type": "boolean"}, "error_code": {"type": ["string", "null"]}},
    },
)
contract["paths"]["/internal/v1/shops/{shop_id}/model-config"] = {
    "get": {
        "security": [{"internalToken": []}],
        "parameters": [{"name": "shop_id", "in": "path", "required": True, "schema": string()}],
        "responses": {
            "200": {
                "description": "Private service-only active configuration, may include plaintext api_key; Cache-Control no-store. Never expose to a browser."
            },
            "403": {"description": "Shop forbidden"},
        },
    }
}

schemas["ShopName"] = object_schema({"name": string(100)}, ["name"])
schemas["ProductDetailRequest"] = object_schema({"goods_id": string(200)}, ["goods_id"])
schemas["ShopProfile"] = object_schema(
    {
        "platform_shop_id": string(),
        "connector_account_id": string(),
        "name": string(200),
        "avatar_url": {"type": ["string", "null"], "maxLength": 4000},
        "main_category": {"type": ["string", "null"], "maxLength": 500},
    },
    ["platform_shop_id", "connector_account_id", "name"],
)
contract["paths"]["/internal/v1/shops/{id}/profile"] = endpoint(
    "ShopProfile",
    {"type": "object"},
    params=[{"name": "id", "in": "path", "required": True, "schema": string()}],
)
contract["paths"]["/internal/v1/pdd/products/details"] = endpoint(
    "ProductDetailRequest",
    {
        "type": "object",
        "description": "Verified current shop product details, category path, attributes, specification labels, dated stock snapshot and approved platform image URLs",
    },
)
contract["paths"]["/api/v1/shops/{id}/products/metadata-syncs"] = {
    "post": {
        "security": [{"reviewer": []}],
        "parameters": [
            {"name": "id", "in": "path", "required": True, "schema": string()},
            {"name": "force", "in": "query", "schema": {"type": "boolean", "default": False}},
        ],
        "responses": {
            "200": {
                "description": "Returns the active run or checks owned catalog: reuses unchanged successful details under six hours, otherwise enqueues durable tasks; force bypasses reuse"
            },
            "409": {"description": "Shop not bound or base catalog not synchronized"},
        },
    },
    "get": {
        "security": [{"reviewer": []}],
        "parameters": [{"name": "id", "in": "path", "required": True, "schema": string()}],
        "responses": {
            "200": {
                "description": "Latest run state, updated_at and total/completed/skipped/failed counts"
            }
        },
    },
}
contract["paths"]["/api/v1/shops/{id}/products/{goods_id}/details"] = {
    "get": {
        "security": [{"reviewer": []}],
        "parameters": [
            {"name": "id", "in": "path", "required": True, "schema": string()},
            {"name": "goods_id", "in": "path", "required": True, "schema": string(200)},
        ],
        "responses": {
            "200": {
                "description": "Cached normalized detail or null when not synchronized; inventory is a dated snapshot"
            },
            "403": {"description": "Shop forbidden"},
            "404": {"description": "Product absent from this shop's latest catalog"},
        },
    }
}
schemas["ConnectorBinding"] = object_schema(
    {"platform_shop_id": string(), "connector_account_id": string()},
    ["platform_shop_id", "connector_account_id"],
)
contract["paths"]["/api/v1/admin/shops"] = endpoint(
    "ShopName", {"type": "object", "properties": {"shop_id": string()}}, internal=False
)
contract["paths"]["/api/v1/admin/shop/name"] = endpoint(
    "ShopName", {"type": "object"}, internal=False
)
contract["paths"]["/api/v1/admin/shop/connector-binding"] = endpoint(
    "ConnectorBinding", {"type": "object"}, internal=False
)
contract["paths"]["/api/v1/admin/shop/connector-binding"]["post"]["parameters"] = [
    {
        "name": "X-Connector-Enrollment-Token",
        "in": "header",
        "required": True,
        "description": "Deployment-only enrollment secret in addition to workspace administrator authentication; never sent to the merchant browser.",
        "schema": {"type": "string", "writeOnly": True},
    }
]
for route in [
    "/api/v1/admin/workspace/invitations",
    "/api/v1/admin/workspace/invitations/{id}/revoke",
]:
    params = (
        []
        if "{id}" not in route
        else [{"name": "id", "in": "path", "required": True, "schema": string()}]
    )
    contract["paths"][route] = {
        "post": {
            "security": [{"administrator": []}],
            "parameters": params,
            "responses": {
                "200": {
                    "description": "Invite created (plaintext token returned only once) or revoked"
                },
                "403": {"description": "Workspace administrator required"},
            },
        }
    }
contract["paths"]["/api/v1/admin/workspace"] = {
    "get": {
        "security": [{"administrator": []}],
        "responses": {
            "200": {"description": "Current workspace, shops, members and redacted invitations"}
        },
    }
}
contract["paths"]["/api/v1/messages/{message_id}/agent-runs"] = {
    "post": {
        "security": [{"reviewer": []}],
        "parameters": [{"name": "message_id", "in": "path", "required": True, "schema": string()}],
        "responses": {
            "200": {"description": "Queued a new run for the existing inbound message; no send"},
            "409": {
                "description": "Run in progress, message not eligible, or draft already decided"
            },
            "403": {"description": "Shop membership required"},
        },
    }
}
for route, methods in contract["paths"].items():
    for operation in methods.values():
        if route.startswith("/api/v1/admin/"):
            operation["security"] = [{"administrator": []}]
        if not route.startswith("/api/v1/auth/"):
            operation.setdefault("parameters", []).append(
                {
                    "name": "X-Shop-Id",
                    "in": "header",
                    "required": False,
                    "description": "Select a shop. Browser access requires current workspace membership; trusted internal callers and queued workers retain their pinned shop.",
                    "schema": string(),
                }
            )

schemas["PendingQuestion"] = object_schema(
    {
        "message_id": string(),
        "content": string(20000),
        "message_type": string(40),
        "created_at": {"type": "integer"},
    },
    ["message_id", "content", "message_type", "created_at"],
)
contract["paths"]["/api/v1/conversations/{id}"] = {
    "get": {
        "description": "按店铺权限读取会话。pending_questions 只包含尚无成功发送覆盖的咨询；每份草稿的 covered_questions 是生成批次覆盖清单。生成上下文另含 pending_questions，逐项处理，不以历史窗口截断代替待回复清单。",
        "parameters": [{"name": "id", "in": "path", "required": True, "schema": string()}],
        "responses": {
            "200": {
                "description": "会话详情",
                "content": {
                    "application/json": {
                        "schema": {
                            "type": "object",
                            "properties": {
                                "pending_questions": {
                                    "type": "array",
                                    "items": {"$ref": "#/components/schemas/PendingQuestion"},
                                },
                                "drafts": {
                                    "type": "array",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "covered_questions": {
                                                "type": "array",
                                                "items": {
                                                    "$ref": "#/components/schemas/PendingQuestion"
                                                },
                                            }
                                        },
                                    },
                                },
                            },
                        }
                    }
                },
            }
        },
    }
}
schemas["QuestionUnderstanding"] = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "intents": {
            "type": "array",
            "items": {
                "enum": [
                    "inventory",
                    "product",
                    "price",
                    "shipping",
                    "logistics",
                    "after_sales_policy",
                    "after_sales_action",
                    "social",
                    "unknown",
                ]
            },
        },
        "quantity_meaning": {"enum": ["inventory", "unit_count", "both", "none", "unclear"]},
        "requires_order": {"type": "boolean"},
        "needs_clarification": {"type": "boolean"},
        "risk_flags": {"type": "array", "items": {"type": "string"}},
        "source": {"enum": ["model", "safety_rules", "platform_metadata", "social_rules"]},
        "failed": {"type": "boolean"},
        "order_verified": {"type": "boolean"},
        "product_scope": {"enum": ["current", "catalog"]},
        "product_ambiguous": {"type": "boolean", "description": "历史推荐多款，尚无买家明确选择。"},
        "product_keywords": {
            "type": "array",
            "maxItems": 6,
            "items": {"type": "string", "minLength": 1, "maxLength": 60},
        },
    },
}
schemas["OrderContext"] = {
    "type": "object",
    "additionalProperties": False,
    "description": "已核对当前店铺凭据和会话买家归属的只读订单摘要；缺失字段不得推断。",
    "properties": {
        "order_id": {"type": "string", "maxLength": 200},
        "goods_name": {"type": ["string", "null"]},
        "spec": {"type": ["string", "null"]},
        "purchased_quantity": {"type": ["integer", "null"], "minimum": 1},
        "order_status": {
            "enum": [
                "unknown",
                "cancelled",
                "closed",
                "unpaid",
                "awaiting_group",
                "awaiting_shipment",
                "shipped",
                "signed",
                "completed",
            ]
        },
        "status_text": {"type": ["string", "null"], "maxLength": 100},
        "created_at": {"type": "string", "format": "date-time"},
        "status_error_code": {"type": "string"},
        "missing_fields": {"type": "array", "items": {"type": "string"}},
        "fetched_at": {"type": "string", "format": "date-time"},
    },
}
schemas["DraftResult"]["properties"].update(
    understanding={"$ref": "#/components/schemas/QuestionUnderstanding"},
    order_context={"$ref": "#/components/schemas/OrderContext"},
)

# Preserve the implemented document, evidence and per-question response contracts.
contract["paths"]["/api/v1/messages/{message_id}/agent-runs"]["post"]["parameters"] = [
    {
        "name": "message_id",
        "in": "path",
        "required": True,
        "schema": {"type": "string", "minLength": 1, "maxLength": 100},
    },
    {
        "name": "X-Shop-Id",
        "in": "header",
        "required": False,
        "description": "Select a shop. Browser access requires current workspace membership; "
        "trusted internal callers and queued workers retain their pinned shop.",
        "schema": {"type": "string", "minLength": 1, "maxLength": 100},
    },
    {
        "name": "single",
        "in": "query",
        "schema": {"type": "boolean", "default": False},
        "description": "Explicitly retry only this pending question, including older questions; "
        "decided/sent drafts remain immutable.",
    },
]

contract["paths"]["/api/v1/shops/{id}/knowledge/preview"] = {
    "post": {
        "summary": "Parse document into unpublished chunks; no model call or knowledge write",
        "security": [{"reviewer": []}],
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {
                    "schema": {"$ref": "#/components/schemas/DocumentPreviewRequest"}
                }
            },
        },
        "responses": {
            "200": {
                "description": "Preview only; review warnings before publication",
                "content": {
                    "application/json": {"schema": {"$ref": "#/components/schemas/DocumentPreview"}}
                },
            },
            "422": {"description": "Document rejected; safe DOCUMENT_* error code"},
            "429": {"description": "Two parser processes already busy"},
            "403": {"description": "Shop access denied"},
        },
        "parameters": [
            {"name": "id", "in": "path", "required": True, "schema": {"type": "string"}}
        ],
    }
}

contract["paths"]["/internal/v1/knowledge/preview"] = {
    "post": {
        "summary": "Parse document into unpublished chunks; no model call or knowledge write",
        "security": [{"internalToken": []}],
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {
                    "schema": {"$ref": "#/components/schemas/DocumentPreviewRequest"}
                }
            },
        },
        "responses": {
            "200": {
                "description": "Preview only; review warnings before publication",
                "content": {
                    "application/json": {"schema": {"$ref": "#/components/schemas/DocumentPreview"}}
                },
            },
            "422": {"description": "Document rejected; safe DOCUMENT_* error code"},
            "429": {"description": "Two parser processes already busy"},
            "403": {"description": "Shop access denied"},
        },
    }
}

contract["paths"]["/api/v1/shops/{id}/knowledge"] = {
    "parameters": [{"name": "id", "in": "path", "required": True, "schema": {"type": "string"}}],
    "post": {
        "security": [{"reviewer": []}],
        "summary": "Publish an immutable full knowledge snapshot for this shop",
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {"schema": {"$ref": "#/components/schemas/KnowledgePublish"}}
            },
        },
        "responses": {
            "200": {"description": "Published knowledge_version"},
            "409": {
                "description": "Base version conflict or attempt to overwrite immutable version"
            },
            "422": {"description": "Invalid entry, category, source or product binding"},
            "403": {"description": "Shop access denied"},
        },
    },
}

contract["paths"]["/api/v1/shops/{id}/knowledge/retrieval"] = {
    "get": {
        "security": [{"reviewer": []}],
        "parameters": [
            {"name": "id", "in": "path", "required": True, "schema": {"type": "string"}}
        ],
        "responses": {
            "200": {
                "description": "Local retrieval mode, embedding/reranker "
                "identifiers and pgvector storage; per-query "
                "fallback is in tool traces"
            },
            "403": {"description": "Shop access denied"},
        },
    }
}

contract["components"]["schemas"]["InboundMessage"]["properties"]["media"] = {
    "type": ["object", "null"],
    "additionalProperties": False,
    "properties": {
        "kind": {"enum": ["image", "product"]},
        "image_url": {"type": ["string", "null"], "maxLength": 4000},
        "goods_id": {"type": ["string", "null"], "maxLength": 200},
        "title": {"type": ["string", "null"], "maxLength": 1000},
        "related_platform_message_id": {
            "type": ["string", "null"],
            "pattern": "^[0-9]{1,200}$",
            "description": "已核实 type 41 来源卡 pre_msg_id 指向的咨询编号。",
        },
    },
    "required": ["kind"],
}

contract["components"]["schemas"]["SendResult"]["properties"]["platform_message_id"] = {
    "type": "string",
    "pattern": "^[0-9]{1,200}$",
    "description": "平台发送成功回执中已核实的消息编号；未返回时不猜测。",
}

contract["components"]["schemas"]["Citation"]["properties"]["document_name"] = {
    "type": "string",
    "description": "Optional document provenance; plain text only.",
}

contract["components"]["schemas"]["Citation"]["properties"]["source_location"] = {
    "type": "string",
    "description": "Optional document provenance; plain text only.",
}

contract["components"]["schemas"]["Citation"]["properties"]["title"] = {
    "type": "string",
    "description": "Optional document provenance; plain text only.",
}

contract["components"]["schemas"]["Citation"]["properties"]["citation_role"] = {
    "type": "string",
    "enum": ["response_guideline"],
    "description": "Response preference reference, not business fact evidence.",
}

contract["components"]["schemas"]["DraftResult"]["properties"]["tool_trace"]["items"]["properties"][
    "retrieval"
] = {
    "type": "object",
    "properties": {
        "mode": {"enum": ["hybrid", "bm25", "bm25_degraded"]},
        "keyword_candidates": {"type": "integer"},
        "vector_candidates": {"type": "integer"},
        "reranked": {"type": "integer"},
        "reason": {"type": "string"},
    },
}

contract["components"]["schemas"]["DraftResult"]["properties"]["answered_message_ids"] = {
    "type": "array",
    "maxItems": 8,
    "uniqueItems": True,
    "items": {"type": "string"},
    "description": "Verified subset of run coverage; only these IDs bind to the send command. "
    "Empty for non-sendable outcomes.",
}

contract["components"]["schemas"]["DraftResult"]["properties"]["question_results"] = {
    "type": "array",
    "items": {
        "type": "object",
        "required": ["message_id", "outcome"],
        "properties": {
            "message_id": {"type": "string"},
            "outcome": {"enum": ["draft", "needs_human", "insufficient_evidence"]},
            "risk_codes": {"type": "array", "items": {"type": "string"}},
            "verification": {"$ref": "#/components/schemas/VerificationSummary"},
            "understanding": {"$ref": "#/components/schemas/QuestionUnderstanding"},
        },
    },
}

contract["components"]["schemas"]["DraftResult"]["properties"]["verification"] = {
    "$ref": "#/components/schemas/VerificationSummary"
}

contract["components"]["schemas"]["DocumentPreviewRequest"] = {
    "type": "object",
    "required": ["filename", "content_base64"],
    "properties": {
        "filename": {"type": "string", "minLength": 1, "maxLength": 200},
        "content_base64": {
            "type": "string",
            "minLength": 1,
            "maxLength": 7000000,
            "contentEncoding": "base64",
            "description": "Local file <=5 MiB; "
            "txt/md/csv/docx/xlsx/text PDF. Original "
            "file is not retained.",
        },
    },
}

contract["components"]["schemas"]["DocumentChunk"] = {
    "type": "object",
    "required": [
        "source_id",
        "title",
        "text",
        "document_name",
        "document_sha256",
        "source_location",
        "chunk_index",
    ],
    "properties": {
        "source_id": {"type": "string"},
        "title": {"type": "string", "maxLength": 120},
        "text": {"type": "string", "maxLength": 10000},
        "document_name": {"type": "string"},
        "document_sha256": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
        "source_location": {"type": "string"},
        "chunk_index": {"type": "integer", "minimum": 1},
        "suggested_topic": {
            "enum": [
                "",
                "shipping",
                "policy",
                "spec",
                "recommendation",
                "response_guidelines",
                "faq",
            ]
        },
        "classification_method": {"enum": ["rule", "semantic", "unclassified"]},
        "classification_note": {"type": "string"},
        "is_heading": {
            "type": "boolean",
            "description": "Keep as context; exclude from publication by default.",
        },
    },
}

contract["components"]["schemas"]["DocumentPreview"] = {
    "type": "object",
    "properties": {
        "filename": {"type": "string"},
        "chunks": {
            "type": "array",
            "maxItems": 500,
            "items": {"$ref": "#/components/schemas/DocumentChunk"},
        },
        "warnings": {"type": "array", "items": {"type": "string"}},
        "organization": {
            "type": "object",
            "properties": {
                "mode": {"enum": ["local_rules", "local_rules_semantic"]},
                "unclassified": {"type": "integer", "minimum": 0},
            },
        },
    },
}

contract["components"]["schemas"]["KnowledgeEntry"] = {
    "type": "object",
    "required": ["source_id", "source_type", "topics", "text"],
    "properties": {
        "source_id": {"type": "string", "maxLength": 100},
        "source_type": {"enum": ["product", "shop_policy"]},
        "topics": {
            "type": "array",
            "minItems": 1,
            "items": {
                "enum": [
                    "shipping",
                    "policy",
                    "spec",
                    "recommendation",
                    "response_guidelines",
                    "faq",
                ]
            },
        },
        "text": {"type": "string", "minLength": 1, "maxLength": 10000},
        "title": {"type": "string", "maxLength": 120},
        "goods_id": {
            "type": "string",
            "maxLength": 200,
            "description": "Required for product sources; must belong to this shop.",
        },
        "document_name": {"type": "string"},
        "document_sha256": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
        "source_location": {"type": "string"},
        "chunk_index": {"type": "integer", "minimum": 1},
        "classification_method": {"enum": ["rule", "semantic", "manual"]},
    },
}

contract["components"]["schemas"]["KnowledgePublish"] = {
    "type": "object",
    "required": ["version", "entries"],
    "properties": {
        "version": {"type": "string", "maxLength": 100},
        "base_version": {
            "type": "string",
            "maxLength": 100,
            "description": "Optimistic lock against active knowledge id; "
            "empty string means none. Omit only for "
            "legacy clients.",
        },
        "entries": {
            "type": "array",
            "minItems": 0,
            "maxItems": 500,
            "items": {"$ref": "#/components/schemas/KnowledgeEntry"},
            "description": "Full immutable snapshot; an empty list removes "
            "all active entries and requires base_version. "
            "Historical versions remain retained.",
        },
    },
}

contract["components"]["schemas"]["VerificationSummary"] = {
    "type": "object",
    "properties": {
        "repair_attempted": {"type": "boolean"},
        "scope": {"enum": ["non_factual", "business_facts"]},
        "checks": {
            "type": "array",
            "maxItems": 2,
            "items": {
                "type": "object",
                "required": ["supported", "issues"],
                "properties": {
                    "supported": {"type": "boolean"},
                    "question_is_social": {"type": "boolean"},
                    "contains_business_claims": {"type": "boolean"},
                    "contains_action_commitment": {"type": "boolean"},
                    "issues": {
                        "type": "array",
                        "items": {
                            "enum": [
                                "price_scope",
                                "price_value",
                                "stock_value",
                                "spec_identity",
                                "minimum_comparison",
                                "unsupported_promise",
                                "missing_answer",
                                "citation_missing",
                                "other",
                                "response_guideline",
                                "reply_verbosity",
                                "business_claims",
                            ]
                        },
                    },
                },
            },
        },
    },
}

conversation_properties = contract["paths"]["/api/v1/conversations/{id}"]["get"]["responses"][
    "200"
]["content"]["application/json"]["schema"]["properties"]
conversation_properties["messages"] = {
    "type": "array",
    "items": {
        "type": "object",
        "properties": {
            "context_only": {
                "type": "boolean",
                "description": "明确关联本会话文字咨询的商品来源卡片，仅提供上下文，不作为独立待回复咨询。",
            }
        },
    },
}

path = ROOT / "packages/contracts/openapi.json"
contract["paths"]["/api/v1/reply-drafts/{draft_id}/product-cards"] = endpoint(
    "DraftCardReplacement", {"type": "object"}
)
contract["paths"]["/api/v1/reply-drafts/{draft_id}/product-candidates"] = {
    "get": {
        "responses": {
            "200": {
                "description": "本店最近同步的商品候选，实际应用时重新查询库存。",
                "content": {
                    "application/json": {"schema": {"type": "array", "items": {"type": "object"}}}
                },
            }
        }
    }
}
contract["paths"]["/api/v1/shops/{id}/orders/overview"] = {
    "get": {
        "summary": "Read-only Pinduoduo merchant order status counts",
        "security": [{"reviewer": []}],
        "parameters": [
            {"name": "id", "in": "path", "required": True, "schema": string()},
            {
                "name": "X-Shop-Id",
                "in": "header",
                "required": False,
                "description": "Select a shop within the current workspace.",
                "schema": string(),
            },
        ],
        "responses": {
            "200": {
                "description": "Live merchant-page counts; cached for up to 60 seconds",
                "content": {
                    "application/json": {
                        "schema": {
                            "type": "object",
                            "required": ["shop_id", "counts", "fetched_at", "details_available"],
                            "properties": {
                                "shop_id": string(),
                                "fetched_at": {"type": "string", "format": "date-time"},
                                "details_available": {"type": "boolean", "const": False},
                                "counts": {
                                    "type": "object",
                                    "required": ["unshipped", "shipped", "waitPay", "refunding"],
                                    "properties": {
                                        key: {"type": "integer", "minimum": 0}
                                        for key in ("unshipped", "shipped", "waitPay", "refunding")
                                    },
                                },
                            },
                        }
                    }
                },
            },
            "403": {"description": "Shop membership required"},
            "502": {"description": "Platform session, throttling or response schema unavailable"},
        },
    }
}
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(contract, ensure_ascii=False, indent=2) + "\n")
print("Exported packages/contracts/openapi.json")

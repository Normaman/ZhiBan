"""Local Chat Completions protocol double for cross-service browser tests.

This is NOT a language model or an answer-quality evaluation. Bind only to
loopback; use a dummy key and the fixture model name during isolated tests.
"""

import argparse
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"ok","mode":"synthetic-protocol-fixture"}')

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_error(404)
            return
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        messages = request["messages"]
        reception_fixture = request.get("model") == "fixture-guideline-flow"
        guideline_reply = None
        if reception_fixture:
            for message in messages:
                if message.get("role") != "user":
                    continue
                try:
                    data = json.loads(message.get("content", ""))
                except (ValueError, TypeError):
                    continue
                if not isinstance(data, dict):
                    continue
                for entry in data.get("response_guidelines", []):
                    match = re.search(r"仅回复「([^」]+)」", entry.get("evidence_text", ""))
                    if match:
                        guideline_reply = match.group(1)
        if "意图识别器" in messages[0]["content"]:
            question = json.loads(messages[-1]["content"])["question"]
            intent = (
                "social"
                if reception_fixture
                else "inventory"
                if any(w in question for w in ["库存", "有货", "有几张"])
                else "after_sales_policy"
                if any(w in question for w in ["售后", "退货"])
                else "product"
                if any(w in question for w in ["材质", "规格", "保真", "正品"])
                else "shipping"
            )
            message = {
                "role": "assistant",
                "content": json.dumps(
                    {
                        "intents": [intent],
                        "quantity_meaning": "inventory" if intent == "inventory" else "none",
                        "requires_order": False,
                        "risk_flags": [],
                        "needs_clarification": False,
                    }
                ),
            }
        elif "检查器" in messages[0]["content"]:
            report = {"supported": True, "issues": []}
            if "contains_business_claims" in messages[0]["content"]:
                report.update(
                    question_is_social=True,
                    contains_business_claims=False,
                    contains_action_commitment=False,
                )
            message = {"role": "assistant", "content": json.dumps(report)}
        elif guideline_reply is not None:
            message = {
                "role": "assistant",
                "content": json.dumps(
                    {
                        "outcome": "draft",
                        "content": guideline_reply,
                        "evidence_ids": [],
                        "product_card_ids": [],
                    },
                    ensure_ascii=False,
                ),
            }
        else:
            results = [m for m in messages if m["role"] == "tool"]
            if not results:
                system = messages[0]["content"]
                inventory = "question_intent=inventory" in system
                topic = (
                    "policy"
                    if "after_sales_policy" in system
                    else "faq"
                    if '"product"' in system
                    else "shipping"
                )
                message = {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "fixture-policy-call",
                            "type": "function",
                            "function": {
                                "name": "get_shop_products"
                                if inventory
                                else "search_customer_service_knowledge",
                                "arguments": "{}" if inventory else json.dumps({"topic": topic}),
                            },
                        }
                    ],
                }
            else:
                evidence = json.loads(results[-1]["content"]).get("evidence", [])
                message = {
                    "role": "assistant",
                    "content": json.dumps(
                        {
                            "outcome": "draft" if evidence else "insufficient_evidence",
                            "content": "您好，为您核实到：" + evidence[0]["evidence_text"]
                            if evidence
                            else "请人工核实。",
                            "evidence_ids": [evidence[0]["evidence_id"]] if evidence else [],
                        },
                        ensure_ascii=False,
                    ),
                }
        payload = json.dumps(
            {
                "choices": [
                    {
                        "message": message,
                        "finish_reason": "tool_calls" if message.get("tool_calls") else "stop",
                    }
                ]
            }
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18881)
    args = parser.parse_args()
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()

"""Conservative local rule extraction: split at topic boundaries and retain conditions verbatim."""

import os
import re

TOPICS = {
    "response_guidelines": (
        "回答规范",
        "回复规范",
        "客服规范",
        "话术",
        "礼貌",
        "称呼",
        "语气",
        "措辞",
        "表情符号",
        "禁止承诺",
        "不得承诺",
        "不要承诺",
        "不得辱骂",
        "先道歉",
        "简洁",
        "回复要求",
    ),
    "shipping": (
        "发货",
        "配送",
        "快递",
        "物流",
        "包邮",
        "邮费",
        "运费",
        "偏远",
        "送达",
        "寄出",
        "发出",
        "预售",
        "截单",
        "到货",
    ),
    "policy": (
        "售后",
        "退货",
        "换货",
        "退款",
        "退换",
        "无理由",
        "保修",
        "质保",
        "赔偿",
        "运费险",
        "破损",
        "拒收",
        "质量问题",
        "能退",
        "可退",
        "退吗",
        "退费",
    ),
    "spec": (
        "参数",
        "规格",
        "材质",
        "尺寸",
        "容量",
        "成分",
        "功率",
        "电压",
        "型号",
        "净含量",
        "使用方法",
        "使用说明",
        "保养",
        "清洗",
        "重量",
        "克重",
        "直径",
        "长度",
        "色号",
        "尺码",
        "产地",
        "保质期",
        "面料",
        "颜色",
    ),
    "recommendation": (
        "推荐",
        "选购",
        "搭配",
        "适用人群",
        "对比",
        "建议购买",
        "优先选",
        "适用场景",
        "怎么选",
        "适合",
    ),
    "faq": ("常见问答", "常见问题", "正品", "保真", "真假", "授权证明", "发票", "开票"),
}
HEADING = re.compile(r"^\s*(?:#{1,6}\s+|[一二三四五六七八九十]+[、.．]|\d+[、.．)]\s*|【[^】]+】)")
CONDITION = re.compile(r"^(?:但|若|如|除|其中|例如|以上|此时|否则|具体|以|不含|注意|注[:：])")
PROTOTYPES = {
    "response_guidelines": "客服回答规范：回复的语气、称呼、礼貌、话术、简洁程度和表达禁忌，不得夸大承诺。",
    "shipping": "店铺发货配送规则：仓库地点、发出地区，付款后多久寄出，预售与现货发货时间，快递、配送范围和邮费。",
    "policy": "店铺售后规则：商品坏了如何处理，退货换货、退款、质量问题、保修和退换货运费。",
    "spec": "商品资料：产品的材质尺寸、容量型号、规格参数、成分和使用方法。",
    "recommendation": "商品选购建议：适合什么人或场景，不同商品之间的对比、搭配与推荐。",
    "faq": "店铺常见问答：正品与保真说明、授权证明、发票与开票等已核实的固定问题答案和适用范围。",
}


def classify(text):
    scores = {topic: sum(1 for word in words if word in text) for topic, words in TOPICS.items()}
    if scores["response_guidelines"]:
        return "response_guidelines"
    if scores["policy"] and any(
        word in text for word in ("退货", "换货", "退款", "退换", "无理由", "保修", "质保", "售后")
    ):
        if not any(
            word in text
            for word in ("发货", "配送", "寄出", "发出", "预售", "截单", "到货", "送达", "包邮")
        ):
            scores["shipping"] = 0  # Return freight belongs to after-sales, not delivery promises.
    if scores["spec"] and scores["recommendation"] <= 1:
        scores["recommendation"] = 0
    found = [(topic, score) for topic, score in scores.items() if score]
    if len(found) == 1:
        return found[0][0]
    return ""


def heading_only(text):
    text = text.strip()
    if len(text) > 35 or re.search(r"[。；！？\d]", re.sub(r"^\d+[.、)]", "", text)):
        return False
    clean = re.sub(r"^(?:#{1,6}\s*|[一二三四五六七八九十]+[、.．]|\d+[、.．)])", "", text).strip()
    if clean.startswith("【"):
        return bool(re.fullmatch(r"【[^】]+】", clean)) and bool(classify(clean))
    return bool(classify(clean)) and (
        clean.endswith(("规则", "说明", "政策", "规范", "问答", "：", ":"))
        or clean in {"发货", "售后", "商品规格", "商品参数", "选购建议"}
    )


def split_topic_spans(text):
    # Preserve punctuation and line breaks. A dependent exception cannot be detached from its rule.
    sentences = re.findall(r"[^。！？!?；;，\n]+(?:[。！？!?；;，]+|\n+|$)|\n+", text)
    groups, current, topic = [], "", ""
    mixed = False
    for sentence in sentences:
        stripped = sentence.strip()
        if not stripped:
            current += sentence
            continue
        next_topic = classify(stripped)
        heading = bool(HEADING.match(stripped))
        if current and (heading or (next_topic and topic and next_topic != topic)):
            if not heading and CONDITION.match(stripped):
                mixed = True
                current += sentence
                continue
            groups.append((current.strip(), "" if mixed else topic, mixed))
            current, topic, mixed = "", "", False
        current += sentence
        topic = topic or next_topic
        # Explicit mixed statements stay together and require manual review.
        if (
            not next_topic
            and sum(any(w in stripped for w in words) for words in TOPICS.values()) > 1
        ):
            mixed = True
    if current.strip():
        groups.append((current.strip(), "" if mixed else topic, mixed))
    return groups


def organize(preview):
    entries = []
    heading, heading_location, heading_topic = "", "", ""
    for chunk in preview["chunks"]:
        if heading_only(chunk["text"]):
            heading, heading_location, heading_topic = (
                chunk["text"],
                chunk["source_location"],
                classify(chunk["text"]),
            )
            # Keep the original heading inspectable, but exclude it from publication by default.
            entries.append(
                {
                    **chunk,
                    "source_id": f"doc-{chunk['document_sha256'][:32]}-entry-{len(entries) + 1}",
                    "suggested_topic": "",
                    "classification_method": "unclassified",
                    "classification_note": "标题已保留为下文上下文，默认不单独发布",
                    "is_heading": True,
                }
            )
            if len(entries) > 500:
                raise ValueError("DOCUMENT_TOO_COMPLEX")
            continue
        spans = split_topic_spans(chunk["text"])
        for text, topic, mixed in spans:
            if heading and topic and topic != heading_topic:
                heading, heading_location, heading_topic = "", "", ""
            if heading:
                text = heading + "\n" + text
                topic = topic or (heading_topic if not mixed else "")
            number = len(entries) + 1
            location = chunk["source_location"]
            if heading_location and heading_location != location:
                location = f"{heading_location}～{location}"
            if len(spans) > 1:
                location += f" · 条目 {len(entries) + 1}"
            entry = {
                **chunk,
                "source_id": f"doc-{chunk['document_sha256'][:32]}-entry-{number}",
                "chunk_index": number,
                "text": text,
                "title": (heading or re.sub(r"^\s*(?:#+|[-*•])\s*", "", text).splitlines()[0])[
                    :120
                ],
                "source_location": location[:200],
                "suggested_topic": topic,
                "classification_method": "rule" if topic else "unclassified",
                "classification_note": "含多类内容或关联条件，请核对后分类"
                if mixed
                else ""
                if topic
                else "分类不明确，请手动选择",
                "classification_mixed": mixed,
            }
            entries.append(entry)
            if len(entries) > 500:
                raise ValueError("DOCUMENT_TOO_COMPLEX")
    return {
        **preview,
        "chunks": entries,
        "organization": {
            "mode": "local_rules",
            "unclassified": sum(
                not c["suggested_topic"] and not c.get("is_heading") for c in entries
            ),
        },
    }


def semantic_suggestions(preview):
    candidates = [
        entry
        for entry in preview["chunks"]
        if not entry["suggested_topic"]
        and not entry.get("is_heading")
        and not entry.get("classification_mixed")
        and not heading_only(entry["text"])
    ]
    if not candidates or os.getenv("RAG_ENABLED", "0") != "1":
        return preview
    try:
        from .hybrid import backend

        instance = backend()
        if not instance.lock.acquire(timeout=0.1):
            return preview
        try:
            topics = list(PROTOTYPES)
            vectors = instance.embedding.encode(
                [PROTOTYPES[t] for t in topics] + [e["text"][:400] for e in candidates],
                batch_size=32,
                normalize_embeddings=True,
                show_progress_bar=False,
            )
            for entry, vector in zip(candidates, vectors[len(topics) :]):
                scores = vector @ vectors[: len(topics)].T
                order = scores.argsort()[::-1]
                # Conservative suggestions; similarity is not a probability. Human review remains required.
                if (
                    float(scores[order[0]]) >= 0.60
                    and float(scores[order[0]] - scores[order[1]]) >= 0.06
                ):
                    entry["suggested_topic"] = topics[int(order[0])]
                    entry["classification_method"] = "semantic"
                    entry["classification_note"] = "本地语义分类建议，请核对"
        finally:
            instance.lock.release()
        preview["organization"]["mode"] = "local_rules_semantic"
        preview["organization"]["unclassified"] = sum(
            not c["suggested_topic"] and not c.get("is_heading") for c in preview["chunks"]
        )
    except Exception:
        preview["warnings"].append("语义分类暂不可用，已保留规则分类结果；未分类条目请手动设置。")
    return preview

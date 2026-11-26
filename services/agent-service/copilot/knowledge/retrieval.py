"""Deterministic lexical ranking inside the already authorized version/product scope."""

import math
import re
from collections import Counter


def terms(text):
    result = []
    for word in re.findall(r"[a-z0-9]+|[\u3400-\u9fff]+", text.casefold()):
        if re.fullmatch(r"[\u3400-\u9fff]+", word):
            result.extend(word[i : i + 2] for i in range(len(word) - 1))
            if len(word) == 1:
                result.append(word)
        else:
            result.append(word)
    # Small transparent vocabulary normalization, not semantic embeddings.
    for phrase, canonical in (
        ("寄出", "发货"),
        ("发出", "发货"),
        ("发货", "发货"),
        ("快递", "物流"),
        ("物流", "物流"),
        ("运费", "邮费"),
        ("邮费", "邮费"),
        ("保修", "质保"),
        ("质保", "质保"),
    ):
        if phrase in text:
            result.append(canonical)
    return Counter(result)


def rank(entries, query, limit=5):
    if not query.strip():
        return entries[:limit]
    queries = terms(query)
    documents = [terms(f"{entry.get('title', '')}\n{entry['text']}") for entry in entries]
    if not documents or not queries:
        return []
    average = sum(sum(doc.values()) for doc in documents) / len(documents) or 1
    frequencies = {term: sum(term in doc for doc in documents) for term in queries}
    ranked = []
    for index, doc in enumerate(documents):
        length = sum(doc.values())
        score = 0.0
        for term in queries:
            freq = doc[term]
            if freq:
                inverse = math.log(
                    1 + (len(documents) - frequencies[term] + 0.5) / (frequencies[term] + 0.5)
                )
                score += inverse * freq * 2.2 / (freq + 1.2 * (0.25 + 0.75 * length / average))
        if score:
            ranked.append((-score, index))
    ranked.sort()
    return [entries[index] for _, index in ranked[:limit]]

"""Resolve joined catalog phrases into literal title facets without semantic guessing."""

import unicodedata
import re


def normalize_name(value):
    return "".join(
        char
        for char in unicodedata.normalize("NFKC", str(value or ""))
        if not char.isspace() and unicodedata.category(char) != "Cf"
    ).casefold()


def catalog_query_terms(query, fallback, question):
    """Keep model-proposed catalog terms grounded in the current question."""
    if not query or not query.strip():
        return fallback
    question_text = normalize_name(question)
    candidates = re.split(r"[\s,，、。；;：:！？!?]+", query.strip())
    accepted = []
    for candidate in candidates:
        term = normalize_name(candidate)
        if term and term in question_text and term not in accepted:
            accepted.append(term)
    return accepted[:6] or fallback


def split_known_title_facets(products, phrase):
    """Split a joined phrase only when every character maps to title vocabulary."""
    query = normalize_name(phrase)
    if len(query) < 4:
        return [query] if query else []

    names = [normalize_name(product.get("goods_name", "")) for product in products]
    # Dynamic programming chooses the complete segmentation with the fewest,
    # longest title-backed facets. Unknown words prevent a semantic overmatch.
    best = {len(query): []}
    for start in range(len(query) - 1, -1, -1):
        options = []
        for end in range(start + 2, min(len(query), start + 16) + 1):
            term = query[start:end]
            if end in best and any(term in name for name in names):
                options.append([term, *best[end]])
        if options:
            best[start] = min(
                options, key=lambda parts: (len(parts), tuple(-len(p) for p in parts))
            )
    return best.get(0, [query])


def resolve_catalog_keywords(products, keywords):
    """Use exact matching first; split a fused phrase only into known title facets."""
    names = [normalize_name(product.get("goods_name", "")) for product in products]
    normalized = [normalize_name(word) for word in keywords]
    if any(not word for word in normalized):
        return keywords
    if any(all(word in name for word in normalized) for name in names):
        return keywords

    resolved = []
    for word in normalized:
        resolved.extend(split_known_title_facets(products, word))
    if resolved != normalized and any(all(word in name for word in resolved) for name in names):
        return list(dict.fromkeys(resolved))
    return keywords

"""Pinned reception strategies and style, separate from evidence for business facts."""


def response_guidelines(context):
    return [
        {
            "source_type": "shop_policy",
            "source_id": entry["source_id"],
            "source_version": context.get("knowledge_version") or "",
            "evidence_text": entry["text"],
            "citation_role": "response_guideline",
            **{
                key: entry[key]
                for key in ("title", "document_name", "source_location")
                if key in entry
            },
        }
        for entry in context.get("knowledge", [])
        if entry.get("source_type") == "shop_policy"
        and entry.get("topics") == ["response_guidelines"]
    ]

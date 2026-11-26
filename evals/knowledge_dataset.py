"""Machine-readable gold cases for knowledge import and lifecycle evaluation."""

import argparse
import base64
import csv
import io
import json
import re
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class Document(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_id: str = Field(min_length=1, max_length=200)
    version: str = Field(min_length=1, max_length=100)
    format: Literal["txt", "md", "pdf", "docx", "xlsx", "csv", "image", "binary"] = "txt"
    content: str = Field(max_length=100_000)
    state: Literal["active", "deleted"] = "active"


class Entry(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_id: str = Field(min_length=1, max_length=200)
    version: str = Field(min_length=1, max_length=100)
    category: Literal[
        "product",
        "shipping",
        "after_sales",
        "faq",
        "response_guidelines",
        "other",
    ]
    text: str = Field(min_length=1, max_length=10_000)
    location: str = Field(default="", max_length=500)
    attributes: dict[str, str] = Field(default_factory=dict)
    also_from: list[dict[str, str]] = Field(default_factory=list, max_length=20)
    must_contain: list[str] = Field(default_factory=list, max_length=20)


class KnowledgeCase(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(pattern=r"^[a-z0-9_-]+$")
    scenario: str = Field(min_length=1, max_length=120)
    group_id: str = Field(default="", max_length=120)
    split: Literal["dev", "test"]
    operation: Literal["parse", "deduplicate", "replace", "delete", "conflict"]
    documents: list[Document] = Field(min_length=1, max_length=20)
    expected_entries: list[Entry] = Field(default_factory=list, max_length=200)
    expected_conflict: bool = False
    expected_review_required: bool = False
    expected_deleted_sources: list[str] = Field(default_factory=list)
    expected_error: Literal[
        "empty_document", "unreadable_content", "unsupported_format"
    ] | None = None


def load(path: Path) -> list[KnowledgeCase]:
    cases = [
        KnowledgeCase.model_validate_json(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    if not cases or len({case.id for case in cases}) != len(cases):
        raise ValueError("EMPTY_OR_DUPLICATE_KNOWLEDGE_CASES")
    scenario_splits: dict[str, str] = {}
    group_splits: dict[str, str] = {}
    for case in cases:
        if scenario_splits.setdefault(case.scenario, case.split) != case.split:
            raise ValueError(f"{case.id}: SCENARIO_SPLIT_LEAKAGE")
        if case.group_id and group_splits.setdefault(case.group_id, case.split) != case.split:
            raise ValueError(f"{case.id}: DOCUMENT_GROUP_SPLIT_LEAKAGE")
        docs = {(doc.source_id, doc.version): doc for doc in case.documents}
        if len(docs) != len(case.documents):
            raise ValueError(f"{case.id}: DUPLICATE_DOCUMENT_VERSION")
        for entry in case.expected_entries:
            if (entry.source_id, entry.version) not in docs:
                raise ValueError(f"{case.id}: GOLD_ENTRY_SOURCE_NOT_IN_INPUT")
            if docs[(entry.source_id, entry.version)].state == "deleted":
                raise ValueError(f"{case.id}: GOLD_ENTRY_POINTS_TO_DELETED_DOCUMENT")
            for source in entry.also_from:
                if (source.get("source_id"), source.get("version")) not in docs:
                    raise ValueError(f"{case.id}: GOLD_PROVENANCE_SOURCE_NOT_IN_INPUT")
        if not set(case.expected_deleted_sources) <= {doc.source_id for doc in case.documents}:
            raise ValueError(f"{case.id}: DELETE_TARGET_NOT_IN_INPUT")
        if case.expected_conflict and not case.expected_review_required:
            raise ValueError(f"{case.id}: CONFLICT_MUST_REQUIRE_REVIEW")
        if case.expected_error and case.expected_entries:
            raise ValueError(f"{case.id}: FAILED_IMPORT_MUST_NOT_HAVE_GOLD_ENTRIES")
    return cases


def _normalized(value: str) -> str:
    return re.sub(r"[\s\W_]+", "", value, flags=re.UNICODE).lower()


def _location_fragment(value: str) -> str:
    row = re.fullmatch(r"row:(\d+)", value)
    return f"第{row.group(1)}行" if row else _normalized(value)


def _document_bytes(document: Document) -> bytes:
    if document.format in {"txt", "md", "csv", "binary"}:
        return document.content.encode("utf-8")
    if document.format == "docx":
        from docx import Document as WordDocument

        word = WordDocument()
        for paragraph in document.content.split("\n\n"):
            word.add_paragraph(paragraph)
        output = io.BytesIO()
        word.save(output)
        return output.getvalue()
    if document.format == "xlsx":
        from openpyxl import Workbook

        workbook = Workbook()
        sheet = workbook.active
        sheet.title = "資料"
        for row in csv.reader(io.StringIO(document.content)):
            sheet.append(row)
        output = io.BytesIO()
        workbook.save(output)
        workbook.close()
        return output.getvalue()
    if document.format == "pdf":
        from pypdf import PdfWriter
        from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject

        writer = PdfWriter()
        page = writer.add_blank_page(width=300, height=300)
        if document.content:
            font = DictionaryObject(
                {
                    NameObject("/Type"): NameObject("/Font"),
                    NameObject("/Subtype"): NameObject("/Type1"),
                    NameObject("/BaseFont"): NameObject("/Helvetica"),
                }
            )
            page[NameObject("/Resources")] = DictionaryObject(
                {NameObject("/Font"): DictionaryObject({NameObject("/F1"): writer._add_object(font)})}
            )
            text = document.content.encode("ascii", errors="strict")
            stream = DecodedStreamObject()
            stream.set_data(b"BT /F1 12 Tf 10 100 Td (" + text + b") Tj ET")
            page[NameObject("/Contents")] = writer._add_object(stream)
        output = io.BytesIO()
        writer.write(output)
        return output.getvalue()
    raise ValueError("UNSUPPORTED_EVALUATION_FORMAT")


def score_parse_case(case: KnowledgeCase) -> dict:
    """Run the production extractor and organizer on one parse case."""
    import sys

    agent_root = Path(__file__).resolve().parents[1] / "services/agent-service"
    if str(agent_root) not in sys.path:
        sys.path.insert(0, str(agent_root))
    from copilot.knowledge.documents import DocumentRequest, parse_document
    from copilot.knowledge.organize import organize

    expected_errors = {
        "empty_document": "DOCUMENT_NO_TEXT",
        "unreadable_content": "DOCUMENT_ENCODING",
        "unsupported_format": "DOCUMENT_FORMAT_UNSUPPORTED",
    }
    outputs = []
    errors = []
    for document in case.documents:
        suffix = {
            "txt": ".txt",
            "md": ".md",
            "csv": ".csv",
            "docx": ".docx",
            "xlsx": ".xlsx",
            "pdf": ".pdf",
            "binary": ".bin",
        }[document.format]
        filename = document.source_id + suffix
        raw = _document_bytes(document)
        request = DocumentRequest(
            filename=filename,
            content_base64=base64.b64encode(raw).decode("ascii"),
        )
        try:
            outputs.extend(organize(parse_document(request))["chunks"])
        except Exception as error:
            errors.append(str(error))

    if case.expected_error:
        expected_error = expected_errors[case.expected_error]
        passed = len(errors) == 1 and errors[0] == expected_error and not outputs
        return {
            "passed": passed,
            "expected_error": expected_error,
            "actual_errors": errors,
            "checks": {"expected_parse_error": passed},
            "gold_entries": 0,
            "matched_entries": 0,
            "predicted_entries": 0,
        }

    if errors:
        return {
            "passed": False,
            "expected_error": None,
            "actual_errors": errors,
            "checks": {"all_documents_parsed": False},
            "gold_entries": len(case.expected_entries),
            "matched_entries": 0,
            "predicted_entries": len(outputs),
        }

    topic_map = {
        "product": {"spec", "recommendation"},
        "shipping": {"shipping"},
        "after_sales": {"policy"},
        "faq": {"faq"},
        "response_guidelines": {"response_guidelines"},
        "other": {""},
    }
    gold = [entry for entry in case.expected_entries]
    available = [entry for entry in outputs if not entry.get("is_heading")]
    used: set[int] = set()
    matches = []
    for item in gold:
        doc = next(
            (d for d in case.documents if (d.source_id, d.version) == (item.source_id, item.version)),
            None,
        )
        if doc is None:
            matches.append({"entry": item.text, "matched": False, "reason": "source_missing"})
            continue
        content_terms = item.must_contain or list(item.attributes.values()) or [item.text]
        needle = [_normalized(term) for term in content_terms if term]
        candidates = []
        for index, output in enumerate(available):
            suffix = {
                "txt": ".txt",
                "md": ".md",
                "csv": ".csv",
                "docx": ".docx",
                "xlsx": ".xlsx",
                "pdf": ".pdf",
                "binary": ".bin",
            }[doc.format]
            if index in used or output.get("document_name") != doc.source_id + suffix:
                continue
            haystack = _normalized(output.get("text", ""))
            content_ok = all(term in haystack for term in needle)
            expected_location = _location_fragment(item.location)
            actual_location = _normalized(output.get("source_location", ""))
            location_ok = not expected_location or expected_location in actual_location
            if content_ok and location_ok:
                candidates.append((index, output))
        if not candidates:
            matches.append({"entry": item.text, "matched": False, "reason": "content_or_location_missing"})
            continue
        index, output = candidates[0]
        used.add(index)
        topic_ok = output.get("suggested_topic", "") in topic_map[item.category]
        matches.append(
            {
                "entry": item.text,
                "matched": True,
                "category_expected": item.category,
                "category_actual": output.get("suggested_topic", ""),
                "category_correct": topic_ok,
                "location_expected": item.location,
                "location_actual": output.get("source_location", ""),
            }
        )

    matched = sum(match["matched"] for match in matches)
    category_correct = sum(match.get("category_correct", False) for match in matches)
    mixed_manual = any(
        output.get("classification_mixed") and not output.get("suggested_topic")
        for output in outputs
    )
    checks = {
        "entry_recall": matched == len(gold),
        "category_labels": category_correct == len(gold),
        "no_unexpected_entries": len(used) == len(available),
    }
    if case.expected_review_required:
        checks["ambiguous_content_requires_review"] = mixed_manual
    return {
        "passed": all(checks.values()),
        "expected_error": None,
        "actual_errors": [],
        "checks": checks,
        "gold_entries": len(gold),
        "matched_entries": matched,
        "predicted_entries": len(available),
        "entry_precision": len(used) / len(available) if available else 1.0,
        "entry_recall": matched / len(gold) if gold else 1.0,
        "category_accuracy": category_correct / len(gold) if gold else 1.0,
        "predicted": [
            {
                "text": entry.get("text", ""),
                "category": entry.get("suggested_topic", ""),
                "location": entry.get("source_location", ""),
                "classification_mixed": entry.get("classification_mixed", False),
            }
            for entry in available
        ],
        "matches": matches,
    }


def evaluate(path: Path) -> dict:
    cases = load(path)
    rows = []
    for case in cases:
        if case.operation == "parse":
            rows.append(
                {
                    "case_id": case.id,
                    "scenario": case.scenario,
                    "split": case.split,
                    "status": "scored",
                    "score": score_parse_case(case),
                }
            )
        else:
            rows.append(
                {
                    "case_id": case.id,
                    "scenario": case.scenario,
                    "split": case.split,
                    "status": "not_connected",
                    "reason": "no_database_lifecycle_adapter",
                }
            )
    scored = [row for row in rows if row["status"] == "scored"]
    return {
        "cases": len(cases),
        "scored_cases": len(scored),
        "not_connected_cases": len(rows) - len(scored),
        "auto_pass": sum(row["score"]["passed"] for row in scored),
        "auto_fail": sum(not row["score"]["passed"] for row in scored),
        "data_source": "synthetic_only",
        "results": rows,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--validate",
        type=Path,
        nargs="?",
        const=Path(__file__).with_name("knowledge_ingestion_cases.jsonl"),
        default=Path(__file__).with_name("knowledge_ingestion_cases.jsonl"),
    )
    parser.add_argument("--run", action="store_true", help="Run parse cases through production code")
    parser.add_argument("--output", type=Path, help="Optional JSON report path for --run")
    args = parser.parse_args()
    if args.run:
        report = evaluate(args.validate)
        rendered = json.dumps(report, ensure_ascii=False, indent=2)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered + "\n", encoding="utf-8")
        print(rendered)
        return 0 if report["auto_fail"] == 0 else 1
    cases = load(args.validate)
    print(json.dumps({
        "cases": len(cases),
        "dev": sum(case.split == "dev" for case in cases),
        "test": sum(case.split == "test" for case in cases),
        "operations": sorted({case.operation for case in cases}),
        "data_source": "synthetic_only",
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

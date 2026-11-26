import base64
import io
import zipfile

import pytest
from copilot.knowledge.documents import DocumentRequest, parse_document
from copilot.knowledge.import_service import preview
from copilot.knowledge.retrieval import rank
from fastapi import HTTPException


def request(name, data):
    return DocumentRequest(filename=name, content_base64=base64.b64encode(data).decode())


def test_text_provenance_stable_ids_and_no_lost_content():
    text = "现货48小时发货。" * 500
    a = parse_document(request("规则.md", text.encode()))
    b = parse_document(request("规则.md", text.encode()))
    assert a == b
    assert "".join(c["text"] for c in a["chunks"]) == text
    assert len(a["chunks"]) > 1
    assert a["chunks"][0]["document_name"] == "规则.md"
    assert a["chunks"][0]["source_location"] == "第 1 段"
    assert len({c["source_id"] for c in a["chunks"]}) == len(a["chunks"])


def test_csv_repeats_columns_and_preserves_multiline_values():
    data = '问题,答案\n周末发货吗,"正常发货\n节假日以公告为准"\n运费,按地区计算'.encode("gb18030")
    result = parse_document(request("问答.csv", data))
    assert result["chunks"][0]["text"] == "问题：周末发货吗；答案：正常发货\n节假日以公告为准"
    assert result["chunks"][1]["source_location"] == "CSV · 第 3 行"


def test_docx_document_order_and_table_headers():
    from docx import Document

    document = Document()
    document.add_paragraph("常规发货规则")
    table = document.add_table(rows=2, cols=2)
    table.cell(0, 0).text, table.cell(0, 1).text = "地区", "时效"
    table.cell(1, 0).text, table.cell(1, 1).text = "上海", "48小时"
    document.add_paragraph("预售以页面为准")
    output = io.BytesIO()
    document.save(output)
    result = parse_document(request("说明.docx", output.getvalue()))
    assert [c["text"] for c in result["chunks"]] == [
        "常规发货规则",
        "地区：上海；时效：48小时",
        "预售以页面为准",
    ]


def test_xlsx_preserves_sheet_row_and_rejects_formulas():
    from openpyxl import Workbook

    book = Workbook()
    sheet = book.active
    sheet.title = "规格"
    sheet.append(["颜色", "说明"])
    sheet.append(["白色", "纯棉"])
    output = io.BytesIO()
    book.save(output)
    result = parse_document(request("商品.xlsx", output.getvalue()))
    assert result["chunks"][0]["text"] == "颜色：白色；说明：纯棉"
    assert "规格 · 第 2 行" in result["chunks"][0]["source_location"]
    sheet["B2"] = "=1+1"
    output = io.BytesIO()
    book.save(output)
    with pytest.raises(ValueError, match="DOCUMENT_FORMULAS"):
        parse_document(request("商品.xlsx", output.getvalue()))


def pdf(encrypted=False, text=True):
    from pypdf import PdfWriter
    from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject

    writer = PdfWriter()
    page = writer.add_blank_page(width=300, height=300)
    if text:
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
        stream = DecodedStreamObject()
        stream.set_data(b"BT /F1 12 Tf 10 100 Td (Shipping within 48 hours.) Tj ET")
        page[NameObject("/Contents")] = writer._add_object(stream)
    if encrypted:
        writer.encrypt("test-document-password")
    output = io.BytesIO()
    writer.write(output)
    return output.getvalue()


def test_pdf_page_provenance_and_encryption():
    result = parse_document(request("rules.pdf", pdf()))
    assert "48 hours" in result["chunks"][0]["text"]
    assert result["chunks"][0]["source_location"] == "第 1 页"
    with pytest.raises(ValueError, match="DOCUMENT_ENCRYPTED"):
        parse_document(request("rules.pdf", pdf(encrypted=True)))
    with pytest.raises(ValueError, match="DOCUMENT_NO_TEXT"):
        parse_document(request("scan.pdf", pdf(text=False)))


@pytest.mark.parametrize(
    "name,data,code",
    [
        ("old.doc", b"hello", "DOCUMENT_FORMAT_UNSUPPORTED"),
        ("blank.txt", b"  ", "DOCUMENT_NO_TEXT"),
        ("large.txt", b"a" * (5 * 1024 * 1024 + 1), "DOCUMENT_SIZE_LIMIT"),
        ("binary.txt", b"a\x00b", "DOCUMENT_ENCODING"),
    ],
)
def test_rejected_inputs(name, data, code):
    with pytest.raises(ValueError, match=code):
        parse_document(request(name, data))


def test_archive_expansion_limit():
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("bomb.xml", b"a" * (31 * 1024 * 1024))
    with pytest.raises(ValueError, match="DOCUMENT_TOO_COMPLEX"):
        parse_document(request("bad.docx", output.getvalue()))


async def test_parser_process_success_and_safe_failure():
    result = await preview(request("rules.txt", "48小时发货".encode()))
    assert result["chunks"][0]["text"] == "48小时发货"
    with pytest.raises(HTTPException) as error:
        await preview(request("corrupt.pdf", b"Not a PDF"))
    assert error.value.status_code == 422
    assert error.value.detail == "DOCUMENT_PARSE_FAILED"


def test_rank_finds_late_entry_and_does_not_invent_semantic_match():
    entries = [{"text": f"包装信息编号{i}"} for i in range(8)] + [
        {"text": "新疆偏远地区发货需要五天。"}
    ]
    assert rank(entries, "新疆多久发货？")[0] == entries[-1]
    assert rank(entries, "热水壶容量") == []
    assert rank([{"text": "现货48小时寄出"}], "什么时候发货？")


async def test_tool_retrieval_keeps_version_product_scope_and_document_citation():
    import json
    from unittest.mock import AsyncMock

    from copilot.agent.tool_executor import ToolExecutor
    from copilot.clients.llm import ToolCall

    entries = [
        {
            "source_type": "product",
            "source_id": str(i),
            "goods_id": "ours",
            "text": f"包装颜色编号{i}",
        }
        for i in range(8)
    ]
    entries += [
        {
            "source_type": "product",
            "source_id": "late",
            "goods_id": "ours",
            "text": "杯子容量为500毫升。",
            "document_name": "规格.docx",
            "source_location": "第 3 段",
        },
        {
            "source_type": "product",
            "source_id": "other",
            "goods_id": "other",
            "text": "杯子容量为300毫升。",
        },
    ]
    executor = ToolExecutor(
        {
            "knowledge": entries,
            "knowledge_version": "fixed-version",
            "product_context": {"goods_id": "ours"},
        },
        AsyncMock(),
    )
    call = ToolCall.model_validate(
        {
            "id": "test-lookup",
            "function": {
                "name": "get_product_knowledge",
                "arguments": json.dumps({"query": "容量"}),
            },
        }
    )
    result = json.loads((await executor.execute(call))["content"])
    assert len(result["evidence"]) == 1
    citation = result["evidence"][0]
    assert citation["source_id"] == "late"
    assert citation["source_version"] == "fixed-version"
    assert citation["document_name"] == "规格.docx"
    assert citation["source_location"] == "第 3 段"

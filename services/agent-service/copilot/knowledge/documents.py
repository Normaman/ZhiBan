"""Bounded local extraction. Original files are never retained or sent to a model."""

import base64
import csv
import hashlib
import io
import json
import re
import sys
import zipfile
from pathlib import PurePosixPath

from pydantic import BaseModel, Field

MAX_BYTES = 5 * 1024 * 1024
MAX_TEXT = 200_000


class DocumentRequest(BaseModel):
    filename: str = Field(min_length=1, max_length=200)
    content_base64: str = Field(min_length=1, max_length=7_000_000)


def check_archive(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        infos = archive.infolist()
        if len(infos) > 2000 or sum(i.file_size for i in infos) > 30 * 1024 * 1024:
            raise ValueError("DOCUMENT_TOO_COMPLEX")
        if any(i.flag_bits & 1 for i in infos):
            raise ValueError("DOCUMENT_ENCRYPTED")


def decode(data):
    for encoding in ("utf-8-sig", "gb18030"):
        try:
            text = data.decode(encoding)
            if "\x00" in text:
                break
            return text
        except UnicodeDecodeError:
            pass
    raise ValueError("DOCUMENT_ENCODING")


def extract(filename, data):
    extension = PurePosixPath(filename).suffix.lower()
    warnings = []
    blocks = []
    total = 0

    def add(location, text):
        nonlocal total
        text = text.strip()
        if not text:
            return
        total += len(text)
        if total > MAX_TEXT:
            raise ValueError("DOCUMENT_TOO_COMPLEX")
        blocks.append((location, text))

    def table(rows, location):
        headers = None
        row_count = 0
        for index, row in enumerate(rows, 1):
            if index > 5000 or len(row) > 100:
                raise ValueError("DOCUMENT_TOO_COMPLEX")
            values = [str(v).strip() if v is not None else "" for v in row]
            if not any(values):
                continue
            if headers is None:
                headers = values
                continue
            row_count += 1
            # Repeat headers so each row retains its column relationships after splitting.
            add(
                f"{location} · 第 {index} 行",
                "；".join(
                    f"{headers[i] if i < len(headers) and headers[i] else f'列{i + 1}'}：{v}"
                    for i, v in enumerate(values)
                    if v
                ),
            )
        if headers is not None and not row_count:
            add(location, "；".join(headers))

    if extension in (".txt", ".md"):
        for index, paragraph in enumerate(re.split(r"\n\s*\n", decode(data)), 1):
            add(f"第 {index} 段", paragraph)
    elif extension == ".csv":
        table(csv.reader(io.StringIO(decode(data))), "CSV")
        warnings.append("表格首个非空行按列名解析，请核对列名与每行内容。")
    elif extension == ".docx":
        check_archive(data)
        from docx import Document
        from docx.table import Table

        document = Document(io.BytesIO(data))
        for index, block in enumerate(document.iter_inner_content(), 1):
            if isinstance(block, Table):
                table(([c.text for c in row.cells] for row in block.rows), f"表格 {index}")
            else:
                add(f"第 {index} 段", block.text)
        warnings.append("仅提取正文和表格；图片、批注、页眉页脚未导入。表格首行按列名解析。")
    elif extension == ".xlsx":
        check_archive(data)
        from openpyxl import load_workbook

        workbook = load_workbook(
            io.BytesIO(data), read_only=True, data_only=False, keep_links=False
        )
        try:
            if len(workbook.worksheets) > 30:
                raise ValueError("DOCUMENT_TOO_COMPLEX")
            for sheet in workbook.worksheets:
                if (
                    sheet.max_row
                    and sheet.max_row > 5000
                    or sheet.max_column
                    and sheet.max_column > 100
                ):
                    raise ValueError("DOCUMENT_TOO_COMPLEX")

                def rows():
                    for row in sheet.iter_rows():
                        if any(cell.data_type == "f" for cell in row):
                            raise ValueError("DOCUMENT_FORMULAS")
                        yield [cell.value for cell in row]

                table(rows(), f"工作表 {sheet.title}")
        finally:
            workbook.close()
        warnings.append("每张表首个非空行按列名解析；图片、样式不导入，请核对合并单元格。")
    elif extension == ".pdf":
        from pypdf import PdfReader

        reader = PdfReader(io.BytesIO(data))
        if reader.is_encrypted:
            raise ValueError("DOCUMENT_ENCRYPTED")
        if len(reader.pages) > 100:
            raise ValueError("DOCUMENT_TOO_COMPLEX")
        empty_pages = []
        for index, page in enumerate(reader.pages, 1):
            text = page.extract_text() or ""
            if not text.strip():
                empty_pages.append(index)
            add(f"第 {index} 页", text)
        if empty_pages:
            warnings.append(
                f"有 {len(empty_pages)} 页没有可提取文字，可能是扫描件或空白页；这些页面未导入。"
            )
        warnings.append("PDF 按文字阅读顺序提取，复杂表格和多栏排版可能错位，请核对预览。")
    else:
        raise ValueError("DOCUMENT_FORMAT_UNSUPPORTED")
    if not blocks:
        raise ValueError("DOCUMENT_NO_TEXT")
    return blocks, warnings


def parse_document(request):
    data = base64.b64decode(request.content_base64, validate=True)
    if not data or len(data) > MAX_BYTES:
        raise ValueError("DOCUMENT_SIZE_LIMIT")
    filename = request.filename.replace("\\", "/").split("/")[-1]
    digest = hashlib.sha256(data).hexdigest()
    blocks, warnings = extract(filename, data)
    chunks = []
    for location, text in blocks:
        # Keep paragraph/row/page provenance; split only overlong blocks, never drop text.
        while text:
            end = min(1600, len(text))
            if len(text) > end:
                boundary = max(text.rfind("\n", 800, end), text.rfind("。", 800, end))
                if boundary >= 800:
                    end = boundary + 1
            part, text = text[:end], text[end:]
            number = len(chunks) + 1
            chunks.append(
                {
                    "text": part.strip(),
                    "title": f"{filename} · {location}"[:120],
                    "source_id": f"doc-{digest[:32]}-{number}",
                    "document_name": filename,
                    "document_sha256": digest,
                    "source_location": location,
                    "chunk_index": number,
                }
            )
            if len(chunks) > 500:
                raise ValueError("DOCUMENT_TOO_COMPLEX")
    return {"filename": filename, "chunks": chunks, "warnings": warnings}


def main():
    # Run the parser in a separate process: a damaged PDF cannot stall the API worker.
    import resource

    resource.setrlimit(resource.RLIMIT_AS, (768 * 1024 * 1024, 768 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_CPU, (15, 15))
    resource.setrlimit(resource.RLIMIT_FSIZE, (0, 0))
    try:
        request = DocumentRequest.model_validate_json(sys.stdin.buffer.read(7_001_000))
        result = parse_document(request)
    except Exception as error:
        code = str(error)
        result = {
            "error": code
            if code.startswith("DOCUMENT_") and re.fullmatch(r"[A-Z_]+", code)
            else "DOCUMENT_PARSE_FAILED"
        }
    sys.stdout.write(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()

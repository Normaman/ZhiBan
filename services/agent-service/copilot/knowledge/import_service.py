"""Limit parser concurrency, execution time and process lifetime."""

import asyncio
import copy
import json
import os
import sys

from fastapi import HTTPException

from .documents import DocumentRequest
from .organize import organize, semantic_suggestions

_slots = asyncio.Semaphore(2)


async def preview(request: DocumentRequest):
    try:
        await asyncio.wait_for(_slots.acquire(), timeout=0.1)
    except (TimeoutError, asyncio.TimeoutError):
        raise HTTPException(429, "DOCUMENT_PARSER_BUSY") from None
    process = None
    try:
        env = {key: os.environ[key] for key in ("PATH", "LANG", "PYTHONPATH") if key in os.environ}
        env.update(OPENBLAS_NUM_THREADS="1", OMP_NUM_THREADS="1")
        process = await asyncio.create_subprocess_exec(
            sys.executable,
            "-m",
            "copilot.knowledge.documents",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
            env=env,
        )
        output, _ = await asyncio.wait_for(
            process.communicate(request.model_dump_json().encode()), 20
        )
        if process.returncode or not output:
            raise HTTPException(422, "DOCUMENT_TOO_COMPLEX")
        result = json.loads(output)
        if "error" in result:
            raise HTTPException(422, result["error"])
        try:
            result = organize(result)
        except ValueError as error:
            raise HTTPException(422, "DOCUMENT_TOO_COMPLEX") from error
        try:
            return await asyncio.wait_for(
                asyncio.to_thread(semantic_suggestions, copy.deepcopy(result)), 8
            )
        except (TimeoutError, asyncio.TimeoutError):
            result["warnings"].append(
                "语义分类耗时较长，已保留规则分类结果；未分类条目请手动设置。"
            )
            return result
    except (TimeoutError, asyncio.TimeoutError):
        raise HTTPException(422, "DOCUMENT_TOO_COMPLEX") from None
    finally:
        if process and process.returncode is None:
            process.kill()
            await process.wait()
        _slots.release()

"""Recognize stock quantities without confusing them with units per purchase."""

import re
import unicodedata


def asks_authenticity(text: str) -> bool:
    value = re.sub(r"[\s?？!！。,.，]", "", unicodedata.normalize("NFKC", text))
    return bool(
        re.fullmatch(
            r"(?:请问)?(?:这个|这款|这件|这张|该商品|这件商品|这个商品)?(?:是真的吗|是不是真的|是正品吗|是不是正品|保真吗|是否保真|真假|真伪)",
            value,
        )
    )


def asks_inventory(text: str) -> bool:
    text = re.sub(r"\s+", "", unicodedata.normalize("NFKC", text)).casefold()
    if re.search(
        r"库存|有货|剩余|还剩|\bstock\b|\bin.?stock\b|(?:有|是|是否).{0,3}现货|现货[吗么?？]", text
    ):
        return True
    # An explicit package/purchase unit changes the meaning of “有几张”.
    if re.search(r"每[件份包盒套袋]|[一1][件份包盒套袋]|包含|内含|装几|装多少|组成", text):
        return False
    return bool(
        re.search(
            r"(?:有|剩)(?:几|多少)(?:张|件|个|枚|片|包|盒|套|袋|本|条|双|瓶|支|卷)(?=[？?。！!，,吗呢啊呀]|$)",
            text,
        )
    )

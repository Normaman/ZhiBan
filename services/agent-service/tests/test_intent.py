import pytest
from copilot.agent.intent import asks_inventory


@pytest.mark.parametrize(
    "question",
    [
        "这个有几张",
        "这款有多少张？",
        "还剩几枚",
        "目前有几件呢",
        "这是现货吗",
        "一包有5片，还有库存吗",
    ],
)
def test_available_quantities_mean_inventory(question):
    assert asks_inventory(question)


@pytest.mark.parametrize(
    "question",
    [
        "每件有几张",
        "1件有多少张",
        "一包几片",
        "里面包含几枚",
        "这个有几张图片",
        "现货什么时候发货",
        "由几张卡片组成",
    ],
)
def test_package_units_and_other_counts_do_not_mean_inventory(question):
    assert not asks_inventory(question)

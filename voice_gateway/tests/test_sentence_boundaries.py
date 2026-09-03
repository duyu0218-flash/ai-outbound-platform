"""Contract tests run against the installed, controlled Pipecat distribution."""

import asyncio
import builtins
import importlib.metadata
import importlib.util
import io
import socket

import pytest

from pipecat.utils.prewarm import warm_deferred_imports
from pipecat.utils.string import match_endofsentence
from pipecat.utils.text.base_text_aggregator import AggregationType
from pipecat.utils.text.simple_text_aggregator import SimpleTextAggregator


@pytest.mark.parametrize("text,first", [
    ("", ""), ("   ", ""), ("流式半句还没说完", ""),
    ("您好。下一句", "您好。"), ("你好！再见", "你好！"),
    ("请问？可以；下一句", "请问？"), ("可以；下一句", "可以；"),
    ("Hello world. Next", "Hello world."), ("Wait… Next", "Wait…"),
    ("Really?! Next", "Really?!"), ("Wait... Next", "Wait..."),
    ('He said "Hello." Next', 'He said "Hello."'),
    ("他说：“您好！”然后", "他说：“您好！”"),
    ("价格为29.99元。下一句", "价格为29.99元。"),
    ("The price is $29.99. Next", "The price is $29.99."),
    ("The price is 29.9", ""), ("version 1.8.1", ""),
    ("Dr. Smith is here. Next", "Dr. Smith is here."),
    ("Mr. Jones met Mrs. Green. Next", "Mr. Jones met Mrs. Green."),
    ("Use e.g. apples and i.e. examples. Next", "Use e.g. apples and i.e. examples."),
    ("U.S. policy is clear. Next", "U.S. policy is clear."),
    ("J. R. Smith arrived. Next", "J. R. Smith arrived."),
    ("1. First item. Next", "1. First item."),
    ("Email a.b@example.com. Next", "Email a.b@example.com."),
    ("Visit https://example.com/path. Next", "Visit https://example.com/path."),
    ("  Hello world. Next", "  Hello world."),
])
def test_sentence_offset(text, first):
    assert match_endofsentence(text) == len(first)


async def collect(chunks):
    aggregator = SimpleTextAggregator()
    result = []
    for chunk in chunks:
        result.extend([item.text async for item in aggregator.aggregate(chunk)])
    tail = await aggregator.flush()
    if tail:
        result.append(tail.text)
    return result


@pytest.mark.parametrize("text,expected", [
    ("您好。价格29.99元！请问需要吗？剩余半句", ["您好。", "价格29.99元！", "请问需要吗？", "剩余半句"]),
    ("Dr. Smith paid $29.99. Next sentence! Final fragment", ["Dr. Smith paid $29.99.", "Next sentence!", "Final fragment"]),
    ('He said "Hello!" Next?! Wait... Done', ['He said "Hello!"', "Next?!", "Wait...", "Done"]),
    ("U.S. policy follows e.g. rules. Next", ["U.S. policy follows e.g. rules.", "Next"]),
    ("J. R. Smith arrived. 再见！", ["J. R. Smith arrived.", "再见！"]),
    ("这是没结束的半句", ["这是没结束的半句"]),
])
def test_chunk_boundaries_do_not_change_sentences(text, expected):
    async def scenario():
        assert await collect([text]) == expected
        assert await collect(list(text)) == expected
        for split in range(len(text) + 1):
            assert await collect([text[:split], text[split:]]) == expected
    asyncio.run(scenario())


def test_decimal_and_abbreviation_wait_for_lookahead():
    async def scenario():
        agg = SimpleTextAggregator()
        for chunk in ["Dr.", " ", "Smith paid $29.", "99"]:
            assert [x async for x in agg.aggregate(chunk)] == []
        assert [x.text async for x in agg.aggregate(". Next")] == ["Dr. Smith paid $29.99."]
        assert (await agg.flush()).text == "Next"
        assert await agg.flush() is None
    asyncio.run(scenario())


def test_interrupt_and_reset_drop_stale_fragments():
    async def scenario():
        agg = SimpleTextAggregator()
        assert [x async for x in agg.aggregate("旧回复29.")] == []
        await agg.handle_interruption()
        assert await agg.flush() is None
        assert [x.text async for x in agg.aggregate("新回复。后续")] == ["新回复。"]
        await agg.reset()
        assert await agg.flush() is None
    asyncio.run(scenario())


def test_token_mode_is_unchanged():
    async def scenario():
        agg = SimpleTextAggregator(aggregation_type=AggregationType.TOKEN)
        assert [x.text async for x in agg.aggregate("29.")] == ["29."]
        assert [x.text async for x in agg.aggregate("99")] == ["99"]
        assert await agg.flush() is None
    asyncio.run(scenario())


def test_segmentation_and_warmup_do_not_touch_files_or_network(monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError("sentence processing attempted I/O")

    monkeypatch.setattr(builtins, "open", forbidden)
    monkeypatch.setattr(io, "open", forbidden)
    monkeypatch.setattr(socket, "socket", forbidden)
    monkeypatch.setattr(socket, "create_connection", forbidden)
    warm_deferred_imports()
    assert match_endofsentence("Dr. Smith说您好。下一句") == len("Dr. Smith说您好。")
    assert match_endofsentence("Dr. " * 1000 + "Done. Next") == len("Dr. " * 1000 + "Done.")


def test_nltk_is_not_installed_or_importable():
    assert importlib.metadata.version("pipecat-ai") == "1.8.1+outbound.1"
    assert importlib.util.find_spec("nltk") is None
    with pytest.raises(importlib.metadata.PackageNotFoundError):
        importlib.metadata.version("nltk")

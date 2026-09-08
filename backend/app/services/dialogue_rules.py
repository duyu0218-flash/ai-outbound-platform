"""Conservative deterministic decisions; uncertainty must never become consent."""
from __future__ import annotations

import re
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo


def normalized(text: str) -> str:
    return re.sub(r"\s+", "", text).lower()


def clauses(text: str) -> list[str]:
    return [x for x in re.split(r"[，,。.!！;；]|(?:不对[，,]?|改成|更正为)", normalized(text)) if x]


def keyword_match(text: str, keyword: str) -> bool:
    text, keyword = normalized(text), normalized(keyword)
    if not keyword:
        return False
    for match in re.finditer(re.escape(keyword), text):
        prefix = text[max(0, match.start()-8):match.start()]
        if not re.search(r"(?:不|没|别|无需|无须|不用|不要|不想|不能|不必|not|don't|no)(?:再|要|需要|想|用|给我|帮我|转)?$", prefix):
            return True
    return False


def classify(text: str) -> str:
    content = normalized(text)
    parts = clauses(text)
    last = parts[-1] if parts else content
    # Explicit requests to stop contact dominate any apparent positive words.
    if re.search(r"别(?:再)?(?:给我)?打|不要再(?:联系|打)|停止(?:联系|打)|删除(?:我的)?号码|取消订阅|不要联系|勿扰|stopcalling|donotcall", content):
        if not re.search(r"(?:没说|不是说|没有说).{0,4}(?:别|不要)|(?:如果|假如).*(?:别再打|不要再)", content):
            return "stop_contact"
    if re.search(r"打错(?:了|电话|号码)?|不是本人|不是机主|不是我的号码", last):
        return "wrong_person"
    if re.search(r"(?:取消|不要|不用).{0,5}(?:预约|回拨|回电)", last):
        return "cancel_callback"
    if re.search(r"(?:明天|后天|今天|下周|\d{4}-\d{2}-\d{2}).*(?:再打|回电|回拨|联系)|(?:回电|回拨|再打).*(?:明天|后天|\d{1,2}点)", last):
        return "callback"
    if re.search(r"(?:不要|不用|别|不必).{0,4}(?:转|人工|客服|坐席)", last):
        return "decline_handoff"
    if any(keyword_match(last, word) for word in ("转人工", "找人工", "人工客服", "人工", "坐席")):
        return "handoff"
    if re.search(r"(?:先别|不要|别|不用).{0,3}(?:挂|结束)", last):
        return "continue"
    if re.fullmatch(r"(?:好的|好|谢谢|那就|嗯)*[，,]?(?:再见|拜拜|挂了|结束通话)(?:吧|了|谢谢)*", last):
        return "end"
    if re.search(r"稍等|等一下|等一会|稍后", last):
        return "wait"
    if re.search(r"电话(?:助理|助手)|(?:滴|嘀)声后(?:请)?留言|语音信箱|请输入分机|转接分机", content):
        # Quoted or questioned announcements are not machine classification.
        if re.search(r"你是|你说|是不是|什么|吗|？|\?", content):
            return "unclear"
        return "machine_candidate"
    if "？" in content or "?" in content or re.search(r"吗[呢啊呀]?$|是不是|是否|你说的是|什么|为什么|多少钱", last):
        return "question"
    if re.search(r"不需要|不考虑|没兴趣|不感兴趣|不要了|不愿意|不卖|不买", last):
        return "rejected"
    if re.search(r"可以听|你说|听一下|了解一下|先了解", last):
        return "permission_to_listen"
    if re.search(r"(?:有|我有|确实有).{0,6}(?:出售|卖车|购买|买车|办理).{0,4}(?:计划|需求|意向)|(?:我|现在|确实)(?:想|要|需要|愿意)(?:卖车|出售|购买|买车|办理)|^(?:我)?(?:现在)?(?:需要|有兴趣|愿意)$", last):
        return "interested"
    if re.fullmatch(r"(?:对|是|是的|确认|同意|可以|好的|好|没错|确定)[的了吧啊]*", last):
        return "affirm"
    if re.fullmatch(r"(?:不|不是|不对|否|不要|不同意|取消)[的了吧啊]*", last):
        return "deny"
    return "unclear" if not content else "continue"


def callback_time(text: str, now: datetime, timezone: str) -> datetime | None:
    """Only exact, unambiguous dates and times; fuzzy times require clarification."""
    zone = ZoneInfo(timezone)
    local = now.replace(tzinfo=ZoneInfo('UTC')).astimezone(zone) if now.tzinfo is None else now.astimezone(zone)
    match = re.search(r"(\d{4}-\d{2}-\d{2})[ T](\d{1,2}):(\d{2})", text)
    if match:
        try:
            result = datetime.fromisoformat(f'{match[1]}T{int(match[2]):02}:{match[3]}').replace(tzinfo=zone)
        except ValueError:
            return None
    else:
        words = {'一':1,'二':2,'两':2,'三':3,'四':4,'五':5,'六':6,'七':7,'八':8,'九':9,'十':10,'十一':11,'十二':12}
        match = re.search(r"(今天|明天|后天)(上午|下午|晚上|早上|中午)?([\d一二两三四五六七八九十]+)点(?:(半)|(\d{1,2})分)?", text)
        if not match:
            return None
        hour = int(match[3]) if match[3].isdigit() else words.get(match[3], -1)
        if not 0 <= hour <= 23 or (match[2] is None and 1 <= hour <= 12):
            return None
        if match[2] in {'下午','晚上'} and hour < 12:
            hour += 12
        minute = 30 if match[4] else int(match[5] or 0)
        if minute > 59:
            return None
        result = (local + timedelta(days={'今天':0,'明天':1,'后天':2}[match[1]])).replace(hour=hour,minute=minute,second=0,microsecond=0)
    if result <= local or result > local + timedelta(days=90):
        return None
    return result.astimezone(ZoneInfo('UTC')).replace(tzinfo=None)

package com.duozhi.outbound.ai.service;

import com.duozhi.outbound.common.support.Values;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.*;

/** Deterministic baseline rules; ambiguous replies never imply consent. */
public final class DialogueRules {
    private DialogueRules() {}

    private static boolean has(String text, String regex) {
        return Pattern.compile(regex).matcher(text).find();
    }

    public static String normalized(String text) {
        return text.replaceAll("(?U)\\s+", "").toLowerCase(Locale.ROOT);
    }

    public static boolean keyword(String text, String keyword) {
        text = normalized(text);
        keyword = normalized(keyword);
        if (keyword.isEmpty()) return false;
        Matcher found = Pattern.compile(Pattern.quote(keyword)).matcher(text);
        while (found.find()) {
            String prefix = text.substring(Math.max(0, found.start() - 8), found.start());
            if (!has(prefix, "(?:不|没|别|无需|无须|不用|不要|不想|不能|不必|not|don't|no)(?:再|要|需要|想|用|给我|帮我|转)?$"))
                return true;
        }
        return false;
    }

    public static String classify(String text) {
        String content = normalized(text), last = content;
        for (String part : content.split("[，,。.!！;；]|(?:不对[，,]?|改成|更正为)"))
            if (!part.isEmpty()) last = part;
        if (has(
                        content,
                        "别(?:再)?(?:给我)?打|不要再(?:联系|打)|停止(?:联系|打)|删除(?:我的)?号码|取消订阅|不要联系|勿扰|stopcalling|donotcall")
                && !has(content, "(?:没说|不是说|没有说).{0,4}(?:别|不要)|(?:如果|假如).*(?:别再打|不要再)"))
            return "stop_contact";
        if (has(last, "打错(?:了|电话|号码)?|不是本人|不是机主|不是我的号码")) return "wrong_person";
        if (has(last, "(?:取消|不要|不用).{0,5}(?:预约|回拨|回电)")) return "cancel_callback";
        if (has(
                last,
                "(?:明天|后天|今天|下周|\\d{4}-\\d{2}-\\d{2}).*(?:再打|回电|回拨|联系)|(?:回电|回拨|再打).*(?:明天|后天|\\d{1,2}点)"))
            return "callback";
        if (has(last, "(?:不要|不用|别|不必).{0,4}(?:转|人工|客服|坐席)")) return "decline_handoff";
        for (String word : Arrays.asList("转人工", "找人工", "人工客服", "人工", "坐席"))
            if (keyword(last, word)) return "handoff";
        if (has(last, "(?:先别|不要|别|不用).{0,3}(?:挂|结束)")) return "continue";
        if (last.matches("(?:好的|好|谢谢|那就|嗯)*[，,]?(?:再见|拜拜|挂了|结束通话)(?:吧|了|谢谢)*")) return "end";
        if (has(last, "稍等|等一下|等一会|稍后")) return "wait";
        if (has(content, "电话(?:助理|助手)|(?:滴|嘀)声后(?:请)?留言|语音信箱|请输入分机|转接分机"))
            return has(content, "你是|你说|是不是|什么|吗|？|\\?") ? "unclear" : "machine_candidate";
        if (has(content, "[？?]") || has(last, "吗[呢啊呀]?$|是不是|是否|你说的是|什么|为什么|多少钱")) return "question";
        if (has(last, "不需要|不考虑|没兴趣|不感兴趣|不要了|不愿意|不卖|不买")) return "rejected";
        if (has(last, "可以听|你说|听一下|了解一下|先了解")) return "permission_to_listen";
        if (has(
                last,
                "(?:有|我有|确实有).{0,6}(?:出售|卖车|购买|买车|办理).{0,4}(?:计划|需求|意向)|(?:我|现在|确实)(?:想|要|需要|愿意)(?:卖车|出售|购买|买车|办理)|^(?:我)?(?:现在)?(?:需要|有兴趣|愿意)$"))
            return "interested";
        if (last.matches("(?:对|是|是的|确认|同意|可以|好的|好|没错|确定)[的了吧啊]*")) return "affirm";
        if (last.matches("(?:不|不是|不对|否|不要|不同意|取消)[的了吧啊]*")) return "deny";
        return content.isEmpty() ? "unclear" : "continue";
    }

    public static LocalDateTime callbackTime(String text, Instant now, String timezone) {
        ZoneId zone = ZoneId.of(timezone);
        ZonedDateTime local = now.atZone(zone), result;
        Matcher exact =
                Pattern.compile("(\\d{4}-\\d{2}-\\d{2})[ T](\\d{1,2}):(\\d{2})").matcher(text);
        try {
            if (exact.find())
                result =
                        LocalDate.parse(exact.group(1))
                                .atTime(
                                        Integer.parseInt(exact.group(2)),
                                        Integer.parseInt(exact.group(3)))
                                .atZone(zone);
            else {
                Matcher relative =
                        Pattern.compile(
                                        "(今天|明天|后天)(上午|下午|晚上|早上|中午)?([\\d一二两三四五六七八九十]+)点(?:(半)|(\\d{1,2})分)?")
                                .matcher(text);
                if (!relative.find()) return null;
                Map<String, Integer> words =
                        Values.map(
                                "一", 1, "二", 2, "两", 2, "三", 3, "四", 4, "五", 5, "六", 6, "七", 7, "八",
                                8, "九", 9, "十", 10, "十一", 11, "十二", 12);
                String raw = relative.group(3);
                int hour =
                        raw.matches("\\d+") ? Integer.parseInt(raw) : words.getOrDefault(raw, -1);
                if (hour < 0 || hour > 23 || relative.group(2) == null && hour >= 1 && hour <= 12)
                    return null;
                if (Values.set("下午", "晚上").contains(relative.group(2)) && hour < 12) hour += 12;
                int minute =
                        relative.group(4) != null
                                ? 30
                                : relative.group(5) == null
                                        ? 0
                                        : Integer.parseInt(relative.group(5));
                int days =
                        "今天".equals(relative.group(1)) ? 0 : "明天".equals(relative.group(1)) ? 1 : 2;
                result = local.toLocalDate().plusDays(days).atTime(hour, minute).atZone(zone);
            }
            if (!result.isAfter(local) || result.isAfter(local.plusDays(90))) return null;
            return LocalDateTime.ofInstant(result.toInstant(), ZoneOffset.UTC);
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }
}

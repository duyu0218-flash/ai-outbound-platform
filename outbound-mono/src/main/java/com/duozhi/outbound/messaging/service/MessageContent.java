package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.integration.support.Support;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Canonical, versioned content shared by console, journeys, call rules and the external API. */
public final class MessageContent {
    public static final Set<String> FORMS = Set.of("sms", "rcs", "yuexin", "oem_ip");
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]{0,63})}}");

    private MessageContent() {}

    public static Map<String, Object> validate(Map<String, Object> payload) {
        String form = Messaging.required(payload, "form", 16);
        if (!FORMS.contains(form)) throw new ApiError(422, "消息形态无效");
        Messaging.required(payload, "body", 4000);
        var result = new LinkedHashMap<>(payload);
        Object raw = payload.getOrDefault("rich", Map.of());
        var rich = Messaging.object(raw);
        if (form.equals("sms") && !rich.isEmpty()) throw new ApiError(422, "普通短信不支持富媒体卡片");
        if (!Set.of("title", "asset_id", "actions", "cards").containsAll(rich.keySet()))
            throw new ApiError(422, "存在不支持的富媒体字段");
        validateCard(rich);
        if (rich.containsKey("cards")) {
            if (!(rich.get("cards") instanceof List<?> cards)
                    || cards.isEmpty()
                    || cards.size() > 10) throw new ApiError(422, "轮播卡片应为 1 至 10 张");
            for (Object card : cards) {
                var c = Messaging.object(card);
                if (!Set.of("title", "body", "asset_id", "actions").containsAll(c.keySet()))
                    throw new ApiError(422, "卡片字段无效");
                Messaging.required(c, "title", 128);
                Messaging.required(c, "body", 1000);
                validateCard(c);
            }
        }
        result.put("rich", rich);
        return result;
    }

    private static void validateCard(Map<String, Object> card) {
        if (card.containsKey("title") && !Objects.toString(card.get("title"), "").isEmpty())
            Messaging.required(card, "title", 128);
        if (card.containsKey("asset_id")
                && !Messaging.required(card, "asset_id", 64).matches("asset_[a-f0-9]{32}"))
            throw new ApiError(422, "素材编号无效");
        if (card.containsKey("actions")) {
            if (!(card.get("actions") instanceof List<?> actions) || actions.size() > 5)
                throw new ApiError(422, "每张卡片最多 5 个按钮");
            var codes = new HashSet<String>();
            for (Object action : actions) {
                var a = Messaging.object(action);
                if (!Set.of("code", "label", "type", "value").containsAll(a.keySet()))
                    throw new ApiError(422, "按钮字段无效");
                String code = Messaging.required(a, "code", 64),
                        type = Messaging.required(a, "type", 16);
                Messaging.required(a, "label", 32);
                String value = Messaging.required(a, "value", 1024);
                if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !codes.add(code))
                    throw new ApiError(422, "按钮编号无效或重复");
                if (!Set.of("url", "phone", "reply").contains(type))
                    throw new ApiError(422, "按钮类型无效");
                if (type.equals("url")) safeUrl(value);
                if (type.equals("phone") && !value.matches("\\+?[0-9]{3,20}"))
                    throw new ApiError(422, "按钮电话号码无效");
                if (value.contains("{{")) throw new ApiError(422, "按钮目标必须为审核后的固定值");
            }
        }
    }

    public static void safeUrl(String value) {
        try {
            URI u = URI.create(value);
            if (!"https".equals(u.getScheme())
                    || u.getHost() == null
                    || u.getUserInfo() != null
                    || value.length() > 2048) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new ApiError(422, "链接须为有效 HTTPS 地址");
        }
    }

    public static Set<String> assets(Map<String, Object> template) {
        var ids = new LinkedHashSet<String>();
        var rich = Messaging.object(template.getOrDefault("rich", Map.of()));
        if (rich.get("asset_id") instanceof String id) ids.add(id);
        if (rich.get("cards") instanceof List<?> cards)
            for (Object card : cards) {
                var c = Messaging.object(card);
                if (c.get("asset_id") instanceof String id) ids.add(id);
            }
        return ids;
    }

    public static Map<String, Object> render(
            Map<String, Object> template, Map<String, Object> variables, String brand) {
        var validated = validate(template);
        var names = variableNames(validated);
        var rich = Messaging.object(validated.getOrDefault("rich", Map.of()));
        if (!variables.keySet().equals(names)) throw new ApiError(422, "模板变量不匹配");
        for (String name : names)
            if (Messaging.required(variables, name, 512).contains("{{"))
                throw new ApiError(422, "变量值不能包含模板占位符");
        String body = replace(validated.get("body").toString(), variables);
        if (brand == null
                || brand.isBlank()
                || brand.length() > 128
                || brand.matches("(?s).*[【】\\r\\n].*")) throw new ApiError(422, "发送签名无效");
        var renderedRich = new LinkedHashMap<>(rich);
        if (rich.get("title") instanceof String title)
            renderedRich.put("title", replace(title, variables));
        if (rich.get("cards") instanceof List<?> cards) {
            var rendered = new ArrayList<Map<String, Object>>();
            for (Object card : cards) {
                var c = new LinkedHashMap<>(Messaging.object(card));
                for (String field : List.of("title", "body"))
                    c.put(field, replace(c.get(field).toString(), variables));
                rendered.add(c);
            }
            renderedRich.put("cards", rendered);
        }
        String text = "【" + brand + "】" + body;
        if (text.length() > 4000 || Support.json(renderedRich).length > 32768)
            throw new ApiError(422, "渲染后的内容过长");
        return Map.of("form", validated.get("form"), "text", text, "rich", renderedRich);
    }

    public static Set<String> variableNames(Map<String, Object> validated) {
        var names = new HashSet<String>();
        collect(validated.get("body"), names);
        var rich = Messaging.object(validated.getOrDefault("rich", Map.of()));
        collect(rich.get("title"), names);
        if (rich.get("cards") instanceof List<?> cards)
            for (Object card : cards) {
                var c = Messaging.object(card);
                collect(c.get("title"), names);
                collect(c.get("body"), names);
            }
        return names;
    }

    public static Map<String, Object> selectVariables(
            Map<String, Object> template, Map<String, Object> source) {
        var selected = new LinkedHashMap<String, Object>();
        for (String name : variableNames(template))
            selected.put(name, source.getOrDefault(name, ""));
        return selected;
    }

    private static void collect(Object value, Set<String> names) {
        if (value instanceof String s) {
            var m = VARIABLE.matcher(s);
            while (m.find()) names.add(m.group(1));
        }
    }

    private static String replace(String text, Map<String, Object> vars) {
        for (var entry : vars.entrySet())
            text = text.replace("{{" + entry.getKey() + "}}", entry.getValue().toString());
        if (text.contains("{{")) throw new ApiError(422, "模板变量无效");
        return text;
    }

    /** CMPP transport uses UCS-2; pricing follows its 140-byte payload and 6-byte UDH. */
    public static int smsUnits(String text) {
        if (text.codePoints().anyMatch(cp -> cp > 0xffff))
            throw new ApiError(422, "当前 CMPP 编码不支持表情等非 BMP 字符");
        return text.length() <= 70 ? 1 : (text.length() + 66) / 67;
    }
}

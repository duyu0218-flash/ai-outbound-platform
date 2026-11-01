package com.duozhi.outbound.marketing.outcome;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.MarketingStore;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Per-tenant, versioned thresholds and phrase libraries. Version 0 is the built-in default kept in
 * code; stored versions are DRAFT until published, and the highest PUBLISHED version is active.
 * Stored configs are merged over the defaults so a key added later always has a value.
 */
@Service
public class OutcomeRules {
    static final String DEFAULTS =
            """
            {
              "ring_timeout_sec": 45,
              "quick_reject_sec": 3,
              "instant_hangup_sec": 3,
              "short_hangup_sec": 10,
              "min_valid_chars": 2,
              "min_asr_confidence": 0.5,
              "review_confidence": 0.7,
              "assistant_llm_enabled": true,
              "assistant_llm_confidence": 0.8,
              "intent_llm_enabled": true,
              "intent_llm_confidence": 0.7,
              "filler_words": ["喂", "嗯", "啊", "哦", "额", "呃", "哈", "唉", "你好", "您好"],
              "sip": {
                "no_answer": [480, 408],
                "busy": [486, 600],
                "rejected": [603],
                "invalid": [404, 484, 410, 604]
              },
              "hangup_causes": {
                "no_answer": ["NO_ANSWER", "NO_USER_RESPONSE", "NOANSWER"],
                "busy": ["USER_BUSY"],
                "rejected": ["CALL_REJECTED"],
                "invalid": ["UNALLOCATED_NUMBER", "INVALID_NUMBER_FORMAT", "NUMBER_CHANGED", "NO_ROUTE_DESTINATION"],
                "power_off": ["SUBSCRIBER_ABSENT"],
                "line_fail": ["NORMAL_TEMPORARY_FAILURE", "NETWORK_OUT_OF_ORDER", "DESTINATION_OUT_OF_ORDER", "RECOVERY_ON_TIMER_EXPIRE", "GATEWAY_DOWN", "SWITCH_CONGESTION", "NORMAL_CIRCUIT_CONGESTION", "SERVICE_UNAVAILABLE", "BEARERCAPABILITY_NOTAVAIL", "INCOMPATIBLE_DESTINATION", "MEDIA_TIMEOUT", "PROGRESS_TIMEOUT"]
              },
              "carrier_phrases": {
                "power_off": ["已关机", "关机", "不在服务区", "无法接通", "暂时无法接通"],
                "suspended": ["已停机", "停机", "欠费", "暂停服务", "已暂停"],
                "invalid": ["是空号", "空号", "号码不存在", "号码有误", "查证后再拨"],
                "busy": ["正在通话中", "用户正忙", "暂时无法接听您的电话请稍后再拨"]
              },
              "assistant_phrases": ["我是机主的智能助理", "机主的智能助理", "机主暂时无法接听", "机主现在不方便接听", "我是他的语音助手", "智能助理", "语音助手", "有什么可以帮您转达", "我会帮您转达", "请问您找机主有什么事"],
              "voicemail_phrases": ["请在提示音后留言", "语音信箱", "请留言", "留言结束请挂机"],
              "not_owner_phrases": ["打错了", "不是本人", "我不是机主", "不是他本人", "你找错人了", "他不在", "她不在", "没有这个人"],
              "refuse_hard_phrases": ["别再打了", "不要再打", "再打我就投诉", "投诉你们", "骚扰电话", "拉黑", "报警", "删除我的号码", "别给我打电话"],
              "intent": {
                "A": ["怎么办理", "我要办", "可以办理", "马上办", "怎么申请", "发给我", "加微信", "好的我需要", "什么时候能到账", "帮我办"],
                "B": ["考虑一下", "了解一下", "发个短信", "有什么优惠", "再说吧", "回头再看", "具体是什么", "多少钱", "利息多少"],
                "C": ["不需要", "没兴趣", "暂时不用", "用不上", "不用了", "没需要"],
                "D": ["别再打了", "不要再打", "投诉", "拉黑", "骚扰", "报警", "删除我的号码"],
                "nodes": {"A": [], "B": [], "C": [], "D": []},
                "analysis": {
                  "qualified_lead": "A",
                  "callback_confirmed": "A",
                  "interested": "B",
                  "rejected": "C",
                  "stop_contact": "D"
                }
              }
            }
            """;

    private final MarketingStore db;
    private final JsonSupport json;
    private final Map<String, Object> defaults;

    public OutcomeRules(MarketingStore db, JsonSupport json) {
        this.db = db;
        this.json = json;
        this.defaults = Collections.unmodifiableMap(json.object(DEFAULTS));
    }

    /** An immutable view of one rule version with typed accessors. */
    public record Rule(int version, Map<String, Object> config) {
        public double num(String key) {
            Object v = config.get(key);
            return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
        }

        public boolean flag(String key) {
            return Boolean.TRUE.equals(config.get(key));
        }

        public List<String> list(String... path) {
            Object v = config;
            for (String p : path) v = v instanceof Map<?, ?> m ? m.get(p) : null;
            if (!(v instanceof Collection<?> c)) return List.of();
            return c.stream().map(Object::toString).filter(s -> !s.isBlank()).toList();
        }

        public Set<Integer> codes(String... path) {
            Set<Integer> out = new HashSet<>();
            for (String s : list(path))
                try {
                    out.add(Integer.parseInt(s.replaceAll("\\.0$", "")));
                } catch (NumberFormatException ignored) {
                }
            return out;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> map(String... path) {
            Object v = config;
            for (String p : path) v = v instanceof Map<?, ?> m ? m.get(p) : null;
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }
    }

    public Rule builtIn() {
        return new Rule(0, defaults);
    }

    public Rule active(long tenant) {
        Map<String, Object> row =
                db.one(
                        "SELECT version,config FROM mk_outcome_rule WHERE tenant_id=? AND state='PUBLISHED' ORDER BY version DESC LIMIT 1",
                        tenant);
        return row == null ? builtIn() : toRule(row);
    }

    public Rule version(long tenant, int version) {
        if (version == 0) return builtIn();
        Map<String, Object> row =
                db.one(
                        "SELECT version,config FROM mk_outcome_rule WHERE tenant_id=? AND version=?",
                        tenant,
                        version);
        if (row == null) throw new ApiError(404, "规则版本不存在");
        return toRule(row);
    }

    private Rule toRule(Map<String, Object> row) {
        return new Rule(
                ((Number) row.get("version")).intValue(),
                merge(defaults, json.object(row.get("config").toString())));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> over) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        over.forEach(
                (k, v) -> {
                    if (v instanceof Map<?, ?> m && base.get(k) instanceof Map<?, ?> b)
                        out.put(k, merge((Map<String, Object>) b, (Map<String, Object>) m));
                    else if (v != null) out.put(k, v);
                });
        return out;
    }

    public List<Map<String, Object>> versions(long tenant) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r :
                db.rows(
                        "SELECT version,state,note,created_by,created_at,published_at FROM mk_outcome_rule WHERE tenant_id=? ORDER BY version DESC",
                        tenant)) out.add(new LinkedHashMap<>(r));
        return out;
    }

    public Map<String, Object> describe(long tenant) {
        Rule active = active(tenant);
        Map<String, Object> draft =
                db.one(
                        "SELECT version,config,note FROM mk_outcome_rule WHERE tenant_id=? AND state='DRAFT' ORDER BY version DESC LIMIT 1",
                        tenant);
        return JsonSupport.map(
                "active_version",
                active.version(),
                "active",
                active.config(),
                "draft",
                draft == null
                        ? null
                        : JsonSupport.map(
                                "version",
                                draft.get("version"),
                                "note",
                                draft.get("note"),
                                "config",
                                merge(defaults, json.object(draft.get("config").toString()))),
                "defaults",
                defaults,
                "versions",
                versions(tenant));
    }

    /** Creates a draft (copy of the active version unless a config is given); one draft at most. */
    public Map<String, Object> draft(
            long tenant, Map<String, Object> config, String note, long by) {
        Map<String, Object> existing =
                db.one(
                        "SELECT version FROM mk_outcome_rule WHERE tenant_id=? AND state='DRAFT' ORDER BY version DESC LIMIT 1",
                        tenant);
        Map<String, Object> merged =
                config == null ? active(tenant).config() : validate(merge(defaults, config));
        if (existing != null) {
            int v = ((Number) existing.get("version")).intValue();
            if (config != null || note != null) save(tenant, v, merged, note);
            return JsonSupport.map("version", v, "state", "DRAFT");
        }
        int next =
                (int)
                                db.count(
                                        "SELECT COALESCE(MAX(version),0) FROM mk_outcome_rule WHERE tenant_id=?",
                                        tenant)
                        + 1;
        db.write(
                "INSERT INTO mk_outcome_rule(tenant_id,version,state,config,note,created_by) VALUES(?,?,'DRAFT',?,?,?)",
                tenant,
                next,
                json.json(merged),
                note,
                by);
        return JsonSupport.map("version", next, "state", "DRAFT");
    }

    public void save(long tenant, int version, Map<String, Object> config, String note) {
        Map<String, Object> merged = validate(merge(defaults, config));
        int n =
                db.write(
                        "UPDATE mk_outcome_rule SET config=?,note=COALESCE(?,note) WHERE tenant_id=? AND version=? AND state='DRAFT'",
                        json.json(merged),
                        note,
                        tenant,
                        version);
        if (n == 0) throw new ApiError(409, "只能修改草稿版本");
    }

    public int publish(long tenant, int version) {
        int n =
                db.write(
                        "UPDATE mk_outcome_rule SET state='PUBLISHED',published_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND version=? AND state='DRAFT'",
                        tenant,
                        version);
        if (n == 0) throw new ApiError(409, "只能发布草稿版本");
        db.write(
                "UPDATE mk_outcome_rule SET state='ARCHIVED' WHERE tenant_id=? AND state='PUBLISHED' AND version<>?",
                tenant,
                version);
        return version;
    }

    /** Adds a voice-assistant phrase to the tenant's draft (created from the active version). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> addAssistantPhrase(long tenant, String phrase, long by) {
        String p = phrase == null ? "" : phrase.strip();
        if (p.length() < 4 || p.length() > 60) throw new ApiError(422, "话术长度应为 4-60 个字");
        int version = ((Number) draft(tenant, null, "新增语音助手话术", by).get("version")).intValue();
        Rule rule = version(tenant, version);
        Map<String, Object> config = new LinkedHashMap<>(rule.config());
        List<String> phrases = new ArrayList<>(rule.list("assistant_phrases"));
        boolean added = !phrases.contains(p);
        if (added) phrases.add(p);
        config.put("assistant_phrases", phrases);
        save(tenant, version, config, null);
        return JsonSupport.map("version", version, "phrase", p, "added", added);
    }

    private static final List<String> NUMBERS =
            List.of(
                    "ring_timeout_sec",
                    "quick_reject_sec",
                    "instant_hangup_sec",
                    "short_hangup_sec",
                    "min_valid_chars",
                    "min_asr_confidence",
                    "review_confidence",
                    "assistant_llm_confidence",
                    "intent_llm_confidence");

    static Map<String, Object> validate(Map<String, Object> config) {
        for (String k : NUMBERS) {
            Object v = config.get(k);
            double d;
            try {
                d = v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
            } catch (RuntimeException e) {
                throw new ApiError(422, "规则参数 " + k + " 必须是数字");
            }
            double max = k.endsWith("_confidence") ? 1 : 600;
            if (d < 0 || d > max) throw new ApiError(422, "规则参数 " + k + " 超出范围");
            config.put(k, d);
        }
        if (((Number) config.get("instant_hangup_sec")).doubleValue()
                > ((Number) config.get("short_hangup_sec")).doubleValue())
            throw new ApiError(422, "秒挂阈值不能大于短通话阈值");
        return config;
    }
}

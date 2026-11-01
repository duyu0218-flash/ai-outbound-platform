package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.marketing.common.ChannelSender;
import com.duozhi.outbound.marketing.common.OutcomeCodes;
import java.math.BigDecimal;
import java.util.*;

/**
 * A journey version: a DAG of nodes. JSON shape:
 *
 * <pre>
 * {"start":"n1","nodes":[
 *   {"id":"n1","type":"sms","name":"首条短信","sms":{"applicationId":"..","templateId":"..",
 *      "productId":null,"senderId":null,"variables":{},"landingUrl":"https://..","domainPool":null},
 *      "next":"n2"},
 *   {"id":"n2","type":"wait","hours":24,"next":"n3"},
 *   {"id":"n3","type":"condition","codes":["LINK_CLICKED"],"windowHours":0,"yes":"n4","no":"n5"},
 *   {"id":"n4","type":"call","campaignId":12,"next":"end"},
 *   {"id":"n5","type":"sms","sms":{..},"next":"end"},
 *   {"id":"end","type":"end"}]}
 * </pre>
 *
 * A condition is true when any of its codes was recorded for the member since it joined the
 * journey. It waits up to {@code windowHours} after the member reaches it (touch events wake it
 * early); on timeout the member takes {@code no}, the default branch.
 */
public record JourneyDefinition(String start, Map<String, Node> nodes) {
    public static final Set<String> TYPES = Set.of("sms", "call", "wait", "condition", "end");

    public record Node(
            String id,
            String type,
            String name,
            String next,
            String yes,
            String no,
            double hours,
            List<String> codes,
            double windowHours,
            Map<String, Object> sms,
            long campaignId) {
        public boolean sends() {
            return "sms".equals(type) || "call".equals(type);
        }

        public List<String> successors() {
            List<String> out = new ArrayList<>();
            if ("condition".equals(type)) {
                out.add(yes);
                out.add(no);
            } else if (!"end".equals(type)) out.add(next);
            return out;
        }

        @SuppressWarnings("unchecked")
        public ChannelSender.Sms smsRequest() {
            Object vars = sms.get("variables");
            return new ChannelSender.Sms(
                    str(sms.get("applicationId")),
                    str(sms.get("productId")),
                    str(sms.get("templateId")),
                    str(sms.get("senderId")),
                    vars instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(),
                    str(sms.get("landingUrl")),
                    str(sms.get("domainPool")));
        }
    }

    public boolean sends() {
        return nodes.values().stream().anyMatch(Node::sends);
    }

    public Node node(String id) {
        Node n = nodes.get(id);
        if (n == null) throw new IllegalStateException("unknown journey node " + id);
        return n;
    }

    /** Parses and validates; every problem is reported in Chinese for the editor. */
    @SuppressWarnings("unchecked")
    public static JourneyDefinition parse(Map<String, Object> raw) {
        if (raw == null) throw new ApiError(422, "活动流程不能为空");
        Object list = raw.get("nodes");
        if (!(list instanceof List<?> items) || items.isEmpty())
            throw new ApiError(422, "活动流程至少需要一个节点");
        if (items.size() > 50) throw new ApiError(422, "节点数量不能超过 50 个");
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) throw new ApiError(422, "节点格式无效");
            Map<String, Object> n = (Map<String, Object>) m;
            String id = str(n.get("id"));
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}"))
                throw new ApiError(422, "节点编号只能包含字母、数字、_ 或 -");
            if (nodes.containsKey(id)) throw new ApiError(422, "节点编号重复：" + id);
            String type = str(n.get("type"));
            if (!TYPES.contains(type)) throw new ApiError(422, "节点 " + id + " 的类型无效");
            String label = "节点 " + id;
            double hours = number(n.get("hours"), 0);
            double window = number(n.get("windowHours"), 0);
            List<String> codes = new ArrayList<>();
            Map<String, Object> sms = Map.of();
            long campaign = 0;
            switch (type) {
                case "wait" -> {
                    if (hours <= 0 || hours > 24 * 60)
                        throw new ApiError(422, label + "：等待时长应在 0 到 1440 小时之间");
                }
                case "condition" -> {
                    if (n.get("codes") instanceof List<?> cs)
                        for (Object c : cs) codes.add(String.valueOf(c));
                    if (codes.isEmpty()) throw new ApiError(422, label + "：请选择判断的结果码");
                    for (String c : codes)
                        if (!OutcomeCodes.ALL.contains(c))
                            throw new ApiError(422, label + "：未知结果码 " + c);
                    if (window < 0 || window > 24 * 60)
                        throw new ApiError(422, label + "：等待窗口应在 0 到 1440 小时之间");
                }
                case "sms" -> {
                    if (!(n.get("sms") instanceof Map<?, ?> s))
                        throw new ApiError(422, label + "：缺少短信配置");
                    sms = (Map<String, Object>) s;
                    if (str(sms.get("applicationId")) == null || str(sms.get("templateId")) == null)
                        throw new ApiError(422, label + "：请填写消息应用和短信模板");
                    String landing = str(sms.get("landingUrl"));
                    if (landing != null && !landing.matches("https?://\\S{3,1000}"))
                        throw new ApiError(422, label + "：落地页地址无效");
                }
                case "call" -> {
                    campaign = (long) number(n.get("campaignId"), 0);
                    if (campaign <= 0) throw new ApiError(422, label + "：请填写外呼任务编号");
                }
                default -> {}
            }
            nodes.put(
                    id,
                    new Node(
                            id,
                            type,
                            str(n.get("name")),
                            str(n.get("next")),
                            str(n.get("yes")),
                            str(n.get("no")),
                            hours,
                            List.copyOf(codes),
                            window,
                            sms,
                            campaign));
        }
        String start = str(raw.get("start"));
        if (start == null) start = nodes.keySet().iterator().next();
        if (!nodes.containsKey(start)) throw new ApiError(422, "起始节点不存在");
        for (Node n : nodes.values())
            for (String s : n.successors()) {
                if (s == null)
                    throw new ApiError(
                            422,
                            "节点 "
                                    + n.id()
                                    + (n.type().equals("condition")
                                            ? " 需要同时设置“满足”和“不满足”分支"
                                            : " 缺少下一步"));
                if (!nodes.containsKey(s))
                    throw new ApiError(422, "节点 " + n.id() + " 指向不存在的节点 " + s);
            }
        var def = new JourneyDefinition(start, Collections.unmodifiableMap(nodes));
        def.checkAcyclic();
        return def;
    }

    private void checkAcyclic() {
        Map<String, Integer> mark = new HashMap<>();
        Deque<String[]> stack = new ArrayDeque<>();
        for (String root : nodes.keySet()) {
            if (mark.containsKey(root)) continue;
            stack.push(new String[] {root, "0"});
            while (!stack.isEmpty()) {
                String[] top = stack.peek();
                String id = top[0];
                if (top[1].equals("0")) {
                    mark.put(id, 1);
                    top[1] = "1";
                    for (String s : node(id).successors()) {
                        Integer state = mark.get(s);
                        if (state != null && state == 1)
                            throw new ApiError(422, "活动流程不能形成循环（" + id + " → " + s + "）");
                        if (state == null) stack.push(new String[] {s, "0"});
                    }
                } else {
                    stack.pop();
                    mark.put(id, 2);
                }
            }
        }
    }

    /** Worst-case cost of one member walking the most expensive path. */
    public BigDecimal worstPathCost(BigDecimal sms, BigDecimal call) {
        Map<String, BigDecimal> memo = new HashMap<>();
        return cost(start, sms, call, memo);
    }

    private BigDecimal cost(String id, BigDecimal sms, BigDecimal call, Map<String, BigDecimal> m) {
        BigDecimal known = m.get(id);
        if (known != null) return known;
        Node n = node(id);
        BigDecimal own =
                switch (n.type()) {
                    case "sms" -> sms;
                    case "call" -> call;
                    default -> BigDecimal.ZERO;
                };
        BigDecimal best = BigDecimal.ZERO;
        for (String s : n.successors()) best = best.max(cost(s, sms, call, m));
        BigDecimal total = own.add(best);
        m.put(id, total);
        return total;
    }

    /** First send channel a member meets (for the admission estimate). */
    public String firstChannel() {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id)) continue;
            Node n = node(id);
            if (n.sends()) return n.type();
            queue.addAll(n.successors());
        }
        return null;
    }

    static String str(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    static double number(Object v, double fallback) {
        if (v == null || v.toString().isBlank()) return fallback;
        try {
            double d = Double.parseDouble(v.toString());
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new ApiError(422, "数值无效");
            return d;
        } catch (NumberFormatException e) {
            throw new ApiError(422, "数值无效：" + v);
        }
    }
}

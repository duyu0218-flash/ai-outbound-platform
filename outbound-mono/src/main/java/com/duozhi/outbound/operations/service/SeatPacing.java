package com.duozhi.outbound.operations.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.dial.domain.DialBatchScope;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import java.util.*;
import org.springframework.stereotype.Service;

/** Group pacing limits supplement the existing consent, account and human pressure gates. */
@Service
public class SeatPacing {
    private final WorkspaceDocuments docs;
    private final Config config;

    public SeatPacing(WorkspaceDocuments docs, Config config) {
        this.docs = docs;
        this.config = config;
    }

    private Map<Long, Long> signedIn(long tenant) {
        var counts = new HashMap<Long, Long>();
        for (var row :
                docs.jdbc.queryForList(
                        "SELECT m.team_id,COUNT(DISTINCT u.id) AS signed_in FROM sys_user u JOIN ops_member m ON m.tenant_id=u.tenant_id AND m.user_id=u.id JOIN ops_team t ON t.tenant_id=m.tenant_id AND t.id=m.team_id WHERE u.tenant_id=? AND t.enabled=1 AND u.enabled=1 AND u.role='agent' AND u.agent_status IN ('ready','busy') AND u.last_seen_at>=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)) GROUP BY m.team_id",
                        tenant,
                        -Math.max(30, config.number("AGENT_PRESENCE_TIMEOUT_SEC", 90))))
            counts.put(
                    ((Number) row.get("team_id")).longValue(),
                    ((Number) row.get("signed_in")).longValue());
        return counts;
    }

    public Object list(long tenant) {
        var signed = signedIn(tenant);
        return docs.list(tenant, "seat_pacing", null).stream()
                .map(
                        d -> {
                            var out = new LinkedHashMap<>(d);
                            var v = map(d.get("draft"));
                            long count =
                                    signed.getOrDefault(
                                            ((Number) v.get("team_id")).longValue(), 0L);
                            out.put("signed_in", count);
                            out.put(
                                    "maximum_concurrency",
                                    (long)
                                            Math.floor(
                                                    count
                                                            * ((Number) v.get("multiplier"))
                                                                    .doubleValue()));
                            return out;
                        })
                .toList();
    }

    public Object save(Identity i, long team, Map<String, Object> body) {
        var t =
                docs.one(
                        "SELECT name FROM ops_team WHERE tenant_id=? AND id=? AND enabled=1",
                        i.tenant(),
                        team);
        if (t == null) throw new ApiError(422, "请选择启用的本租户坐席组");
        var v = map(body.get("config"));
        if (!(v.get("multiplier") instanceof Number n)
                || !Double.isFinite(n.doubleValue())
                || n.doubleValue() < 0
                || n.doubleValue() > 100
                || new java.math.BigDecimal(n.toString()).scale() > 2)
            throw new ApiError(422, "倍速值须为0至100，最多2位小数");
        int wait = OutboundRules.integer(v.get("average_wait_seconds"), 0, 300);
        return docs.save(
                i,
                "seat_pacing",
                "" + team,
                "",
                Map.of(
                        "name",
                        t.get("name"),
                        "team_id",
                        team,
                        "multiplier",
                        v.get("multiplier"),
                        "average_wait_seconds",
                        wait),
                number(body.get("revision")));
    }

    public boolean permits(long tenant, Map<String, Object> policy) {
        var batch = DialBatchScope.current();
        String key = "seat-pacing:" + tenant;
        var data = batch == null ? null : batch.facts.get(key);
        if (data == null) {
            data = Map.of("rules", docs.list(tenant, "seat_pacing", null));
            if (batch != null) batch.facts.put(key, data);
        }
        if (((List<?>) data.get("rules")).isEmpty()) return true;
        var signed = signedIn(tenant);
        var selected = (List<?>) policy.getOrDefault("handoff_team_ids", List.of());
        var teams = new ArrayList<Object>();
        long maximum = 0;
        int wait = 300;
        for (var raw : (List<?>) data.get("rules")) {
            var v = map(map(raw).get("draft"));
            if (((Number) v.get("multiplier")).doubleValue() == 0
                    || !selected.isEmpty()
                            && selected.stream()
                                    .noneMatch(
                                            x -> x.toString().equals(v.get("team_id").toString())))
                continue;
            teams.add(v.get("team_id"));
            maximum +=
                    (long)
                            Math.floor(
                                    signed.getOrDefault(((Number) v.get("team_id")).longValue(), 0L)
                                            * ((Number) v.get("multiplier")).doubleValue());
            wait = Math.min(wait, number(v.get("average_wait_seconds")));
        }
        if (teams.isEmpty()) return true;
        String memberTeams =
                "COALESCE(JSON_EXTRACT(s.policy_json,'$.handoff_team_ids'),JSON_EXTRACT(p.snapshot_json,'$.policy.handoff_team_ids'),JSON_ARRAY())";
        var facts =
                docs.jdbc.queryForMap(
                        "SELECT COUNT(*) AS active,COALESCE(AVG(CASE WHEN c.status='WAITING_HUMAN' THEN TIMESTAMPDIFF(MICROSECOND,h.requested_at,UTC_TIMESTAMP(3))/1000000 END),0) AS average_wait FROM callsession c LEFT JOIN conversationstate s ON s.tenant_id=c.tenant_id AND s.call_id=c.id AND s.attempt=c.attempts LEFT JOIN platform_campaign_snapshot p ON p.tenant_id=c.tenant_id AND p.campaign_id=c.campaign_id LEFT JOIN handoffrequest h ON h.tenant_id=c.tenant_id AND h.call_session_id=c.id AND h.state='WAITING' WHERE c.tenant_id=? AND c.mode IN ('AI_HANDOFF','MIXED_HUMAN_FIRST') AND c.status IN ('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN') AND (JSON_LENGTH("
                                + memberTeams
                                + ")=0 OR JSON_OVERLAPS("
                                + memberTeams
                                + ",CAST(? AS JSON)))",
                        tenant,
                        docs.json.json(teams));
        return ((Number) facts.get("active")).longValue() < maximum
                && ((Number) facts.get("average_wait")).doubleValue() <= wait;
    }
}

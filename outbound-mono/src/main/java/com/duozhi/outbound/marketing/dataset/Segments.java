package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Segment rules over data package rows, match results and touch history, previewed as a count and
 * frozen into snapshots. A snapshot is immutable; its control group (A/B hold-out) is a
 * deterministic hash split salted with the snapshot id.
 */
@Service
public class Segments implements SnapshotMembers {
    static final int MAX_SNAPSHOT = 1_000_000;

    private final MarketingStore db;
    private final JsonSupport json;
    private final TransactionTemplate tx;

    public Segments(MarketingStore db, JsonSupport json, TransactionTemplate tx) {
        this.db = db;
        this.json = json;
        this.tx = tx;
    }

    /** Validated rule set; see the segment page for the meaning of each field. */
    record Rules(
            List<String> versionIds,
            List<String> datasetIds,
            String matchJobId,
            boolean hitOnly,
            BigDecimal scoreMin,
            BigDecimal scoreMax,
            List<String> grades,
            List<String> labels,
            boolean includeBlocked,
            Integer noCallDays,
            Integer noSmsDays,
            Integer linkNotClickedDays,
            List<String> excludeCodes,
            Integer excludeCodesDays) {}

    static List<String> strings(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new ApiError(422, "规则字段应为列表");
        List<String> out = new ArrayList<>();
        for (Object o : list)
            if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
        if (out.size() > 200) throw new ApiError(422, "规则列表过长");
        return out;
    }

    static Integer days(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        int d;
        try {
            d = new BigDecimal(value.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ApiError(422, "天数应为整数");
        }
        if (d < 1 || d > 365) throw new ApiError(422, "天数应在 1 至 365 之间");
        return d;
    }

    static BigDecimal score(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            var v = new BigDecimal(value.toString());
            if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0)
                throw new ApiError(422, "分数应在 0 至 100 之间");
            return v;
        } catch (NumberFormatException e) {
            throw new ApiError(422, "分数格式不正确");
        }
    }

    static Rules rules(Map<String, Object> m) {
        if (m == null) throw new ApiError(422, "缺少圈选规则");
        var grades = strings(m.get("grades"));
        for (String g : grades) if (!g.matches("[ABCD]")) throw new ApiError(422, "等级只能是 A、B、C、D");
        var codes = strings(m.get("excludeCodes"));
        for (String c : codes)
            if (!OutcomeCodes.ALL.contains(c)) throw new ApiError(422, "未知触达结果码: " + c);
        String job =
                m.get("matchJobId") == null || m.get("matchJobId").toString().isBlank()
                        ? null
                        : m.get("matchJobId").toString();
        var r =
                new Rules(
                        strings(m.get("versionIds")),
                        strings(m.get("datasetIds")),
                        job,
                        Boolean.TRUE.equals(m.get("hitOnly")),
                        score(m.get("scoreMin")),
                        score(m.get("scoreMax")),
                        grades,
                        strings(m.get("labels")),
                        Boolean.TRUE.equals(m.get("includeBlocked")),
                        days(m.get("noCallDays")),
                        days(m.get("noSmsDays")),
                        days(m.get("linkNotClickedDays")),
                        codes,
                        days(m.get("excludeCodesDays")));
        if (r.versionIds().isEmpty() && r.datasetIds().isEmpty() && r.matchJobId() == null)
            throw new ApiError(422, "请至少选择一个数据包、版本或撞库任务");
        boolean needsJob =
                r.hitOnly()
                        || r.scoreMin() != null
                        || r.scoreMax() != null
                        || !r.grades().isEmpty()
                        || !r.labels().isEmpty();
        if (needsJob && r.matchJobId() == null) throw new ApiError(422, "按分数、等级或标签圈选需要选择撞库任务");
        if (r.scoreMin() != null
                && r.scoreMax() != null
                && r.scoreMin().compareTo(r.scoreMax()) > 0) throw new ApiError(422, "最低分不能高于最高分");
        return r;
    }

    record Query(
            String from, String where, String key, String cipher, String mask, List<Object> args) {
        String grouped() {
            return "SELECT "
                    + key
                    + " AS h,MIN("
                    + cipher
                    + ") AS c,MIN("
                    + mask
                    + ") AS m FROM "
                    + from
                    + " WHERE "
                    + where
                    + " GROUP BY "
                    + key;
        }

        String count() {
            return "SELECT COUNT(DISTINCT " + key + ") FROM " + from + " WHERE " + where;
        }
    }

    private static String marks(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    Query query(long tenant, Rules r) {
        List<Object> fromArgs = new ArrayList<>(), whereArgs = new ArrayList<>();
        StringBuilder from = new StringBuilder(), where = new StringBuilder();
        String key;
        if (r.matchJobId() != null
                && db.one(
                                "SELECT id FROM mk_match_job WHERE tenant_id=? AND id=?",
                                tenant,
                                r.matchJobId())
                        == null) throw new ApiError(404, "撞库任务不存在");
        boolean dataset = !r.versionIds().isEmpty() || !r.datasetIds().isEmpty();
        if (dataset) {
            Set<String> versions = new LinkedHashSet<>();
            for (String v : r.versionIds()) {
                if (db.one(
                                "SELECT id FROM mk_dataset_version WHERE tenant_id=? AND id=? AND state='ready'",
                                tenant,
                                v)
                        == null) throw new ApiError(404, "数据包版本不存在或未导入完成");
                versions.add(v);
            }
            for (String d : r.datasetIds())
                for (var v :
                        db.rows(
                                "SELECT id FROM mk_dataset_version WHERE tenant_id=? AND dataset_id=? AND state='ready'",
                                tenant,
                                d)) versions.add((String) v.get("id"));
            if (versions.isEmpty()) throw new ApiError(422, "所选数据包没有已导入完成的版本");
            from.append("mk_dataset_row r");
            if (r.matchJobId() != null) {
                from.append(
                        " JOIN mk_match_request q ON q.tenant_id=r.tenant_id AND q.job_id=? AND q.phone_hash=r.phone_hash");
                fromArgs.add(r.matchJobId());
            }
            where.append("r.tenant_id=? AND r.status='valid' AND r.version_id IN (")
                    .append(marks(versions.size()))
                    .append(")");
            whereArgs.add(tenant);
            whereArgs.addAll(versions);
            if (!r.includeBlocked()) where.append(" AND r.blocked_reason IS NULL");
            key = "r.phone_hash";
        } else {
            from.append("mk_match_request q");
            where.append("q.tenant_id=? AND q.job_id=?");
            whereArgs.add(tenant);
            whereArgs.add(r.matchJobId());
            key = "q.phone_hash";
        }
        String cipher = dataset ? "r.phone_cipher" : "q.phone_cipher",
                mask = dataset ? "r.phone_mask" : "q.phone_mask";
        if (r.matchJobId() != null) {
            where.append(" AND q.state IN ('success','no_data','cached')");
            boolean hit =
                    r.hitOnly()
                            || r.scoreMin() != null
                            || r.scoreMax() != null
                            || !r.grades().isEmpty()
                            || !r.labels().isEmpty();
            if (hit) where.append(" AND q.hit=TRUE");
            if (r.scoreMin() != null) {
                where.append(" AND q.score>=?");
                whereArgs.add(r.scoreMin());
            }
            if (r.scoreMax() != null) {
                where.append(" AND q.score<=?");
                whereArgs.add(r.scoreMax());
            }
            if (!r.grades().isEmpty()) {
                where.append(" AND q.grade IN (").append(marks(r.grades().size())).append(")");
                whereArgs.addAll(r.grades());
            }
            if (!r.labels().isEmpty()) {
                where.append(" AND JSON_OVERLAPS(q.labels,CAST(? AS JSON))");
                whereArgs.add(json.json(r.labels()));
            }
        }
        // TouchEvents stores UTC, including when the server's default zone is Asia/Shanghai.
        LocalDateTime now = com.duozhi.outbound.common.dao.Store.now();
        String touch = "SELECT 1 FROM mk_touch_event t WHERE t.tenant_id=? AND t.phone_hash=" + key;
        if (r.noCallDays() != null) {
            where.append(" AND NOT EXISTS(")
                    .append(touch)
                    .append(" AND t.channel='call' AND t.occurred_at>=?)");
            whereArgs.add(tenant);
            whereArgs.add(now.minusDays(r.noCallDays()));
        }
        if (r.noSmsDays() != null) {
            where.append(" AND NOT EXISTS(")
                    .append(touch)
                    .append(" AND t.channel='sms' AND t.occurred_at>=?)");
            whereArgs.add(tenant);
            whereArgs.add(now.minusDays(r.noSmsDays()));
        }
        if (r.linkNotClickedDays() != null) {
            where.append(" AND EXISTS(")
                    .append(touch)
                    .append(" AND t.channel='sms' AND t.occurred_at>=?)");
            where.append(" AND NOT EXISTS(")
                    .append(touch)
                    .append(" AND t.code='LINK_CLICKED' AND t.occurred_at>=?)");
            whereArgs.add(tenant);
            whereArgs.add(now.minusDays(r.linkNotClickedDays()));
            whereArgs.add(tenant);
            whereArgs.add(now.minusDays(r.linkNotClickedDays()));
        }
        if (!r.excludeCodes().isEmpty()) {
            where.append(" AND NOT EXISTS(")
                    .append(touch)
                    .append(" AND t.code IN (")
                    .append(marks(r.excludeCodes().size()))
                    .append(")");
            whereArgs.add(tenant);
            whereArgs.addAll(r.excludeCodes());
            if (r.excludeCodesDays() != null) {
                where.append(" AND t.occurred_at>=?");
                whereArgs.add(now.minusDays(r.excludeCodesDays()));
            }
            where.append(")");
        }
        List<Object> args = new ArrayList<>(fromArgs);
        args.addAll(whereArgs);
        return new Query(from.toString(), where.toString(), key, cipher, mask, args);
    }

    public Map<String, Object> preview(long tenant, Map<String, Object> rawRules) {
        var q = query(tenant, rules(rawRules));
        long count = db.count(q.count(), q.args().toArray());
        var sample =
                db.rows(
                        "SELECT x.m AS phone_mask FROM ("
                                + q.grouped()
                                + ") x ORDER BY x.h LIMIT 10",
                        q.args().toArray());
        return JsonSupport.map(
                "count", count, "sample", sample.stream().map(s -> s.get("phone_mask")).toList());
    }

    // ---- segments

    public List<Map<String, Object>> list(long tenant) {
        return db.rows(
                "SELECT s.*,(SELECT COUNT(*) FROM mk_segment_snapshot n WHERE n.tenant_id=s.tenant_id AND n.segment_id=s.id) AS snapshot_count FROM mk_segment s WHERE s.tenant_id=? ORDER BY s.created_at DESC LIMIT 500",
                tenant);
    }

    public Map<String, Object> segment(long tenant, String id) {
        var row = db.one("SELECT * FROM mk_segment WHERE tenant_id=? AND id=?", tenant, id);
        if (row == null) throw new ApiError(404, "人群不存在");
        return row;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> save(long tenant, String id, Map<String, Object> input, long actor) {
        String name = Matching.str(input.get("name"));
        if (name.isEmpty() || name.length() > 128) throw new ApiError(422, "人群名称应为 1 至 128 个字符");
        var raw = input.get("rules") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        var rules = rules(raw);
        query(tenant, rules);
        if (id == null) {
            id = MarketingStore.id("seg");
            db.write(
                    "INSERT INTO mk_segment(id,tenant_id,name,rules,created_by) VALUES(?,?,?,?,?)",
                    id,
                    tenant,
                    name,
                    json.json(raw),
                    actor);
        } else {
            segment(tenant, id);
            db.write(
                    "UPDATE mk_segment SET name=?,rules=? WHERE tenant_id=? AND id=?",
                    name,
                    json.json(raw),
                    tenant,
                    id);
        }
        return segment(tenant, id);
    }

    public Map<String, Object> freeze(
            long tenant, String segmentId, Object controlPercent, long actor) {
        var segment = segment(tenant, segmentId);
        BigDecimal percent =
                controlPercent == null || controlPercent.toString().isBlank()
                        ? BigDecimal.ZERO
                        : new BigDecimal(controlPercent.toString());
        if (percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(50)) > 0)
            throw new ApiError(422, "对照组比例应在 0 至 50% 之间");
        var raw = json.object(String.valueOf(segment.get("rules")));
        var q = query(tenant, rules(raw));
        long count = db.count(q.count(), q.args().toArray());
        if (count == 0) throw new ApiError(422, "当前规则圈选结果为空，不能生成快照");
        if (count > MAX_SNAPSHOT) throw new ApiError(413, "单个快照最多 100 万人，请收窄规则");
        String id = MarketingStore.id("snap");
        int threshold = percent.movePointRight(2).intValue();
        tx.executeWithoutResult(
                s -> {
                    String name = segment.get("name") + " · " + java.time.LocalDate.now();
                    db.write(
                            "INSERT INTO mk_segment_snapshot(id,tenant_id,segment_id,name,rules,control_percent,created_by) VALUES(?,?,?,?,?,?,?)",
                            id,
                            tenant,
                            segmentId,
                            name.length() > 160 ? name.substring(0, 160) : name,
                            json.json(raw),
                            percent,
                            actor);
                    List<Object> args = new ArrayList<>(List.of(id, tenant, id, threshold));
                    args.addAll(q.args());
                    db.write(
                            "INSERT INTO mk_snapshot_member(snapshot_id,sequence,tenant_id,phone_hash,phone_cipher,phone_mask,contact_id,grp)"
                                    + " SELECT ?,ROW_NUMBER() OVER (ORDER BY x.h),?,x.h,x.c,x.m,NULL,"
                                    + "CASE WHEN CAST(CONV(SUBSTRING(SHA2(CONCAT(?,x.h),256),1,8),16,10) AS UNSIGNED) % 10000 < ? THEN 'control' ELSE 'test' END"
                                    + " FROM ("
                                    + q.grouped()
                                    + ") x",
                            args.toArray());
                    db.write(
                            "UPDATE mk_segment_snapshot s SET size=(SELECT COUNT(*) FROM mk_snapshot_member m WHERE m.snapshot_id=s.id),"
                                    + "control_size=(SELECT COUNT(*) FROM mk_snapshot_member m WHERE m.snapshot_id=s.id AND m.grp='control'),"
                                    + "test_size=(SELECT COUNT(*) FROM mk_snapshot_member m WHERE m.snapshot_id=s.id AND m.grp='test') WHERE s.tenant_id=? AND s.id=?",
                            tenant,
                            id);
                });
        return snapshot(tenant, id);
    }

    public List<Map<String, Object>> snapshots(long tenant, String segmentId) {
        return segmentId == null
                ? db.rows(
                        "SELECT * FROM mk_segment_snapshot WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200",
                        tenant)
                : db.rows(
                        "SELECT * FROM mk_segment_snapshot WHERE tenant_id=? AND segment_id=? ORDER BY created_at DESC LIMIT 200",
                        tenant,
                        segmentId);
    }

    public Map<String, Object> snapshot(long tenant, String id) {
        var row =
                db.one("SELECT * FROM mk_segment_snapshot WHERE tenant_id=? AND id=?", tenant, id);
        if (row == null) throw new ApiError(404, "快照不存在");
        return row;
    }

    public List<Map<String, Object>> memberView(long tenant, String id, long after, int limit) {
        snapshot(tenant, id);
        return db.rows(
                "SELECT sequence,phone_mask,grp FROM mk_snapshot_member WHERE tenant_id=? AND snapshot_id=? AND sequence>? ORDER BY sequence LIMIT ?",
                tenant,
                id,
                after,
                Math.max(1, Math.min(limit, 500)));
    }

    // ---- SnapshotMembers

    @Override
    public List<Member> page(long tenant, String snapshotId, long afterSequence, int limit) {
        List<Member> out = new ArrayList<>();
        for (var r :
                db.rows(
                        "SELECT sequence,phone_hash,phone_cipher,contact_id,grp FROM mk_snapshot_member WHERE tenant_id=? AND snapshot_id=? AND sequence>? ORDER BY sequence LIMIT ?",
                        tenant,
                        snapshotId,
                        afterSequence,
                        Math.max(1, Math.min(limit, 1000))))
            out.add(
                    new Member(
                            ((Number) r.get("sequence")).longValue(),
                            (String) r.get("phone_hash"),
                            (String) r.get("phone_cipher"),
                            r.get("contact_id") == null
                                    ? null
                                    : ((Number) r.get("contact_id")).longValue(),
                            (String) r.get("grp")));
        return out;
    }

    @Override
    public long size(long tenant, String snapshotId) {
        return db.count(
                "SELECT COUNT(*) FROM mk_snapshot_member WHERE tenant_id=? AND snapshot_id=?",
                tenant,
                snapshotId);
    }
}

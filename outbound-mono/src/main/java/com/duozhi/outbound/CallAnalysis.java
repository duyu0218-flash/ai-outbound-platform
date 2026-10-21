package com.duozhi.outbound;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class CallAnalysis {
    private final Store store;
    private final Transactions tx;
    private final JsonSupport json;
    private final Outbox outbox;
    private final Contract contract;
    private final OperationsAccess operations;
    public CallAnalysis(Store store, Transactions tx, JsonSupport json, Outbox outbox, Contract contract,OperationsAccess operations) {
        this.store = store; this.tx = tx; this.json = json; this.outbox = outbox; this.contract = contract;this.operations=operations;
    }
    // The transaction's first snapshot must follow its scoped parent row lock;
    // never run the cross-tenant locator inside this transaction beforehand.
    private Map<String, Object> existing(Map<String,Object> call) { return store.one("callanalysis","scopedCallAnalysis17",Store.scopedParameters(CallLifecycle.tenant(call),store.encode("callanalysis", "call_session_id", call.get("id")))); }
    private Map<String,Object> locked(long tenant,Object id){return store.one("callsession","lockById",Values.map("tenantId",tenant,"id",store.encode("callsession","id",id)));}
    private void visible(Map<String, Object> call, Identity identity) {
        if (call == null || CallLifecycle.tenant(call) != identity.tenant()) throw new ApiError(404, "call not found");
        operations.call(identity,call.get("id"));
    }
    public Map<String, Object> analyze(Object id, Integer expectedAttempt) {
        Long hint=TaskWorker.tenantHint();
        Map<String,Object> located=hint==null?null:store.one("callsession","findById",Values.map("tenantId",hint,"id",store.encode("callsession","id",id)));
        if(located==null)located=store.locate("callsession",id,false);
        if(located==null)return null;
        long tenant=CallLifecycle.tenant(located);
        return tx.run(() -> {
            // Analysis changes only this call's result and outbox. The locked
            // call serializes recalculation/review without the resource mutex.
            TaskWorker.permitted(); Map<String, Object> call = locked(tenant,id);
            return analyzeLocked(call,expectedAttempt);
        });
    }
    /** The caller holds this parent row and the durable task rows in the same transaction. */
    Map<String,Object> analyzeLocked(Map<String,Object> call,Integer expectedAttempt){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("analysis requires its parent transaction");
        TaskWorker.permitted();
        if(call==null||expectedAttempt!=null&&expectedAttempt!=CallLifecycle.number(call,"attempts"))return null;
        return calculate(call);
    }
    private Map<String, Object> calculate(Map<String, Object> call) {
        long tenant = CallLifecycle.tenant(call); Object id = call.get("id"); int attempt = CallLifecycle.number(call, "attempts");
        List<Map<String, Object>> turns = store.query("speechturn","scopedCallAnalysis32",Store.scopedParameters(tenant,store.encode("speechturn", "call_session_id", id),true,attempt));
        Map<String, Object> state = store.one("conversationstate","scopedCallAnalysis33",Store.scopedParameters(tenant,store.encode("conversationstate", "call_id", id),attempt));
        Map<String, Object> policy = state == null ? Collections.emptyMap() : json.object(state.get("policy_json").toString());
        double threshold = policy.get("confidence_threshold") instanceof Number ? ((Number)policy.get("confidence_threshold")).doubleValue() : 0.55;
        Map<String, Object> product = state == null ? Collections.emptyMap() : json.object(state.get("data_json").toString());
        List<String> texts = new ArrayList<>(), meaningful = new ArrayList<>(), flags = new ArrayList<>(), risks = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>(); boolean lowConfidence = false;
        for (Map<String, Object> turn : turns) if ("customer".equals(turn.get("speaker_role"))) {
            String text = turn.get("transcript").toString(); texts.add(text);
            evidence.add(JsonSupport.map("turn_id", turn.get("id"), "text", text, "start_ms", turn.get("start_ms"), "end_ms", turn.get("end_ms")));
            if (turn.get("confidence") instanceof Number && ((Number)turn.get("confidence")).doubleValue() < threshold) { lowConfidence = true; continue; }
            String decision = DialogueRules.classify(text);
            if (Values.set("interested", "rejected", "stop_contact", "wrong_person", "permission_to_listen", "callback").contains(decision)) meaningful.add(decision);
        }
        String full = String.join(" ", texts).trim();
        String finalIntent = meaningful.contains("stop_contact") ? "stop_contact" : meaningful.isEmpty() ? "unclear" : meaningful.get(meaningful.size() - 1);
        boolean positive = "interested".equals(finalIntent), negative = Values.set("rejected", "stop_contact", "wrong_person").contains(finalIntent);
        for (String word : Arrays.asList("投诉", "骚扰", "报警", "删除号码", "别再打")) if (full.contains(word)) risks.add(word);
        String status = call.get("status").toString(), result, intent;
        if ("no_answer".equals(status)) { result = "no_answer"; intent = "unreached"; }
        else if ("busy".equals(status)) { result = "busy"; intent = "retry_later"; }
        else if (Values.set("stop_contact", "wrong_person").contains(finalIntent)) result = intent = finalIntent;
        else if (Values.set("qualified_lead", "callback_confirmed", "non_human", "no_response", "service_failure", "handoff_timeout").contains(product.get("outcome"))) result = intent = product.get("outcome").toString();
        else if (negative) { result = "rejected"; intent = "not_interested"; }
        else if (positive) { result = "interested"; intent = "needs_qualification"; }
        else if ("completed".equals(status)) { result = "completed"; intent = "unclear"; }
        else { result = "failed"; intent = "unknown"; }
        if (turns.isEmpty()) flags.add("missing_structured_transcript");
        if (lowConfidence) flags.add("low_confidence_transcript");
        if (!risks.isEmpty()) flags.add("customer_compliance_risk");
        if (call.get("recording_url") == null) flags.add("missing_recording");
        if ("interested".equals(result) || "unclear".equals(intent)) flags.add("qualification_unconfirmed");
        String sentiment = negative ? "negative" : positive ? "positive" : "neutral";
        String summary = full.isEmpty() ? "通话状态：" + status + "，暂无有效转写。" : full.substring(0, full.offsetByCodePoints(0, Math.min(1000, full.codePointCount(0, full.length()))));
        Map<String, Object> structured = JsonSupport.map("turn_count", turns.size(), "customer_turn_count", texts.size(), "risk_keywords", risks, "status", status,
                "attempt", attempt, "unstructured_evidence", turns.isEmpty() ? call.get("summary") : null, "policy_version_id", state == null ? null : state.get("policy_version_id"),
                "answer_kind", product.getOrDefault("answer_kind", "unknown"), "slots", product.getOrDefault("slots", Collections.emptyMap()), "evidence", evidence,
                "delivery_state", call.get("recording_url") == null ? "recording_pending" : flags.isEmpty() ? "ready" : "review_pending");
        int score = Math.max(0, 100 - flags.size() * 20);
        String automatic = json.canonical(JsonSupport.map("result_code", result, "sentiment", sentiment, "intent", intent, "summary", summary, "qa_score", score, "qa_flags", flags, "structured", structured), true);
        Map<String, Object> record = existing(call), change = new LinkedHashMap<>();
        change.put("automatic_result_json", automatic);
        if (record != null && "reviewed".equals(record.get("review_state"))) {
            if (!automatic.equals(record.get("automatic_result_json"))) change.put("needs_review", true);
        } else {
            change.putAll(JsonSupport.map("result_code", result, "sentiment", sentiment, "intent", intent, "summary", summary, "qa_score", score, "qa_flags_json", json.json(flags), "structured_json", json.json(structured)));
        }
        if (record == null) { change.put("tenant_id", tenant); change.put("call_session_id", id); record = store.insert("callanalysis", change); }
        else record = store.patchLoaded("callanalysis", record, change);
        publish(call, record); return record;
    }
    private void publish(Map<String, Object> call, Map<String, Object> record) {
        Map<String, Object> structured = json.object(record.get("structured_json").toString());
        if (Boolean.TRUE.equals(record.get("needs_review"))) structured.put("delivery_state", "review_pending");
        else if ("reviewed".equals(record.get("review_state"))) structured.put("delivery_state", call.get("recording_url") == null ? "recording_pending" : "ready");
        Map<String, Object> data = JsonSupport.map("result_code", record.get("result_code"), "intent", record.get("intent"), "attempt", call.get("attempts"), "structured", structured,
                "review_state", record.get("review_state"), "needs_review", record.get("needs_review"), "automatic_result", json.object(record.get("automatic_result_json").toString()),
                "reviewed_by", record.get("reviewed_by"), "reviewed_at", Objects.toString(record.get("reviewed_at"), "").replace('T', ' '));
        String version = JsonSupport.sha(json.canonical(data, true)); data.put("version", version);
        outbox.callback(call, "call.result", data, "result:" + call.get("id") + ":" + version);
    }
    @GetMapping("/api/v1/calls/{id}/analysis")
    public Map<String, Object> get(@PathVariable UUID id, @RequestParam(defaultValue="false") boolean refresh, HttpServletRequest request) {
        Identity identity=Identity.from(request);
        return tx.run(() -> {
            Map<String, Object> call = locked(identity.tenant(),id); visible(call, identity);
            Map<String, Object> record = existing(call);
            return contract.output("CallAnalysisOut", record == null || refresh ? calculate(call) : record);
        });
    }
    @AdminPermission(value="quality:review",supervisor=true)
    @PutMapping("/api/v1/calls/{id}/analysis")
    public Map<String, Object> review(@PathVariable UUID id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Identity identity = Identity.from(request);
        if (identity.user() != null && !"admin".equals(identity.user().get("role")) && !Boolean.TRUE.equals(identity.user().get("is_supervisor"))) throw new ApiError(403, "supervisor permission required");
        Map<String, Object> input = contract.input("CallAnalysisReview", body, false);
        return tx.run(() -> {
            Map<String, Object> call = locked(identity.tenant(),id); visible(call, identity);
            Map<String, Object> record = existing(call); if (record == null) record = calculate(call);
            if (input.containsKey("qa_flags")) input.put("qa_flags_json", json.json(input.remove("qa_flags")));
            input.put("review_state", "reviewed"); input.put("needs_review", false); input.put("reviewed_by", identity.userId()); input.put("reviewed_at", Store.now());
            record = store.patchLoaded("callanalysis", record, input);
            publish(call, record); return contract.output("CallAnalysisOut", record);
        });
    }
}

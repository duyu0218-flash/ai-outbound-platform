package com.duozhi.outbound.common.dao;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Class-backed namespace lookup shared by typed and fixed-statement DAOs. */
public final class MapperRegistry {
    private MapperRegistry() {}

    private static final Map<String, Class<?>> TYPES =
            Stream.<Class<?>>of(
                            com.duozhi.outbound.agent.dao.HandoffRequestMapper.class,
                            com.duozhi.outbound.auth.dao.AuthIdentityMapper.class,
                            com.duozhi.outbound.auth.dao.SysUserMapper.class,
                            com.duozhi.outbound.auth.dao.TenantMapper.class,
                            com.duozhi.outbound.call.dao.CallEventMapper.class,
                            com.duozhi.outbound.call.dao.CallSessionMapper.class,
                            com.duozhi.outbound.call.dao.CallUsageMapper.class,
                            com.duozhi.outbound.call.dao.ConversationStateMapper.class,
                            com.duozhi.outbound.call.dao.RealtimeSessionMapper.class,
                            com.duozhi.outbound.call.dao.SpeechTurnMapper.class,
                            com.duozhi.outbound.callback.dao.CallbackBatchMapper.class,
                            com.duozhi.outbound.callback.dao.CallbackInboxMapper.class,
                            com.duozhi.outbound.callback.dao.CallbackInboxPartitionMapper.class,
                            com.duozhi.outbound.callback.dao.CallbackInboxWorkerMapper.class,
                            com.duozhi.outbound.callback.dao.WebhookEventIngestMapper.class,
                            com.duozhi.outbound.campaign.dao.CampaignContactMapper.class,
                            com.duozhi.outbound.campaign.dao.CampaignMapper.class,
                            com.duozhi.outbound.customer.dao.ContactImportJobMapper.class,
                            com.duozhi.outbound.customer.dao.ContactMapper.class,
                            com.duozhi.outbound.customer.dao.PhoneSuppressionMapper.class,
                            com.duozhi.outbound.dial.dao.DialBatchMapper.class,
                            com.duozhi.outbound.job.dao.TaskOutboxMapper.class,
                            com.duozhi.outbound.job.dao.TaskReceiptMapper.class,
                            com.duozhi.outbound.operations.dao.CallAnalysisMapper.class,
                            com.duozhi.outbound.operations.dao.CallMetricMapper.class,
                            com.duozhi.outbound.operations.dao.CallbackAppointmentMapper.class,
                            com.duozhi.outbound.operations.dao.OperationsMapper.class,
                            com.duozhi.outbound.operations.dao.ProductWorkItemMapper.class,
                            com.duozhi.outbound.recording.dao.RecordingAssetMapper.class,
                            com.duozhi.outbound.script.dao.KnowledgeItemMapper.class,
                            com.duozhi.outbound.script.dao.ScenarioVersionMapper.class,
                            com.duozhi.outbound.script.dao.ScriptFlowVersionMapper.class,
                            com.duozhi.outbound.script.dao.ScriptTemplateMapper.class,
                            com.duozhi.outbound.system.dao.AdminSettingMapper.class,
                            com.duozhi.outbound.system.dao.AuditLogMapper.class,
                            com.duozhi.outbound.system.dao.GatewayNodeMapper.class,
                            com.duozhi.outbound.system.dao.SmsLogMapper.class,
                            com.duozhi.outbound.system.dao.TelephonyLineMapper.class)
                    .collect(Collectors.toUnmodifiableMap(Class::getSimpleName, type -> type));

    public static Class<?> type(String name) {
        var type = TYPES.get(name);
        if (type == null) throw new IllegalArgumentException("unknown mapper " + name);
        return type;
    }

    public static String namespace(String name) {
        return type(name).getName();
    }

    public static String statement(String name) {
        int dot = name.indexOf('.');
        if (dot < 1) throw new IllegalArgumentException("mapper statement required");
        return namespace(name.substring(0, dot)) + name.substring(dot);
    }
}

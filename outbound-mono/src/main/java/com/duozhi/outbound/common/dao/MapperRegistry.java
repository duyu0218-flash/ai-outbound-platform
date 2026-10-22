package com.duozhi.outbound.common.dao;

import com.duozhi.outbound.agent.dao.HandoffRequestMapper;
import com.duozhi.outbound.auth.dao.AuthIdentityMapper;
import com.duozhi.outbound.auth.dao.SysUserMapper;
import com.duozhi.outbound.auth.dao.TenantMapper;
import com.duozhi.outbound.call.dao.CallEventMapper;
import com.duozhi.outbound.call.dao.CallSessionMapper;
import com.duozhi.outbound.call.dao.CallUsageMapper;
import com.duozhi.outbound.call.dao.ConversationStateMapper;
import com.duozhi.outbound.call.dao.RealtimeSessionMapper;
import com.duozhi.outbound.call.dao.SpeechTurnMapper;
import com.duozhi.outbound.callback.dao.CallbackBatchMapper;
import com.duozhi.outbound.callback.dao.CallbackInboxMapper;
import com.duozhi.outbound.callback.dao.CallbackInboxPartitionMapper;
import com.duozhi.outbound.callback.dao.CallbackInboxWorkerMapper;
import com.duozhi.outbound.callback.dao.WebhookEventIngestMapper;
import com.duozhi.outbound.campaign.dao.CampaignContactMapper;
import com.duozhi.outbound.campaign.dao.CampaignMapper;
import com.duozhi.outbound.customer.dao.ContactImportJobMapper;
import com.duozhi.outbound.customer.dao.ContactMapper;
import com.duozhi.outbound.customer.dao.PhoneSuppressionMapper;
import com.duozhi.outbound.dial.dao.DialBatchMapper;
import com.duozhi.outbound.job.dao.TaskOutboxMapper;
import com.duozhi.outbound.job.dao.TaskReceiptMapper;
import com.duozhi.outbound.operations.dao.CallAnalysisMapper;
import com.duozhi.outbound.operations.dao.CallMetricMapper;
import com.duozhi.outbound.operations.dao.CallbackAppointmentMapper;
import com.duozhi.outbound.operations.dao.OperationsMapper;
import com.duozhi.outbound.operations.dao.ProductWorkItemMapper;
import com.duozhi.outbound.recording.dao.RecordingAssetMapper;
import com.duozhi.outbound.script.dao.KnowledgeItemMapper;
import com.duozhi.outbound.script.dao.ScenarioVersionMapper;
import com.duozhi.outbound.script.dao.ScriptFlowVersionMapper;
import com.duozhi.outbound.script.dao.ScriptTemplateMapper;
import com.duozhi.outbound.system.dao.AdminSettingMapper;
import com.duozhi.outbound.system.dao.AuditLogMapper;
import com.duozhi.outbound.system.dao.GatewayNodeMapper;
import com.duozhi.outbound.system.dao.SmsLogMapper;
import com.duozhi.outbound.system.dao.TelephonyLineMapper;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Class-backed namespace lookup shared by typed and fixed-statement DAOs. */
public final class MapperRegistry {
    private MapperRegistry() {}

    private static final Map<String, Class<?>> TYPES =
            Stream.<Class<?>>of(
                            HandoffRequestMapper.class,
                            AuthIdentityMapper.class,
                            SysUserMapper.class,
                            TenantMapper.class,
                            CallEventMapper.class,
                            CallSessionMapper.class,
                            CallUsageMapper.class,
                            ConversationStateMapper.class,
                            RealtimeSessionMapper.class,
                            SpeechTurnMapper.class,
                            CallbackBatchMapper.class,
                            CallbackInboxMapper.class,
                            CallbackInboxPartitionMapper.class,
                            CallbackInboxWorkerMapper.class,
                            WebhookEventIngestMapper.class,
                            CampaignContactMapper.class,
                            CampaignMapper.class,
                            ContactImportJobMapper.class,
                            ContactMapper.class,
                            PhoneSuppressionMapper.class,
                            DialBatchMapper.class,
                            TaskOutboxMapper.class,
                            TaskReceiptMapper.class,
                            CallAnalysisMapper.class,
                            CallMetricMapper.class,
                            CallbackAppointmentMapper.class,
                            OperationsMapper.class,
                            ProductWorkItemMapper.class,
                            RecordingAssetMapper.class,
                            KnowledgeItemMapper.class,
                            ScenarioVersionMapper.class,
                            ScriptFlowVersionMapper.class,
                            ScriptTemplateMapper.class,
                            AdminSettingMapper.class,
                            AuditLogMapper.class,
                            GatewayNodeMapper.class,
                            SmsLogMapper.class,
                            TelephonyLineMapper.class)
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

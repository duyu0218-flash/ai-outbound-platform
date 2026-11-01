package com.duozhi.outbound.marketing.common;

import java.util.Map;

/**
 * One marketing send through the existing execution layer: admission check, message or call
 * creation, and {@link Attribution} registration in one step. Implemented by the dataset module
 * (P1), which also uses it for bulk tasks; journeys (P4) call it per member.
 */
public interface ChannelSender {
    record Origin(String snapshotId, String journeyId, String journeyMemberId) {}

    record Recipient(String phoneHash, String phoneCipher, Long contactId) {}

    /**
     * @param applicationId messaging application (msg_application) that owns the template
     * @param landingUrl when set, the template's {link} placeholder becomes a per-recipient short
     *     link minted through {@link LinkMinter}
     */
    record Sms(
            String applicationId,
            String productId,
            String templateId,
            String senderId,
            Map<String, Object> variables,
            String landingUrl,
            String domainPool) {}

    /** Adds the recipient to an existing AI campaign. */
    record Call(long campaignId) {}

    /**
     * accepted=false carries the admission reason; targetId is msg_message.id or callsession.id.
     */
    record Result(boolean accepted, String reason, String targetId) {}

    Result sms(long tenant, Origin origin, Recipient recipient, Sms sms);

    Result call(long tenant, Origin origin, Recipient recipient, Call call);
}

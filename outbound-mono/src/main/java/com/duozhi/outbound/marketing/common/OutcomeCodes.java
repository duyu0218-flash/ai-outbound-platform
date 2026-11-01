package com.duozhi.outbound.marketing.common;

import java.util.Set;

/**
 * The shared vocabulary of touch results. Channel modules emit these codes into mk_touch_event;
 * journeys, profiles and dashboards only ever read them. Add a code here before emitting it.
 */
public final class OutcomeCodes {
    private OutcomeCodes() {}

    public static final String SMS = "sms", CALL = "call", LINK = "link";

    // SMS delivery
    public static final String SMS_SUBMITTED = "SMS_SUBMITTED";
    public static final String SMS_SUBMIT_FAIL = "SMS_SUBMIT_FAIL";
    public static final String SMS_DELIVERED = "SMS_DELIVERED";
    public static final String SMS_FAIL_INVALID = "SMS_FAIL_INVALID";
    public static final String SMS_FAIL_BLOCKED = "SMS_FAIL_BLOCKED";
    public static final String SMS_FAIL_TEMP = "SMS_FAIL_TEMP";
    public static final String SMS_UNKNOWN = "SMS_UNKNOWN";
    public static final String SMS_REPLY_TD = "SMS_REPLY_TD";
    // Short links
    public static final String LINK_CLICKED = "LINK_CLICKED";
    public static final String LINK_CONVERTED = "LINK_CONVERTED";
    // Call layer 1: connection
    public static final String CALL_NO_ANSWER = "CALL_NO_ANSWER";
    public static final String CALL_BUSY = "CALL_BUSY";
    public static final String CALL_REJECTED = "CALL_REJECTED";
    public static final String CALL_INVALID = "CALL_INVALID";
    public static final String CALL_POWER_OFF = "CALL_POWER_OFF";
    public static final String CALL_SUSPENDED = "CALL_SUSPENDED";
    public static final String CALL_LINE_FAIL = "CALL_LINE_FAIL";
    public static final String CALL_ANSWERED = "CALL_ANSWERED";
    // Call layer 2: answer quality
    public static final String ANS_INSTANT_HANGUP = "ANS_INSTANT_HANGUP";
    public static final String ANS_SHORT_HANGUP = "ANS_SHORT_HANGUP";
    public static final String ANS_VOICE_ASSISTANT = "ANS_VOICE_ASSISTANT";
    public static final String ANS_VOICEMAIL = "ANS_VOICEMAIL";
    public static final String ANS_NOT_OWNER = "ANS_NOT_OWNER";
    public static final String ANS_REFUSE_HARD = "ANS_REFUSE_HARD";
    public static final String ANS_CONVERSATION = "ANS_CONVERSATION";
    // Call layer 3: intent level
    public static final String INTENT_A = "INTENT_A";
    public static final String INTENT_B = "INTENT_B";
    public static final String INTENT_C = "INTENT_C";
    public static final String INTENT_D = "INTENT_D";
    public static final String INTENT_E = "INTENT_E";
    // Business conversion reported back by landing pages or client portals
    public static final String CONVERTED = "CONVERTED";

    public static final Set<String> ALL =
            Set.of(
                    SMS_SUBMITTED,
                    SMS_SUBMIT_FAIL,
                    SMS_DELIVERED,
                    SMS_FAIL_INVALID,
                    SMS_FAIL_BLOCKED,
                    SMS_FAIL_TEMP,
                    SMS_UNKNOWN,
                    SMS_REPLY_TD,
                    LINK_CLICKED,
                    LINK_CONVERTED,
                    CALL_NO_ANSWER,
                    CALL_BUSY,
                    CALL_REJECTED,
                    CALL_INVALID,
                    CALL_POWER_OFF,
                    CALL_SUSPENDED,
                    CALL_LINE_FAIL,
                    CALL_ANSWERED,
                    ANS_INSTANT_HANGUP,
                    ANS_SHORT_HANGUP,
                    ANS_VOICE_ASSISTANT,
                    ANS_VOICEMAIL,
                    ANS_NOT_OWNER,
                    ANS_REFUSE_HARD,
                    ANS_CONVERSATION,
                    INTENT_A,
                    INTENT_B,
                    INTENT_C,
                    INTENT_D,
                    INTENT_E,
                    CONVERTED);

    /** Codes after which no channel may contact the number again. */
    public static final Set<String> BLOCKING =
            Set.of(SMS_FAIL_INVALID, SMS_REPLY_TD, CALL_INVALID, ANS_REFUSE_HARD, INTENT_D);
}

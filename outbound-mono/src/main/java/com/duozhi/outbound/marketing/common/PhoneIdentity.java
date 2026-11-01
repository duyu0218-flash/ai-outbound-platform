package com.duozhi.outbound.marketing.common;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.messaging.service.MessageSecrets;
import org.springframework.stereotype.Service;

/**
 * One phone identity for the whole marketing domain. The hash equals msg_message.recipient_hash, so
 * marketing facts join messaging facts without decrypting anything.
 */
@Service
public class PhoneIdentity {
    private final MessageSecrets secrets;

    public PhoneIdentity(MessageSecrets secrets) {
        this.secrets = secrets;
    }

    public record Phone(String e164, String hash, String mask) {}

    /** Null when the value is not a dialable number; callers record the row as invalid. */
    public static String normalize(String value) {
        if (value == null) return null;
        String s = value.replaceAll("[\\s-]", "");
        if (s.matches("1[3-9][0-9]{9}")) s = "+86" + s;
        else if (s.matches("861[3-9][0-9]{9}")) s = "+" + s;
        return s.matches("\\+[1-9][0-9]{7,14}") ? s : null;
    }

    public static String mask(String e164) {
        String local = e164.startsWith("+86") ? e164.substring(3) : e164;
        if (local.length() < 7) return "****";
        return local.substring(0, 3) + "****" + local.substring(local.length() - 4);
    }

    public Phone of(long tenant, String value) {
        String e164 = normalize(value);
        if (e164 == null) throw new ApiError(422, "号码格式无效");
        return new Phone(e164, hash(tenant, e164), mask(e164));
    }

    public String hash(long tenant, String e164) {
        return secrets.hash(tenant, e164);
    }

    public String encrypt(long tenant, String e164) {
        return secrets.encrypt(context(tenant), e164);
    }

    public String decrypt(long tenant, String cipher) {
        return secrets.decrypt(context(tenant), cipher);
    }

    private static String context(long tenant) {
        return "mk-phone:" + tenant;
    }
}

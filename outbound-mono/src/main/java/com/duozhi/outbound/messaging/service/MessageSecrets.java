package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.integration.config.UnifiedServiceSettings;
import com.duozhi.outbound.integration.support.Support;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.stereotype.Service;

@Service
public class MessageSecrets {
    private final Config config;

    public MessageSecrets(Config config) {
        this.config = config;
    }

    public static String random() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private byte[] key() {
        try {
            var b =
                    Base64.getDecoder()
                            .decode(
                                    new UnifiedServiceSettings(config, "voice")
                                            .secret("MESSAGING_MASTER_KEY"));
            if (b.length != 32) throw new IllegalArgumentException();
            return b;
        } catch (Exception e) {
            throw new ApiError(503, "消息加密主密钥尚未配置");
        }
    }

    public String hash(long tenant, String value) {
        return Support.hmac(
                Base64.getEncoder().encodeToString(key()),
                (tenant + ":" + value).getBytes(StandardCharsets.UTF_8));
    }

    public String encrypt(String context, String value) {
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            var c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(128, nonce));
            c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            var bytes = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce)
                    + "."
                    + Base64.getEncoder().encodeToString(bytes);
        } catch (ApiError e) {
            throw e;
        } catch (Exception e) {
            throw new ApiError(503, "消息数据加密失败");
        }
    }

    public String decrypt(String context, String value) {
        try {
            var parts = value.split("\\.");
            var c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(
                    c.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (ApiError e) {
            throw e;
        } catch (Exception e) {
            throw new ApiError(503, "消息数据无法解密");
        }
    }
}

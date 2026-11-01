package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.common.support.JsonSupport;
import org.springframework.stereotype.Component;

/**
 * Offline exchange with partners that only accept hashed numbers: export one lowercase SHA-256 of
 * the 11-digit number (or the E.164 digits outside mainland China) per line, then import the
 * partner's hit-result file. No network call is made by the platform.
 */
@Component
public class HashFileMatchAdapter implements MatchAdapter {
    @Override
    public String id() {
        return HASH_FILE;
    }

    @Override
    public String label() {
        return "哈希文件交换（导出 SHA256 / 导入命中结果）";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public boolean online() {
        return false;
    }

    /** The value partners hash: mainland numbers as 11 digits, others as E.164 without '+'. */
    static String exchangeHash(String e164) {
        String local = AntDasMatchAdapter.local(e164);
        return JsonSupport.sha(local != null ? local : e164.substring(1));
    }
}

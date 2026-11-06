package com.duozhi.outbound.messaging.service;

import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DirectMessageAdapter implements MessageAdapter {
    private final MessageDelivery delivery;

    public DirectMessageAdapter(MessageDelivery delivery) {
        this.delivery = delivery;
    }

    public String id() {
        return "channel";
    }

    public Set<String> forms() {
        return MessageContent.FORMS;
    }

    public Result submit(Submission s) {
        return delivery.submit(((Number) s.options().get("tenant_id")).longValue(), s.messageId());
    }

    public Result query(String id) {
        return new Result("UNKNOWN", "", 0, false);
    }
}

package com.duozhi.outbound.callback.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CallbackInboxBootstrap implements ApplicationRunner {
    private final CallbackInbox inbox;

    public CallbackInboxBootstrap(CallbackInbox inbox) {
        this.inbox = inbox;
    }

    @Override
    public void run(ApplicationArguments args) {
        inbox.initialize();
    }
}

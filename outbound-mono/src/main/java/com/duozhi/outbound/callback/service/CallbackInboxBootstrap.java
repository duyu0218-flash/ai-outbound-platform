package com.duozhi.outbound.callback.service;

@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type =
                org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type
                        .SERVLET)
public class CallbackInboxBootstrap implements org.springframework.boot.ApplicationRunner {
    private final CallbackInbox inbox;

    public CallbackInboxBootstrap(CallbackInbox inbox) {
        this.inbox = inbox;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        inbox.initialize();
    }
}

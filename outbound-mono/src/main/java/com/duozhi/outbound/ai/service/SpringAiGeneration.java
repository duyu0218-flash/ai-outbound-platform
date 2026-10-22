package com.duozhi.outbound.ai.service;

import com.duozhi.outbound.ai.transport.BoundedOpenAiHttpClient;
import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.support.Support;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;

/** Spring AI adapter for the original single-request, bounded OpenAI-compatible contract. */
public final class SpringAiGeneration {
    private SpringAiGeneration() {}

    static String generate(
            Settings settings,
            HttpClient client,
            URI endpoint,
            List<Map<String, String>> source,
            String model,
            int outputTokens,
            AccountQuota quota,
            AgentTracing tracing)
            throws Exception {
        long timeout = (long) (settings.decimal("OPENAI_TIMEOUT_SEC", 8, .1, 300) * 1000);
        var transport =
                new BoundedOpenAiHttpClient(
                        client, endpoint, settings.secret("OPENAI_API_KEY"), timeout, quota);
        var sdk =
                new OpenAIClientImpl(
                        ClientOptions.builder()
                                .httpClient(transport)
                                .baseUrl(endpoint.resolve("/").toString())
                                .apiKey(settings.secret("OPENAI_API_KEY"))
                                .maxRetries(0)
                                .timeout(Duration.ofMillis(timeout))
                                .responseValidation(false)
                                .build());
        var options =
                OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(.3)
                        .maxTokens(outputTokens)
                        .maxRetries(0)
                        .timeout(Duration.ofMillis(timeout))
                        .build();
        var chat =
                OpenAiChatModel.builder()
                        .openAiClient(sdk)
                        .openAiClientAsync(sdk.async())
                        .options(options)
                        .build();
        List<Message> messages = new ArrayList<>();
        for (var item : source)
            messages.add(
                    switch (item.get("role")) {
                        case "system" -> new SystemMessage(item.get("content"));
                        case "assistant" -> new AssistantMessage(item.get("content"));
                        default -> new UserMessage(item.get("content"));
                    });
        try {
            var response = chat.call(new Prompt(messages, options));
            if (response == null || response.getResult() == null)
                throw new Support.Failure(502, "LLM response did not contain a message");
            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank())
                throw new Support.Failure(502, "LLM returned an empty message");
            var usage = response.getMetadata().getUsage();
            if (usage != null) {
                var counts = new LinkedHashMap<String, Object>();
                counts.put("prompt_tokens", usage.getPromptTokens());
                counts.put("completion_tokens", usage.getCompletionTokens());
                counts.put("total_tokens", usage.getTotalTokens());
                tracing.usage(Map.of("usage", counts));
            }
            return text.strip();
        } catch (Support.Failure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new Support.Failure(502, "external LLM request failed");
        }
    }
}

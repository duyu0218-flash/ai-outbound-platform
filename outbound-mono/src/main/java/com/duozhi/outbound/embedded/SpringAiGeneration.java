package com.duozhi.outbound.embedded;
import java.net.*;
import java.net.http.HttpClient;
import java.io.*;
import java.time.Duration;
import java.util.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.*;

/** Spring AI adapter for the original single-request, bounded OpenAI-compatible contract. */
final class SpringAiGeneration {
    private SpringAiGeneration(){}
    static String generate(Settings settings,HttpClient client,URI endpoint,List<Map<String,String>> source,String model,int outputTokens,AccountQuota quota,AgentTracing tracing)throws Exception{
        long timeout=(long)(settings.decimal("OPENAI_TIMEOUT_SEC",8,.1,300)*1000);
        var factory=new JdkClientHttpRequestFactory(client);factory.setReadTimeout(Duration.ofMillis(timeout));
        var rest=RestClient.builder().requestFactory(factory).messageConverters(converters->{for(var converter:converters)if(converter instanceof org.springframework.http.converter.json.MappingJackson2HttpMessageConverter jackson)jackson.setSupportedMediaTypes(List.of(MediaType.ALL));}).requestInterceptor((request,body,execution)->{
            var response=execution.execute(request,body);
            return new ClientHttpResponse(){
                public HttpStatusCode getStatusCode()throws IOException{return response.getStatusCode();}
                public String getStatusText()throws IOException{return response.getStatusText();}
                public HttpHeaders getHeaders(){return response.getHeaders();}
                public void close(){response.close();}
                public InputStream getBody()throws IOException{
                    return new FilterInputStream(Support.timed(response.getBody(),timeout)){
                        long count;
                        private int bounded(int size)throws IOException{if(size>0&&(count+=size)>1048576)throw new IOException("response exceeds maximum size");return size;}
                        @Override public int read()throws IOException{int value=in.read();if(value>=0)bounded(1);return value;}
                        @Override public int read(byte[] b,int offset,int length)throws IOException{return bounded(in.read(b,offset,length));}
                    };
                }
            };
        });
        var errors=new ResponseErrorHandler(){
            public boolean hasError(ClientHttpResponse response)throws IOException{return !response.getStatusCode().is2xxSuccessful();}
            public void handleError(URI uri,HttpMethod method,ClientHttpResponse response)throws IOException{
                if(Set.of(429,503).contains(response.getStatusCode().value())){
                    double seconds;try{seconds=Double.parseDouble(Optional.ofNullable(response.getHeaders().getFirst("Retry-After")).orElse("5"));}catch(NumberFormatException e){seconds=5;}
                    if(!Double.isFinite(seconds))seconds=5;try{quota.block(Math.max(1,Math.min(300,seconds)));}catch(java.sql.SQLException failed){throw new Support.Failure(500,"account quota unavailable");}
                }throw new Support.Failure(502,"external LLM request failed");
            }
        };
        String origin=new URI(endpoint.getScheme(),null,endpoint.getHost(),endpoint.getPort(),null,null,null).toString();
        var api=OpenAiApi.builder().baseUrl(origin).completionsPath(endpoint.getRawPath()).apiKey(settings.secret("OPENAI_API_KEY")).restClientBuilder(rest).responseErrorHandler(errors).build();
        var options=OpenAiChatOptions.builder().model(model).temperature(.3).maxTokens(outputTokens).build();
        var chat=OpenAiChatModel.builder().openAiApi(api).defaultOptions(options).retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build()).build();
        List<Message> messages=new ArrayList<>();for(var item:source)messages.add(switch(item.get("role")){case "system"->new SystemMessage(item.get("content"));case "assistant"->new AssistantMessage(item.get("content"));default->new UserMessage(item.get("content"));});
        try{
            var response=chat.call(new Prompt(messages,options));
            if(response==null||response.getResult()==null)throw new Support.Failure(502,"LLM response did not contain a message");
            String text=response.getResult().getOutput().getText();if(text==null||text.isBlank())throw new Support.Failure(502,"LLM returned an empty message");
            var usage=response.getMetadata().getUsage();if(usage!=null){var counts=new LinkedHashMap<String,Object>();counts.put("prompt_tokens",usage.getPromptTokens());counts.put("completion_tokens",usage.getCompletionTokens());counts.put("total_tokens",usage.getTotalTokens());tracing.usage(Map.of("usage",counts));}
            return text.strip();
        }catch(Support.Failure failure){throw failure;}catch(RuntimeException failure){throw new Support.Failure(502,"external LLM request failed");}
    }
}

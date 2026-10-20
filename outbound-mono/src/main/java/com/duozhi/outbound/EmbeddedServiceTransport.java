package com.duozhi.outbound;
import com.duozhi.outbound.embedded.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
/** Internal calls keep the source credentials, validation and acknowledgement deadlines. */
@org.springframework.stereotype.Service
public class EmbeddedServiceTransport {
    private final VoiceController voice;private final ObjectProvider<RecordingController> recordings;
    private final ThreadPoolExecutor commands=new ThreadPoolExecutor(0,64,60,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{var thread=new Thread(task,"mono-service-command");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    public EmbeddedServiceTransport(VoiceController voice,ObjectProvider<RecordingController> recordings){this.voice=voice;this.recordings=recordings;}
    private <T>T invoke(Callable<T> action,int timeout){Future<T> result;try{result=commands.submit(action);}catch(RejectedExecutionException busy){throw new TelephonyClient.Failure(503,false);}try{return result.get(timeout,TimeUnit.MILLISECONDS);}catch(TimeoutException failure){result.cancel(true);throw new TelephonyClient.Failure(0,false);}catch(InterruptedException failure){result.cancel(true);Thread.currentThread().interrupt();throw new TelephonyClient.Failure(0,false);}catch(ExecutionException failure){if(failure.getCause() instanceof Support.Failure error)throw new TelephonyClient.Failure(error.status,error.status==429&&"false".equals(error.headers.get("X-Voice-Dial-Admitted")));throw new TelephonyClient.Failure(failure.getCause() instanceof IllegalArgumentException?422:500,false);}}
    public Map<String,Object> command(String action,byte[] body,Map<String,String> headers,int timeout){return invoke(()->voice.commandInternal(action,body,headers),timeout);}
    public List<VoiceController.CommandResult> commands(List<VoiceController.Command> commands,int timeout){return invoke(()->voice.commandsInternalBatch(commands),timeout);}
    public Map<String,Object> recording(boolean delete,Map<String,Object> payload,String auth,int timeout){return invoke(()->delete?recordings.getObject().delete(payload,auth):recordings.getObject().ingest(payload,auth),timeout);}
    public Map<String,Object> voiceReady(){return voice.ready();}
    @jakarta.annotation.PreDestroy public void close(){commands.shutdownNow();}
}

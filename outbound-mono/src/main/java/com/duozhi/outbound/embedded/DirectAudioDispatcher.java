package com.duozhi.outbound.embedded;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Bounded off-heap PCM ingress. Per-socket order is preserved; overload drops the oldest queued frame. */
final class DirectAudioDispatcher implements AutoCloseable {
    interface Sink { void accept(ByteBuffer frame)throws Exception; }
    private static final int FRAME_BYTES=65536,SESSION_FRAMES=4;
    private final ThreadPoolExecutor executor;
    private final ArrayBlockingQueue<ByteBuffer> free;
    private final Semaphore allocations;
    private final ConcurrentMap<String,Channel> channels=new ConcurrentHashMap<>();
    private final LongAdder dropped=new LongAdder(),processed=new LongAdder();
    private volatile boolean closed;
    private static final class Channel {final ArrayDeque<ByteBuffer> frames=new ArrayDeque<>();boolean running,closed;final Sink sink;final java.util.function.Consumer<Exception> failure;Channel(Sink sink,java.util.function.Consumer<Exception> failure){this.sink=sink;this.failure=failure;}}
    DirectAudioDispatcher(int sessions){this(Math.min(32,Math.max(4,sessions)),Math.min(8192,Math.max(32,sessions*2)),Math.min(1024,Math.max(32,sessions*4)));}
    DirectAudioDispatcher(int threads,int tasks,int frames){free=new ArrayBlockingQueue<>(frames);allocations=new Semaphore(frames);executor=new ThreadPoolExecutor(threads,threads,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(tasks),task->{var thread=new Thread(task,"mono-media-pcm");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());executor.allowCoreThreadTimeOut(true);}
    void register(String id,Sink sink,java.util.function.Consumer<Exception> failure){if(closed)throw new IllegalStateException("media ingress closed");Channel old=channels.putIfAbsent(id,new Channel(sink,failure));if(old!=null)throw new IllegalStateException("media socket already registered");}
    void offer(String id,ByteBuffer source){
        if(source.remaining()%2!=0||source.remaining()>FRAME_BYTES)throw new Support.Failure(400,"invalid PCM frame");
        Channel channel=channels.get(id);if(channel==null||closed)return;
        synchronized(channel){
            if(channel.closed)return;
            if(channel.frames.size()==SESSION_FRAMES){release(channel.frames.removeFirst());dropped.increment();}
            ByteBuffer frame=free.poll();if(frame==null&&allocations.tryAcquire())try{frame=ByteBuffer.allocateDirect(FRAME_BYTES);}catch(Throwable error){allocations.release();throw error;}
            if(frame==null){dropped.increment();return;}
            frame.clear();frame.put(source.duplicate()).flip();channel.frames.addLast(frame);
            if(!channel.running){channel.running=true;try{executor.execute(()->drain(channel));}catch(RejectedExecutionException busy){channel.running=false;clear(channel);}}
        }
    }
    private void drain(Channel channel){
        for(;;){ByteBuffer frame;synchronized(channel){frame=channel.frames.pollFirst();if(frame==null||channel.closed){if(frame!=null)release(frame);clear(channel);channel.running=false;return;}}
            try{channel.sink.accept(frame.asReadOnlyBuffer());processed.increment();}catch(Exception error){try{if(!closed&&!channel.closed)channel.failure.accept(error);}finally{closeChannel(channel);}}finally{release(frame);}
        }
    }
    private void release(ByteBuffer frame){frame.clear();if(!free.offer(frame))throw new IllegalStateException("PCM pool ownership violation");}
    private void clear(Channel channel){ByteBuffer frame;while((frame=channel.frames.pollFirst())!=null){release(frame);dropped.increment();}}
    private void closeChannel(Channel channel){synchronized(channel){channel.closed=true;clear(channel);}}
    void disconnect(String id){Channel channel=channels.remove(id);if(channel!=null)closeChannel(channel);}
    long dropped(){return dropped.sum();}long processed(){return processed.sum();}
    int retainedBuffers(){return free.size();}
    @Override public void close(){closed=true;for(var channel:channels.values())closeChannel(channel);channels.clear();executor.shutdownNow();}
}

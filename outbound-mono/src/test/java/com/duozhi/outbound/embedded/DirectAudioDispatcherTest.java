package com.duozhi.outbound.embedded;
import java.nio.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DirectAudioDispatcherTest {
    @Test void saturatedSocketDropsOldFramesWithoutReorderingOrHeapBuffering()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(5);var seen=new CopyOnWriteArrayList<Integer>();var threads=new CopyOnWriteArrayList<String>();
        try(var dispatcher=new DirectAudioDispatcher(1,2,16)){
            dispatcher.register("socket",frame->{assertTrue(frame.isDirect());assertTrue(frame.isReadOnly());threads.add(Thread.currentThread().getName());int value=frame.getShort();if(value==0){entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));}seen.add(value);done.countDown();},error->fail(error));
            dispatcher.offer("socket",ByteBuffer.allocate(2).putShort((short)0).flip());assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1),()->{for(short value=1;value<=20;value++)dispatcher.offer("socket",ByteBuffer.allocate(2).putShort(value).flip());});
            release.countDown();assertTrue(done.await(3,TimeUnit.SECONDS));
            // The sink signals before drain increments processed and returns its direct buffer.
            assertTimeoutPreemptively(Duration.ofSeconds(3),()->{while(dispatcher.processed()!=5||dispatcher.retainedBuffers()!=5)Thread.sleep(1);});
            assertEquals(List.of(0,17,18,19,20),seen);assertEquals(16,dispatcher.dropped());assertEquals(5,dispatcher.processed());assertTrue(threads.stream().allMatch(name->name.startsWith("mono-media-pcm")));dispatcher.disconnect("socket");assertEquals(5,dispatcher.retainedBuffers());
        }finally{release.countDown();}
    }
    @Test void queuedTaskOverloadDropsAndReturnsBuffers()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var dispatcher=new DirectAudioDispatcher(1,1,4)){
            dispatcher.register("a",frame->{entered.countDown();release.await();},error->fail(error));dispatcher.register("b",frame->{},error->fail(error));dispatcher.register("c",frame->{},error->fail(error));
            dispatcher.offer("a",ByteBuffer.allocate(2));assertTrue(entered.await(2,TimeUnit.SECONDS));dispatcher.offer("b",ByteBuffer.allocate(2));dispatcher.offer("c",ByteBuffer.allocate(2));assertEquals(1,dispatcher.dropped());dispatcher.disconnect("b");dispatcher.disconnect("c");assertEquals(2,dispatcher.retainedBuffers());release.countDown();
        }finally{release.countDown();}
    }
    @Test void directPcmConversionMatchesSourceJavaAcrossEverySupportedRateAndChunkBoundary()throws Exception{
        java.nio.file.Path source=java.nio.file.Path.of("../services-java/src/main/java/com/duozhi/services/NativeMedia.java");String text=java.nio.file.Files.readString(source);int start=text.indexOf("    static final class PcmResampler");assertTrue(start>=0);String nested=text.substring(start,text.lastIndexOf('}'));
        var temp=java.nio.file.Files.createTempDirectory("source-resampler-");java.nio.file.Files.writeString(temp.resolve("SourcePcm.java"),"import java.io.*;import java.util.*;public class SourcePcm{ "+nested+"}");var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();assertEquals(0,compiler.run(null,null,null,"-d",temp.toString(),temp.resolve("SourcePcm.java").toString()));
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{temp.toUri().toURL()},null)){
            Class<?> reference=loader.loadClass("SourcePcm$PcmResampler");var ctor=reference.getDeclaredConstructor(int.class,int.class);ctor.setAccessible(true);var convert=reference.getDeclaredMethod("convert",byte[].class);convert.setAccessible(true);var finish=reference.getDeclaredMethod("finish");finish.setAccessible(true);
            byte[] input=new byte[10000];new Random(42).nextBytes(input);
            for(int from:new int[]{8000,16000,24000,48000})for(int to:new int[]{8000,16000,24000,48000}){
                Object original=ctor.newInstance(from,to);var migrated=new NativeMedia.PcmResampler(from,to);var expected=new java.io.ByteArrayOutputStream();var actual=new java.io.ByteArrayOutputStream();
                for(int offset=0;offset<input.length;offset+=37){byte[] chunk=Arrays.copyOfRange(input,offset,Math.min(input.length,offset+37));expected.writeBytes((byte[])convert.invoke(original,(Object)chunk));ByteBuffer direct=ByteBuffer.allocateDirect(chunk.length).put(chunk).flip();ByteBuffer result=migrated.convertBuffer(direct);assertTrue(result.isDirect());byte[] bytes=new byte[result.remaining()];result.get(bytes);actual.writeBytes(bytes);}
                expected.writeBytes((byte[])finish.invoke(original));var tail=migrated.finishBuffer();byte[] bytes=new byte[tail.remaining()];tail.get(bytes);actual.writeBytes(bytes);assertArrayEquals(expected.toByteArray(),actual.toByteArray(),from+"->"+to);
            }
        }
    }
}

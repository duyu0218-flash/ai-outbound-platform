package com.duozhi.outbound;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class RedisTlsSecurityTest {
    @TempDir
    static Path folder;
    static SSLContext serverContext,trustFixture;
    @BeforeAll
    static void certificates() throws Exception {
        Path storeFile=folder.resolve("synthetic-test.p12");String password="synthetic-test-only";
        Process command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","fixture",
            "-keyalg","RSA","-keysize","2048","-storetype","PKCS12","-keystore",storeFile.toString(),"-storepass",password,"-keypass",password,
            "-dname","CN=localhost","-ext","SAN=dns:localhost","-validity","1","-noprompt").redirectErrorStream(true).redirectOutput(folder.resolve("keytool.log").toFile()).start();
        assertTrue(command.waitFor(20,TimeUnit.SECONDS));assertEquals(0,command.exitValue());
        KeyStore store=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(storeFile)){store.load(input,password.toCharArray());}
        KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,password.toCharArray());
        serverContext=SSLContext.getInstance("TLS");serverContext.init(keys.getKeyManagers(),null,null);
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
        trustFixture=SSLContext.getInstance("TLS");trustFixture.init(null,trust.getTrustManagers(),null);
    }
    private Config config(String scheme,String host,int port) {
        return new Config(new MockEnvironment().withProperty("ENV","production")
            .withProperty("REDIS_URL",scheme+"://:synthetic-redis-password@"+host+":"+port+"/0"));
    }
    private SSLServerSocket server() throws IOException {
        SSLServerSocket server=(SSLServerSocket)serverContext.getServerSocketFactory().createServerSocket(0,10,InetAddress.getByName("localhost"));
        server.setSoTimeout(3000);return server;
    }
    private List<String> serve(SSLServerSocket server,boolean failureExpected) throws Exception {
        List<String> commands=new ArrayList<>();
        try(SSLSocket client=(SSLSocket)server.accept()) {
            client.setSoTimeout(3000);client.startHandshake();
            BufferedReader input=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));
            for(int i=0;i<3;i++) {
                String array=input.readLine();if(array==null)break;
                int size=Integer.parseInt(array.substring(1));List<String> fields=new ArrayList<>();
                for(int field=0;field<size;field++){assertTrue(input.readLine().startsWith("$"));fields.add(input.readLine());}
                commands.add(fields.get(0));
                client.getOutputStream().write(("PING".equals(fields.get(0))?"+PONG\r\n":"+OK\r\n").getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();
            }
        }catch(IOException expected){if(!failureExpected)throw expected; /* Peer may close with an SSL alert or a broken pipe after rejecting our certificate. */}
        return commands;
    }
    @Test
    void trustedTlsVerifiesHostnameBeforeSendingCredentialsAndCommands() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(SSLServerSocket server=server()) {
            Future<List<String>> commands=executor.submit(()->serve(server,false));
            assertEquals("PONG",new ExpiringValues(config("rediss","localhost",server.getLocalPort()),trustFixture.getSocketFactory()).shared("PING"));
            assertEquals(List.of("AUTH","SELECT","PING"),commands.get(5,TimeUnit.SECONDS));
        }finally{executor.shutdownNow();}
    }
    @Test
    void certificateHostnameMismatchAndUntrustedIssuerNeverReceiveCredentials() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            for(boolean mismatch:new boolean[]{true,false})try(SSLServerSocket server=server()) {
                Future<List<String>> commands=executor.submit(()->serve(server,true));
                SSLSocketFactory trust=mismatch?trustFixture.getSocketFactory():(SSLSocketFactory)SSLSocketFactory.getDefault();
                assertThrows(ApiError.class,()->new ExpiringValues(config("rediss",mismatch?"127.0.0.1":"localhost",server.getLocalPort()),trust).shared("PING"));
                assertEquals(List.of(),commands.get(5,TimeUnit.SECONDS));
            }
        }finally{executor.shutdownNow();}
    }
    @Test
    void plaintextRedisInProductionIsRejectedBeforeAnyConnection() throws Exception {
        try(ServerSocket server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(200);
            ApiError error=assertThrows(ApiError.class,()->new ExpiringValues(config("redis","127.0.0.1",server.getLocalPort())).shared("PING"));
            assertTrue(error.getMessage().contains("TLS"));assertThrows(SocketTimeoutException.class,server::accept);
        }
    }
    @Test
    void httpsCallbackRejectsUntrustedCertificateBeforeSendingPayload() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(SSLServerSocket server=server()) {
            Future<List<String>> commands=executor.submit(()->serve(server,true));
            String origin="https://localhost:"+server.getLocalPort();
            Config cfg=new Config(new MockEnvironment().withProperty("ENV","production").withProperty("BUSINESS_CALLBACK_PRIVATE_ORIGINS",origin));
            IntegrationTasks tasks=new IntegrationTasks(null,null,cfg,null,null,null);
            ApiError error=assertThrows(ApiError.class,()->tasks.pinnedPost(origin+"/callback","synthetic confidential payload".getBytes(StandardCharsets.UTF_8),Map.of("Authorization","Bearer synthetic-secret"),2000));
            assertEquals(502,error.status);assertEquals(List.of(),commands.get(5,TimeUnit.SECONDS));
        }finally{executor.shutdownNow();}
    }
    @Test
    void malformedRedisResponsesHaveDepthAndAggregateSizeLimits() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            for(String reply:List.of("*1\r\n".repeat(40)+"+END\r\n","*2\r\n$1048570\r\n","$-2\r\n"))
                try(ServerSocket server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))) {
                    Future<?> sent=executor.submit(()->{try(Socket client=server.accept()) {
                        client.setSoTimeout(2000);byte[] request=new byte[64];client.getInputStream().read(request);
                        client.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();
                    }catch(IOException e){throw new UncheckedIOException(e);}});
                    Config cfg=new Config(new MockEnvironment().withProperty("REDIS_URL","redis://127.0.0.1:"+server.getLocalPort()));
                    assertThrows(ApiError.class,()->new ExpiringValues(cfg).shared("PING"));sent.get(5,TimeUnit.SECONDS);
                }
        }finally{executor.shutdownNow();}
    }
}

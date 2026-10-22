package com.duozhi.outbound;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HealthcheckProcessTest {
    private int check(String port) throws Exception {
        ProcessBuilder command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
            System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ControlApplication.class.getName(),"--healthcheck");
        command.environment().put("PORT",port);command.redirectErrorStream(true);command.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process child=command.start();
        try{assertTrue(child.waitFor(10,TimeUnit.SECONDS));return child.exitValue();}finally{if(child.isAlive())child.destroyForcibly();}
    }
    @Test
    void cliHealthcheckOnlyAcceptsNumericLoopbackPortAndNeverFollowsRedirects() throws Exception {
        AtomicInteger redirected=new AtomicInteger();
        HttpServer target=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0),probe=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        target.createContext("/",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        probe.createContext("/readyz",exchange->{exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+target.getAddress().getPort()+"/unexpected");exchange.sendResponseHeaders(302,-1);exchange.close();});
        target.start();probe.start();
        try{
            assertEquals(1,check(Integer.toString(probe.getAddress().getPort())));assertEquals(0,redirected.get());
            assertNotEquals(0,check("0"));assertNotEquals(0,check("65536"));
            assertNotEquals(0,check("18000@127.0.0.1:"+target.getAddress().getPort()));assertEquals(0,redirected.get());
        }finally{target.stop(0);probe.stop(0);}
    }
}

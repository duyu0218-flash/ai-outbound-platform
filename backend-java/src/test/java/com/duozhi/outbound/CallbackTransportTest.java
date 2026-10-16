package com.duozhi.outbound;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class CallbackTransportTest {
    InputStream bytes(String value){return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));}
    @Test void interimResponsesDoNotTriggerDuplicateDelivery() throws Exception {
        assertEquals(204,IntegrationTasks.responseStatus(bytes("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 103 Early Hints\r\nLink: /foo\r\n\r\nHTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")));
        assertEquals(503,IntegrationTasks.responseStatus(bytes("HTTP/1.0 503 Unavailable\r\n\r\n")));
    }
    @Test void truncatedResponsesAndProtocolUpgradeCannotAcknowledgeDelivery(){
        for(String response:new String[]{"HTTP/1.1 200 OK","HTTP/1.1 200 OK\r\nIncomplete: yes","HTTP/1.1 101 Switching Protocols\r\n\r\n","garbage\r\n"})assertThrows(IOException.class,()->IntegrationTasks.responseStatus(bytes(response)));
    }
    @Test void callbackAddressFilterBlocksPrivateAndTransitionNetworks() throws Exception {
        for(String address:new String[]{"127.0.0.1","10.0.0.1","169.254.169.254","100.64.0.1","192.0.2.1","198.51.100.1","203.0.113.1","::1","fc00::1","2001:db8::1","2001:2::1","2002:7f00:1::1","3fff::1"})assertFalse(IntegrationTasks.publicAddress(InetAddress.getByName(address)),address);
        assertTrue(IntegrationTasks.publicAddress(InetAddress.getByName("8.8.8.8")));assertTrue(IntegrationTasks.publicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }
}

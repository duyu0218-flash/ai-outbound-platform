package com.duozhi.services;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** One authenticated connection per command; subscriptions have their own socket. */
final class EslClient {
    record Frame(Map<String,String> headers, byte[] body) { }
    private final Settings settings;
    EslClient(Settings settings) { this.settings = settings; }
    static Frame read(InputStream input) throws IOException {
        Map<String,String> headers = new HashMap<>(); int total = 0;
        while (true) {
            var line = new ByteArrayOutputStream(); int b;
            while ((b = input.read()) != '\n') { if (b == -1) throw new EOFException("ESL disconnected"); if (++total > 65536) throw new IOException("ESL header limit"); line.write(b); }
            String value = line.toString(StandardCharsets.UTF_8).replaceAll("\r$", ""); if (value.isEmpty()) break;
            int colon = value.indexOf(':'); if (colon > 0) headers.put(value.substring(0, colon), URLDecoder.decode(value.substring(colon + 1).stripLeading().replace("+", "%2B"), StandardCharsets.UTF_8));
        }
        int length; try { length = Integer.parseInt(headers.getOrDefault("Content-Length", "0")); } catch (NumberFormatException invalid) { throw new IOException("invalid ESL length"); }
        if (length < 0 || length > 1048576) throw new IOException("ESL body limit");
        byte[] body = input.readNBytes(length); if (body.length != length) throw new EOFException("ESL partial frame"); return new Frame(headers, body);
    }
    private void send(Socket socket, String command) throws IOException {
        if (command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0) throw new IOException("ESL command contains newline");
        socket.getOutputStream().write((command + "\n\n").getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
    }
    private Socket connect() throws IOException {
        Socket socket = new Socket();
        try {
            int timeout = (int)(settings.decimal("FREESWITCH_ESL_TIMEOUT_SEC", 5, .1, 300) * 1000);
            socket.connect(new InetSocketAddress(settings.get("FREESWITCH_ESL_HOST", "freeswitch"), (int)settings.number("FREESWITCH_ESL_PORT", 8021, 1, 65535)), timeout); socket.setSoTimeout(timeout);
            if (!read(socket.getInputStream()).headers.getOrDefault("Content-Type", "").equals("auth/request")) throw new IOException("unexpected ESL greeting");
            send(socket, "auth " + settings.secret("FREESWITCH_ESL_PASSWORD"));
            if (!read(socket.getInputStream()).headers.getOrDefault("Reply-Text", "").startsWith("+OK")) throw new IOException("ESL authentication failed");
            return socket;
        } catch (Exception error) { socket.close(); throw error; }
    }
    String command(String command, boolean background) throws IOException {
        try (Socket socket = connect()) {
            send(socket, (background ? "bgapi " : "api ") + command); Frame frame = read(socket.getInputStream());
            String reply = frame.body.length == 0 ? frame.headers.getOrDefault("Reply-Text", "") : new String(frame.body, StandardCharsets.UTF_8).strip();
            if (reply.startsWith("-ERR") || (background && !reply.startsWith("+OK"))) throw new IOException("ESL rejected command");
            return background && reply.contains("Job-UUID:") ? reply.substring(reply.indexOf("Job-UUID:") + 9).strip() : background ? "" : reply;
        }
    }
    Socket subscribe(Consumer<Map<String,Object>> event) throws IOException {
        Socket socket = connect();
        try {
            send(socket, "event json DTMF CHANNEL_CREATE CHANNEL_PROGRESS CHANNEL_PROGRESS_MEDIA CHANNEL_ANSWER CHANNEL_BRIDGE CHANNEL_UNBRIDGE CHANNEL_EXECUTE_COMPLETE CHANNEL_HANGUP CHANNEL_HANGUP_COMPLETE BACKGROUND_JOB CUSTOM");
            if (!read(socket.getInputStream()).headers.getOrDefault("Reply-Text", "").startsWith("+OK")) throw new IOException("ESL subscription rejected");
            socket.setSoTimeout(0); return socket;
        } catch (Exception error) { socket.close(); throw error; }
    }
    void events(Socket socket, Consumer<Map<String,Object>> callback) throws IOException {
        while (!socket.isClosed()) {
            Frame frame = read(socket.getInputStream()); String type = frame.headers.getOrDefault("Content-Type", "");
            if (type.equals("text/event-json")) callback.accept(Support.object(frame.body));
            else if (type.equals("text/disconnect-notice")) throw new EOFException("ESL disconnected");
        }
    }
}

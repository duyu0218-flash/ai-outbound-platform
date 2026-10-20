package com.duozhi.outbound;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Deployment-selected regular files only; enforce the bound while reading too. */
final class ConfigFiles {
    private ConfigFiles() {}
    static String readUtf8(String filename,int maxBytes) throws IOException {
        Path path=Path.of(filename);
        if(!Files.isRegularFile(path)||Files.size(path)>maxBytes)throw new IOException("invalid configuration file");
        try(InputStream input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes(maxBytes+1);
            if(bytes.length>maxBytes)throw new IOException("configuration file exceeds limit");
            return new String(bytes,StandardCharsets.UTF_8);
        }
    }
}

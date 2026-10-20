package com.duozhi.outbound;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JsonSupportTest {
    @Test void hashAndWebhookMacKeepTheirGoldenLowercaseEncoding(){
        assertThat(JsonSupport.hex(new byte[]{0,1,15,16,(byte)128,127,(byte)255})).isEqualTo("00010f10807fff");
        assertThat(JsonSupport.sha("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(JsonSupport.hmac("\u000b".repeat(20),"Hi There".getBytes(StandardCharsets.US_ASCII))).isEqualTo("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
    }
}

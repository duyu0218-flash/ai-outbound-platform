package com.duozhi.outbound.voiceai.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.auth.dao.AuthIdentityMapper;
import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.system.service.Audit;
import com.duozhi.outbound.voiceai.dao.VoiceTrialMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BailianLifecycleTest {
    final VoiceTrialMapper db = mock(VoiceTrialMapper.class);
    final AuthIdentityMapper identities = mock(AuthIdentityMapper.class);

    BailianTrials.Live live() {
        var user = new SysUser();
        user.setId(1);
        user.setTenantId(1);
        user.setRole("admin");
        when(identities.findByIdForJwt(1)).thenReturn(user);
        var tenant = new Tenant();
        tenant.setId(1);
        when(identities.findTenantById(1)).thenReturn(tenant);
        var trials =
                new BailianTrials(
                        new Config(new MockEnvironment()),
                        mock(VoiceAiCredentials.class),
                        db,
                        mock(Store.class),
                        identities,
                        mock(com.duozhi.outbound.system.service.Settings.class),
                        mock(Transactions.class),
                        mock(Audit.class));
        var l =
                trials
                .new Live(
                        "trial",
                        new Identity(1, Map.of("id", 1), Set::of),
                        new Settings(Map.of()),
                        "test",
                        Map.of());
        l.dialog = mock(BailianDialog.class);
        return l;
    }

    @Test
    void disconnectCannotPreventProviderAndResponseCleanup() {
        var l = live();
        var closed = new AtomicBoolean();
        l.transportClose = () -> closed.set(true);
        l.response = new ResponseScope();
        l.sink =
                e -> {
                    throw new IllegalStateException("browser gone");
                };
        assertThatCode(() -> l.end("interrupted")).doesNotThrowAnyException();
        verify(l.dialog).close();
        verify(db).save(eq(1L), eq("trial"), eq("interrupted"), anyString(), anyString());
        assertThat(closed).isTrue();
        assertThat(l.closed).isTrue();
    }

    @Test
    void databaseFailureStillClosesBillableTransport() {
        var l = live();
        doThrow(new IllegalStateException("db unavailable"))
                .when(db)
                .save(anyLong(), anyString(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> l.end("failed")).isInstanceOf(IllegalStateException.class);
        verify(l.dialog).close();
        assertThat(l.closed).isTrue();
    }

    @Test
    void interruptedApprovedAudioAndPartialInputAreArchivedOnce() {
        var l = live();
        l.epoch = 3;
        l.approved.writeBytes(new byte[960]);
        l.input.writeBytes(new byte[3200]);
        l.cancelResponse();
        l.end("interrupted");
        l.end("interrupted");
        verify(db)
                .audio(
                        argThat(
                                row ->
                                        row.get("audio").equals("out-3")
                                                && ((byte[]) row.get("pcm")).length == 960));
        verify(db)
                .audio(
                        argThat(
                                row ->
                                        row.get("direction").equals("input")
                                                && ((byte[]) row.get("pcm")).length == 3200));
        verify(db, times(2)).audio(anyMap());
        verify(l.dialog).close();
    }

    @Test
    void staleResponseCannotReleaseAudioAndRevokedTenantCannotContinue() throws Exception {
        var l = live();
        l.discardOutput = true;
        l.providerAudio(new byte[960]);
        verifyNoInteractions(l.dialog);
        var tenant = new Tenant();
        tenant.setId(1);
        tenant.setEnabled(false);
        when(identities.findTenantById(1)).thenReturn(tenant);
        assertThatThrownBy(l::authorize).hasMessageContaining("授权已失效");
    }

    @Test
    void inputAudioBeforeCaptureAndOversizedAudioAreRejected() {
        var l = live();
        assertThatThrownBy(() -> l.audio(new byte[3200])).hasMessageContaining("limit");
        verifyNoInteractions(l.dialog);
    }
}

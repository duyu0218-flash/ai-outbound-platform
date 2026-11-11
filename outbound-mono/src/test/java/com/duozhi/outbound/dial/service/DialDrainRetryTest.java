package com.duozhi.outbound.dial.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;

class DialDrainRetryTest {
    @Test
    void rolledBackDeadlockRetriesTheCompleteBarrierOnce() {
        var tx = mock(Transactions.class);
        var jdbc = mock(JdbcTemplate.class);
        var control = new DialDrainControl(jdbc, tx);
        when(tx.serialized(any(Supplier.class)))
                .thenThrow(deadlock())
                .thenReturn(Map.of("phase", "SEND_CLOSED"));
        assertThat(control.closeSending()).containsEntry("phase", "SEND_CLOSED");
        verify(tx, times(2)).serialized(any(Supplier.class));
    }

    @Test
    void repeatedDeadlockReturnsExplicitBackpressureAndNeverAcknowledgesClosure() {
        var tx = mock(Transactions.class);
        var control = new DialDrainControl(mock(JdbcTemplate.class), tx);
        when(tx.serialized(any(Supplier.class))).thenThrow(deadlock());
        assertThatThrownBy(control::closeSending)
                .isInstanceOf(ApiError.class)
                .hasMessage("DIAL_CONTROL_BUSY");
        verify(tx, times(2)).serialized(any(Supplier.class));
    }

    @Test
    void arbitraryDatabaseFailureIsNotRetriedAsAConfirmedRollback() {
        var tx = mock(Transactions.class);
        var control = new DialDrainControl(mock(JdbcTemplate.class), tx);
        var failure =
                new CannotAcquireLockException(
                        "unknown commit outcome", new SQLException("connection lost", "08006", 0));
        when(tx.serialized(any(Supplier.class))).thenThrow(failure);
        assertThatThrownBy(control::closeSending).isSameAs(failure);
        verify(tx).serialized(any(Supplier.class));
    }

    private CannotAcquireLockException deadlock() {
        return new CannotAcquireLockException(
                "deadlock victim", new SQLException("rolled back", "40001", 1213));
    }
}

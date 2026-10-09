package com.example.miniseckill.mq;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.*;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.*;

class ConsumerFailureClassifierTest {
    static Stream<Throwable> retryableFailures() {
        return Stream.of(new CannotGetJdbcConnectionException("pool"),
                new CannotCreateTransactionException("begin"),
                new TransactionSystemException("commit outcome unknown"),
                new RecoverableDataAccessException("recoverable"),
                new TransientDataAccessResourceException("transient"),
                new SQLTransientConnectionException("timeout"),
                new SQLRecoverableException("reconnect"), new SQLException("link", "08006"),
                new SQLException("deadlock", "40001"));
    }
    @ParameterizedTest @MethodSource("retryableFailures")
    void recognizesDirectAndWrappedInfrastructureErrors(Throwable failure) {
        assertTrue(ConsumerFailureClassifier.retryable(failure));
        assertTrue(ConsumerFailureClassifier.retryable(new IllegalStateException("wrapped", failure)));
    }
    @Test void businessAndIntegrityFailuresAreNotMistakenForStarvation() {
        assertFalse(ConsumerFailureClassifier.retryable(new IllegalArgumentException("payload")));
        assertFalse(ConsumerFailureClassifier.retryable(new DuplicateKeyException("duplicate")));
        assertFalse(ConsumerFailureClassifier.retryable(new SQLException("constraint", "23000")));
        assertFalse(ConsumerFailureClassifier.retryable(new SQLException("no state")));
    }
    @Test void causeCyclesAreBounded() {
        Throwable first = new Exception("first");
        Throwable second = new Exception("second", first);
        first.initCause(second);
        assertFalse(ConsumerFailureClassifier.retryable(first));
        assertFalse(ConsumerFailureClassifier.retryable(null));
    }
}

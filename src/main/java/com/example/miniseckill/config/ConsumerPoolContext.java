package com.example.miniseckill.config;

/** Only the listener owns the reserved pool. HTTP, publisher callbacks, logging
 * executors and recovery jobs keep using the admission pool. Never inheritable. */
public final class ConsumerPoolContext {
    private static final ThreadLocal<Boolean> CONSUMER = new ThreadLocal<>();

    private ConsumerPoolContext() { }

    public static boolean isConsumer() {
        return Boolean.TRUE.equals(CONSUMER.get());
    }

    public static Scope enter() {
        Boolean previous = CONSUMER.get();
        CONSUMER.set(Boolean.TRUE);
        return () -> {
            if (previous == null) {
                CONSUMER.remove();
            } else {
                CONSUMER.set(previous);
            }
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}

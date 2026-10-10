package com.example.miniseckill.service;

import com.example.miniseckill.config.StockCoordinationProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** One-shot local file barriers. Not an HTTP backdoor; disabled outside the explicit test profile. */
@Component
public class CoordinationFaults {
    private final StockCoordinationProperties policy;
    private final Path directory;
    public CoordinationFaults(StockCoordinationProperties policy, Environment environment) {
        this.policy = policy;
        if (policy.isFaultsEnabled() && (!environment.acceptsProfiles(Profiles.of("coordination-test"))
                || policy.getFaultDirectory() == null || policy.getFaultDirectory().isBlank())) {
            throw new IllegalArgumentException("fault barriers require coordination-test profile and a private directory");
        }
        directory = policy.isFaultsEnabled() ? Path.of(policy.getFaultDirectory()).toAbsolutePath() : null;
    }
    public void hit(String point, String identity) {
        if (directory == null) return;
        // point is a code-owned constant, never request input.
        Path arm = directory.resolve(point + ".arm");
        if (!Files.exists(arm)) return;
        try {
            try { Files.move(arm, directory.resolve(point + ".claimed")); }
            catch (NoSuchFileException | FileAlreadyExistsException raced) { return; }
            Files.writeString(directory.resolve(point + ".hit"), identity + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            long start = System.nanoTime();
            while (!Files.exists(directory.resolve(point + ".release"))) {
                if (System.nanoTime() - start > policy.getFaultWait().toNanos())
                    throw new IllegalStateException("fault barrier timed out: " + point);
                Thread.sleep(20);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fault barrier interrupted: " + point, e);
        } catch (IOException e) {
            throw new IllegalStateException("fault barrier IO: " + point, e);
        }
    }
}

package com.ratelimit.gateway.rules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gateway's own copy of the last snapshot it applied, so it can start with real rules while Redis is
 * down. It contains API key hashes (not keys). Failures are logged and never stop the gateway: losing
 * this copy only matters if Redis is also unavailable at the next start.
 *
 * <p>Gateways on one machine need different files.
 */
public class RuleSnapshotFile {

    private static final Logger log = LoggerFactory.getLogger(RuleSnapshotFile.class);

    private final Path path; // null: saving and loading are turned off

    /** @param location the file path; blank turns the saved copy off */
    public RuleSnapshotFile(String location) {
        this.path = location.isBlank() ? null : Path.of(location).toAbsolutePath();
    }

    public Optional<String> read() {
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Cannot read the saved rules at {}: {}", path, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Writes a temporary file and then renames it over the old one, so a crash while saving leaves the
     * previous copy whole rather than a half-written file.
     */
    public void save(String snapshot) {
        if (path == null) {
            return;
        }
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), "rules-", ".tmp");
            Files.writeString(temporary, snapshot, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Cannot save the rules to {}: {}", path, e.toString());
            deleteQuietly(temporary);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // a stray temporary file is harmless
            }
        }
    }
}

package com.compsych.licmon.store;

import com.compsych.licmon.Json;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Makes the in-memory store durable across runs: load the whole thing on start, write the whole
 * thing after each mutating command. Writes go to a temp file and are atomically renamed over the
 * old one, so a crash mid-write leaves the previous good snapshot in place.
 */
public final class SnapshotFile {

    private final Path path;

    public SnapshotFile(Path path) {
        this.path = path;
    }

    public InMemoryStore load() throws IOException {
        if (!Files.exists(path)) return new InMemoryStore();
        Snapshot snap = Json.MAPPER.readValue(path.toFile(), Snapshot.class);
        return InMemoryStore.fromSnapshot(snap);
    }

    public void save(InMemoryStore store) throws IOException {
        Path dir = path.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, path.getFileName().toString(), ".tmp");
        try {
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), store.toSnapshot());
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public Path path() {
        return path;
    }
}

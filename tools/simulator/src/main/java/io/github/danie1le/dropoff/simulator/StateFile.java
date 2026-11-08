package io.github.danie1le.dropoff.simulator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Reads and writes the {@link SimState} JSON file. */
final class StateFile {

    private static final JsonMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private StateFile() {}

    static void write(Path file, SimState state) {
        Path parent = file.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        JSON.writeValue(file.toFile(), state);
    }

    static SimState read(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalStateException("No state file at " + file + "; run `seed` first.");
        }
        return JSON.readValue(file.toFile(), SimState.class);
    }
}

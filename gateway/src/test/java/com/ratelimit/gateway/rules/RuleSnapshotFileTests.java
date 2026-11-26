package com.ratelimit.gateway.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleSnapshotFileTests {

    @TempDir
    Path dir;

    private RuleSnapshotFile file(Path path) {
        return new RuleSnapshotFile(path.toString());
    }

    @Test
    void whatIsSavedCanBeReadBack() {
        RuleSnapshotFile file = file(dir.resolve("rules.json"));

        file.save("{\"v\": \"héllo\"}");

        assertThat(file.read()).contains("{\"v\": \"héllo\"}");
    }

    @Test
    void savingAgainReplacesTheCopyAndLeavesNoTemporaryFiles() throws Exception {
        RuleSnapshotFile file = file(dir.resolve("rules.json"));
        file.save("first");
        file.save("second");

        assertThat(file.read()).contains("second");
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("rules.json");
        }
    }

    @Test
    void missingDirectoriesAreCreated() {
        RuleSnapshotFile file = file(dir.resolve("a/b/rules.json"));

        file.save("x");

        assertThat(file.read()).contains("x");
    }

    @Test
    void aMissingFileReadsAsNothing() {
        assertThat(file(dir.resolve("absent.json")).read()).isEmpty();
    }

    @Test
    void aBlankLocationTurnsTheCopyOff() {
        RuleSnapshotFile off = new RuleSnapshotFile("");

        off.save("x");

        assertThat(off.read()).isEmpty();
    }

    @Test
    void failingToSaveNeverThrows() throws Exception {
        Path notADirectory = Files.writeString(dir.resolve("plain-file"), "x");

        file(notADirectory.resolve("rules.json")).save("y"); // its parent is a file: cannot work

        assertThat(Files.readString(notADirectory)).isEqualTo("x");
    }
}

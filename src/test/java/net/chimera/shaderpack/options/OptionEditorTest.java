package net.chimera.shaderpack.options;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settings screen's view of a pack's values: applied, pending and default, as Iris keeps them. */
final class OptionEditorTest {
    private static final String SOURCE = """
            #define SHADOWS
            //#define FOG
            #define QUALITY 2 // [1 2 3]
            #ifdef SHADOWS
            #endif
            #ifdef FOG
            #endif
            """;
    private static final String PROPERTIES = """
            profile.LOW=!SHADOWS QUALITY=1
            profile.HIGH=SHADOWS QUALITY=3
            """;

    @BeforeEach
    @AfterEach
    void emptyQueue() {
        PendingOptions.clear();
    }

    private static OptionEditor editor(Path root, Map<String, String> applied) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), SOURCE);
        Files.writeString(shaders.resolve("shaders.properties"), PROPERTIES);
        ShaderPackOptions pack = ShaderPackOptions.load(shaders, applied);
        return new OptionEditor(pack, pack.values());
    }

    @Test
    void anOptionIsModifiedWhilePendingDiffersFromApplied(@TempDir Path root) throws IOException {
        OptionEditor editor = editor(root, Map.of("QUALITY", "3"));
        assertFalse(editor.modified("QUALITY"));
        editor.set("QUALITY", "1");
        assertTrue(editor.modified("QUALITY"));
        assertEquals("3", editor.applied().choiceOrDefault("QUALITY"), "edits never touch the applied values");
        editor.set("QUALITY", "3");
        assertFalse(editor.modified("QUALITY"), "back at the applied value is no change");
        editor.reset("FOG");
        assertFalse(editor.modified("FOG"), "queueing the default of an option at its default changes nothing");
        assertEquals(Map.of("QUALITY", "3", "FOG", "false"), PendingOptions.view());
    }

    @Test
    void choicesWrapAndOffListValuesCountFromTheStart(@TempDir Path root) throws IOException {
        OptionEditor editor = editor(root, Map.of());
        var quality = editor.pack().options().choices().get("QUALITY").option();
        assertEquals("3", editor.cycle(quality, 1));
        editor.set("QUALITY", "3");
        assertEquals("1", editor.cycle(quality, 1), "forward from the last value wraps to the first");
        editor.set("QUALITY", "7");
        assertEquals(-1, editor.choiceIndex(quality));
        assertEquals("2", editor.shownChoice(quality), "an off-list value shows the applied one");
        assertFalse(editor.modified("QUALITY"), "and the cell showing the applied value is not marked");
        assertEquals("2", editor.cycle(quality, 1), "and steps on from the first entry");
        assertEquals("3", editor.cycle(quality, -1));
    }

    @Test
    void profilesQueueEveryValueTheySet(@TempDir Path root) throws IOException {
        OptionEditor editor = editor(root, Map.of());
        assertTrue(editor.profile().current().isEmpty(), "defaults match neither profile");
        editor.applyProfile(editor.profile().next());
        assertEquals("LOW", editor.profile().current().orElseThrow().name());
        assertFalse(editor.pending().toggleOrDefault("SHADOWS"));
        assertTrue(editor.modified("SHADOWS") && editor.modified("QUALITY"));
    }

    @Test
    void importReplacesRatherThanMerges(@TempDir Path root) throws IOException {
        OptionEditor editor = editor(root, Map.of("QUALITY", "3"));
        editor.set("SHADOWS", "false");
        editor.importValues(Map.of("FOG", "true", "UNKNOWN", "1"));
        assertEquals(Map.of("QUALITY", "2", "FOG", "true", "UNKNOWN", "1"), PendingOptions.view(),
                "earlier edits go, applied changes return to their defaults, the file's values go on top");
        assertEquals("2", editor.pending().choiceOrDefault("QUALITY"));
        assertTrue(editor.pending().toggleOrDefault("FOG"));
    }

    @Test
    void aLoadTakesTheQueueAndTheResetRequest(@TempDir Path root) {
        Path pack = root.resolve("pack.zip");
        PendingOptions.bind(pack);
        PendingOptions.put("QUALITY", "1");
        PendingOptions.requestReset();
        PendingOptions.Drained drained = PendingOptions.drain(pack);
        assertEquals(Map.of("QUALITY", "1"), drained.values());
        assertTrue(drained.reset());
        assertTrue(PendingOptions.isEmpty() && !PendingOptions.resetRequested());
    }

    @Test
    void anotherPacksQueueIsDroppedNeverApplied(@TempDir Path root) {
        Path first = root.resolve("first.zip");
        Path second = root.resolve("second.zip");
        PendingOptions.bind(first);
        PendingOptions.put("QUALITY", "1");
        PendingOptions.requestReset();
        assertEquals(PendingOptions.Drained.NONE, PendingOptions.drain(second),
                "a load of another pack (shaders turned back on, K, the pack command) leaves its file alone");
        assertTrue(PendingOptions.isEmpty() && !PendingOptions.resetRequested());

        PendingOptions.put("QUALITY", "3");
        PendingOptions.bind(root.resolve("x/../first.zip"));
        assertEquals(Map.of("QUALITY", "3"), PendingOptions.view(), "the same pack keeps its queue");
        PendingOptions.bind(second);
        assertTrue(PendingOptions.isEmpty(), "showing another pack's settings drops the first pack's queue");
    }
}

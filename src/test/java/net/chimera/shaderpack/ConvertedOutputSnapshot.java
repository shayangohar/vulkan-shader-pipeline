package net.chimera.shaderpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.chimera.testing.TestPacks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Every shader one pack compiles in {@link PackCompileTest}: each prepared stage, input variant
 * and compute, by key ({@code gbuffers_terrain.vsh}, {@code gbuffers_hand [hand input variant
 * ...].vsh}), with a hash of its converted source and of its SPIR-V. The baseline
 * {@code testpacks/baselines/converted/<pack>.json} holds both as "source spirv"; a change in
 * either names the key. GLSL front-end migrations (EPIC-715) must keep both, except where a
 * commit names a fix. With {@code chimera.snapshot.spirvOnly} only SPIR-V must match: full macro
 * expansion rewrites the source text the compiler would expand the same way.
 *
 * <p>Each run writes the sources to {@code build/converted-output/current/<pack>/}; a key that
 * matches its baseline is also copied to {@code baseline/}, so a later change diffs against it.
 */
final class ConvertedOutputSnapshot {
    private static final int HASH_CHARS = 16;
    private static final Path OUTPUT = Path.of("build", "converted-output");
    private static final int LISTED_CHANGES = 25;

    private final String pack;
    private final Map<String, String> hashes = new TreeMap<>();
    private final Map<String, String> sources = new TreeMap<>();

    ConvertedOutputSnapshot(String pack) {
        this.pack = pack;
    }

    /** One compiled shader: its source, and its SPIR-V's hash (null when it failed to compile). */
    void record(String key, String source, String spirvHash) {
        String sourceHash = shortHash(source);
        String previous = hashes.put(key, sourceHash + " " + (spirvHash == null ? "failed" : spirvHash));
        if (previous != null && !previous.equals(hashes.get(key))) {
            throw new IllegalStateException(pack + ": two different shaders under one key " + key);
        }
        sources.put(key, source);
    }

    static String shortHash(String text) {
        return ConformanceReport.sha256(text.getBytes(StandardCharsets.UTF_8)).substring(0, HASH_CHARS);
    }

    /**
     * Compares with the recorded baseline, or with {@code update} rewrites it. Returns one failure
     * per pack naming the changed keys, or none.
     */
    List<String> compareWithBaseline(boolean update, boolean spirvOnly) {
        writeSources("current", sources.keySet());
        Path baselinePath = baselinePath();
        if (update) {
            write(baselinePath, new GsonBuilder().setPrettyPrinting().create().toJson(hashes) + "\n");
            writeSources("baseline", sources.keySet());
            return List.of();
        }
        if (!Files.isRegularFile(baselinePath)) {
            return List.of(pack + ": no converted-output baseline (run with -Pchimera.snapshot.update=true)");
        }
        Map<String, String> baseline = read(baselinePath);
        List<String> changes = changes(baseline, spirvOnly);
        TreeSet<String> unchanged = new TreeSet<>(sources.keySet());
        changes.forEach(change -> unchanged.remove(change.substring(change.indexOf(' ') + 1)));
        writeSources("baseline", unchanged);
        if (changes.isEmpty()) return List.of();
        write(OUTPUT.resolve("current").resolve(directory()).resolve("changes.txt"), String.join("\n", changes) + "\n");
        List<String> listed = changes.subList(0, Math.min(LISTED_CHANGES, changes.size()));
        return List.of(pack + ": converted output differs from the baseline in " + changes.size() + " shaders "
                + "(diff build/converted-output/baseline and current/" + directory() + "): "
                + String.join(", ", listed) + (changes.size() > listed.size() ? ", ..." : ""));
    }

    /** "source key", "spirv key", "new key" or "removed key" for each difference, sorted by key. */
    private List<String> changes(Map<String, String> baseline, boolean spirvOnly) {
        List<String> changes = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(baseline.keySet());
        keys.addAll(hashes.keySet());
        for (String key : keys) {
            String expected = baseline.get(key);
            String actual = hashes.get(key);
            if (expected == null) {
                changes.add("new " + key);
            } else if (actual == null) {
                changes.add("removed " + key);
            } else if (!spirv(expected).equals(spirv(actual))) {
                changes.add("spirv " + key);
            } else if (!spirvOnly && !expected.equals(actual)) {
                changes.add("source " + key);
            }
        }
        return changes;
    }

    private static String spirv(String entry) {
        return entry.substring(entry.indexOf(' ') + 1);
    }

    private Path baselinePath() {
        return TestPacks.baseline("converted/" + directory());
    }

    private String directory() {
        return pack.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void writeSources(String side, Iterable<String> keys) {
        Path root = OUTPUT.resolve(side).resolve(directory());
        for (String key : keys) {
            write(root.resolve(key.replaceAll("[^A-Za-z0-9._-]", "_")), sources.get(key));
        }
    }

    private static Map<String, String> read(Path path) {
        try {
            return new Gson().fromJson(Files.readString(path), new TypeToken<TreeMap<String, String>>() {}.getType());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void write(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}

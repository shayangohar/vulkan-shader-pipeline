package net.chimera.shaderpack;

import com.google.gson.GsonBuilder;
import net.chimera.shaderpack.options.OptionMenu;
import net.chimera.shaderpack.options.PendingOptions;
import net.chimera.shaderpack.options.ShaderOption;
import net.chimera.shaderpack.options.ShaderPackOptions;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sweeps the options the settings screen offers, one change at a time over each pack's defaults:
 * every toggle flipped, every choice that can change which code runs set to each of its values
 * (a long numeric list: its two ends), and every profile. Each variant goes through the same
 * analysis and compile as the pack compile gate, on the sources edited as Iris edits them, and
 * the sweep records what differs from the defaults: programs newly rejected (with the report's
 * reasons) and newly failing compiles. Writes build/option-sweep/<pack>.json and summary.md.
 * Run with {@code tools/check-packs.ps1 optionSweep}; {@code -Pchimera.sweep.packs=<name>;<name>}
 * narrows it to the packs whose file names contain those words.
 */
@Tag("sweep")
final class OptionSweepTest {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
    private static final Pattern CONDITION = Pattern.compile("^\\s*#\\s*(?:if|elif|ifdef|ifndef)\\b(.*)$");
    private static final int MAX_LISTED_VALUES = 8;
    private static final Path OUTPUT = Path.of("build", "option-sweep");

    record Variant(String label, Map<String, String> values) {}

    /** What one variant runs: rejected programs with their reasons, and compile failures. */
    record Outcome(SortedMap<String, List<String>> rejected, SortedSet<String> failures, String error,
                   boolean applied) {}

    /** What one variant changes against the defaults. */
    record Change(String variant, SortedMap<String, List<String>> newlyRejected, SortedSet<String> newFailures,
                  String error, boolean applied) {
        boolean matters() {
            return !newlyRejected.isEmpty() || !newFailures.isEmpty() || error != null || !applied;
        }
    }

    @Test
    void sweep() throws Exception {
        Files.createDirectories(OUTPUT.resolve("packs"));
        List<Path> packs = new ArrayList<>(List.of(TestPacks.complementary(), TestPacks.bsl()));
        packs.addAll(TestPacks.extra());
        String filter = System.getProperty("chimera.sweep.packs", "");
        StringBuilder summary = new StringBuilder("# Option sweep\n");
        for (Path source : packs) {
            if (!filter.isBlank() && Stream.of(filter.split(";")).noneMatch(
                    word -> source.getFileName().toString().toLowerCase().contains(word.toLowerCase()))) {
                continue;
            }
            summary.append(sweepPack(source));
        }
        Files.writeString(OUTPUT.resolve("summary.md"), summary.toString());
    }

    /**
     * Explains named variants in full: {@code -Pchimera.sweep.explain=<pack word>:<OPTION=value or profile
     * name>;...} prints every report deviation of each program the variant rejects that the defaults run,
     * the full compile errors, and a profile's entries that did not reach the pack.
     */
    @Test
    void explain() throws Exception {
        String requests = System.getProperty("chimera.sweep.explain", "");
        if (requests.isBlank()) return;
        List<Path> packs = new ArrayList<>(List.of(TestPacks.complementary(), TestPacks.bsl()));
        packs.addAll(TestPacks.extra());
        StringBuilder text = new StringBuilder();
        for (String request : requests.split(";")) {
            String word = request.substring(0, request.indexOf(':')).toLowerCase();
            String variant = request.substring(request.indexOf(':') + 1);
            Path source = packs.stream().filter(path -> path.getFileName().toString().toLowerCase().contains(word))
                    .findFirst().orElseThrow();
            Path pack = Files.copy(source, OUTPUT.resolve("packs").resolve(source.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
            text.append(explainVariant(pack, variant));
        }
        Files.writeString(OUTPUT.resolve("explain.md"), text.toString());
    }

    private static String explainVariant(Path pack, String variant) {
        StringBuilder text = new StringBuilder("\n## ").append(pack.getFileName()).append(" ").append(variant);
        Map<String, String> values = new TreeMap<>();
        SortedSet<String> defaultsRejected = new TreeSet<>(evaluate(pack, Map.of()).rejected().keySet());
        try (PackSource.LoadResult defaults = PackSource.loadResult(pack)) {
            ShaderPackOptions options = defaults.options().pack();
            if (variant.startsWith("profile ")) {
                String name = variant.substring("profile ".length());
                options.profiles().declared().stream().filter(profile -> profile.name().equals(name))
                        .findFirst().ifPresent(profile -> values.putAll(profile.values()));
            } else {
                values.put(variant.substring(0, variant.indexOf('=')), variant.substring(variant.indexOf('=') + 1));
            }
        }
        try (PackSource.LoadResult loaded = PackSource.loadResult(pack, "minecraft:overworld",
                new PendingOptions.Drained(values, false))) {
            ShaderPackOptions staged = loaded.options().pack();
            values.forEach((name, value) -> {
                String declared = defaultText(staged, name);
                if (declared != null && !declared.equals(value) && !staged.values().changes().containsKey(name)) {
                    text.append("\n- not applied: ").append(name).append('=').append(value)
                            .append(" (default ").append(declared).append(')');
                } else if (declared == null) {
                    text.append("\n- undeclared: ").append(name).append('=').append(value);
                }
            });
            text.append("\n- staging: ").append(loaded.deviations().stream()
                    .filter(deviation -> !deviation.startsWith("PACK_OPTION_OVERRIDE")).toList());
            PackProbe.Analysis analysis = PackProbe.analyze(pack, loaded);
            for (String program : PackCompileTest.rejectedProgramsIn(loaded.shadersDir(), analysis)) {
                if (defaultsRejected.contains(program)) continue;
                var report = analysis.report().program(program);
                text.append("\n- rejects ").append(program).append(": ")
                        .append(report == null ? "(no report)" : report.deviations());
            }
            for (String failure : PackCompileTest.run(pack, loaded.shadersDir(), analysis).failures()) {
                text.append("\n- fails ").append(failure);
            }
        }
        return text.append('\n').toString();
    }

    /** One pack: its defaults, every variant against them, and a determinism check at the end. */
    private static String sweepPack(Path source) throws Exception {
        // A copy without the player's <pack>.txt beside it, so every variant starts from the pack's defaults.
        Path pack = Files.copy(source, OUTPUT.resolve("packs").resolve(source.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);
        Outcome defaults = evaluate(pack, Map.of());
        List<Variant> variants;
        Set<String> undeclared = new TreeSet<>();
        try (PackSource.LoadResult loaded = PackSource.loadResult(pack)) {
            ShaderPackOptions options = loaded.options().pack();
            variants = variants(options, structuralNames(loaded.shadersDir()));
            options.profiles().declared().forEach(profile -> profile.values().keySet().stream()
                    .filter(name -> defaultText(options, name) == null).forEach(undeclared::add));
        }
        long started = System.nanoTime();
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
        List<Change> changes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Change>> pending = new ArrayList<>();
            for (Variant variant : variants) {
                pending.add(pool.submit(() -> compare(variant, evaluate(pack, variant.values()), defaults)));
            }
            for (Future<Change> change : pending) changes.add(change.get());
        }
        assertEquals(defaults, evaluate(pack, Map.of()), "the defaults must evaluate the same after the sweep");
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        List<Change> mattering = changes.stream().filter(Change::matters).toList();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("pack", source.getFileName().toString());
        json.put("variants", variants.size());
        json.put("seconds", seconds);
        json.put("defaults", defaults);
        json.put("undeclaredProfileEntries", undeclared);
        json.put("changes", mattering);
        Files.writeString(OUTPUT.resolve(source.getFileName() + ".json"),
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json));
        System.out.printf("[chimera] option sweep %s: %d variants in %ds, %d change what runs%n",
                source.getFileName(), variants.size(), seconds, mattering.size());
        return markdown(source.getFileName().toString(), variants.size(), defaults, undeclared, mattering);
    }

    /**
     * Each toggle flipped, each structural choice at each other value (a long list: its ends), each
     * profile. Only options the settings screen shows: a define the menu never offers (a program's
     * own GBUFFERS_TERRAIN or INCLUDE_SKY guard) is no setting a player can change.
     */
    static List<Variant> variants(ShaderPackOptions options, Set<String> structural) {
        Set<String> shown = shownOptions(options.menu());
        List<Variant> variants = new ArrayList<>();
        options.options().toggles().forEach((name, declared) -> {
            if (!shown.isEmpty() && !shown.contains(name)) return;
            String flipped = Boolean.toString(!declared.option().defaultValue());
            variants.add(new Variant(name + "=" + flipped, Map.of(name, flipped)));
        });
        options.options().choices().forEach((name, declared) -> {
            if (!structural.contains(name) || !shown.isEmpty() && !shown.contains(name)) return;
            ShaderOption.Choice choice = declared.option();
            List<String> values = choice.values().size() > MAX_LISTED_VALUES
                    ? List.of(choice.values().getFirst(), choice.values().getLast()) : choice.values();
            for (String value : new TreeSet<>(values)) {
                if (!value.equals(choice.defaultValue())) {
                    variants.add(new Variant(name + "=" + value, Map.of(name, value)));
                }
            }
        });
        options.profiles().declared().forEach(profile ->
                variants.add(new Variant("profile " + profile.name(), profile.values())));
        return variants;
    }

    /** The options on any page of the settings screen; empty for a pack that lays out none. */
    static Set<String> shownOptions(OptionMenu menu) {
        Set<String> shown = new TreeSet<>();
        List<OptionMenu.Screen> screens = new ArrayList<>(menu.subScreens().values());
        screens.add(menu.main());
        for (OptionMenu.Screen screen : screens) {
            for (OptionMenu.Element element : screen.elements()) {
                if (element instanceof OptionMenu.OptionEntry entry) shown.add(entry.option().name());
            }
        }
        return shown;
    }

    /** Names a preprocessor condition or shaders.properties reads: their values can change which code runs. */
    static Set<String> structuralNames(Path shadersDir) throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(shadersDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                boolean properties = fileName.equals("shaders.properties");
                if (!properties && !fileName.matches(".*\\.(glsl|vsh|fsh|gsh|csh|tcs|tes|inc)")) continue;
                for (String line : new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).split("\\R")) {
                    Matcher condition = CONDITION.matcher(line);
                    String text = properties ? line : condition.matches() ? condition.group(1) : null;
                    if (text == null) continue;
                    Matcher identifier = IDENTIFIER.matcher(text);
                    while (identifier.find()) names.add(identifier.group());
                }
            }
        }
        return names;
    }

    /** The variant's sources as Iris edits them, analysed and compiled as the compile gate does. */
    static Outcome evaluate(Path pack, Map<String, String> values) {
        try (PackSource.LoadResult loaded = PackSource.loadResult(pack, "minecraft:overworld",
                new PendingOptions.Drained(values, false))) {
            PackProbe.Analysis analysis = PackProbe.analyze(pack, loaded);
            SortedMap<String, List<String>> rejected = new TreeMap<>();
            for (String program : PackCompileTest.rejectedProgramsIn(loaded.shadersDir(), analysis)) {
                // A pass with no fragment source draws nothing in Iris either; its computes are planned apart.
                var report = analysis.report().program(program);
                if (report != null && report.deviations().contains("MISSING_FRAGMENT_SOURCE")) continue;
                rejected.put(program, PackCompileTest.rejectionReasons(analysis, program));
            }
            // shadowcomp and the other advanced computes are planned apart from the program list.
            var advanced = analysis.plan() == null ? null : analysis.plan().advancedResources();
            if (advanced != null) {
                advanced.computeStages().forEach((name, spec) -> {
                    if (!spec.supported()) rejected.put(name, spec.deviations());
                });
            }
            SortedSet<String> failures = new TreeSet<>();
            for (String failure : PackCompileTest.run(pack, loaded.shadersDir(), analysis).failures()) {
                // Drop the dump path: it names the build folder, not the defect.
                failures.add(failure.replaceAll(" \\[[^\\]]*]$", ""));
            }
            // A variant whose value never reached the sources would sweep nothing; say so. A
            // profile's entries at their defaults are no change, and Iris ignores an entry that
            // names no declared option (listed apart: undeclaredProfileEntries).
            ShaderPackOptions staged = loaded.options().pack();
            Map<String, String> changed = staged.values().changes();
            boolean applied = values.entrySet().stream()
                    .filter(entry -> defaultText(staged, entry.getKey()) != null
                            && !entry.getValue().equals(defaultText(staged, entry.getKey())))
                    .allMatch(entry -> changed.containsKey(entry.getKey()));
            return new Outcome(rejected, failures, null, applied);
        } catch (RuntimeException | AssertionError failure) {
            return new Outcome(new TreeMap<>(), new TreeSet<>(), failure.toString(), true);
        }
    }

    private static String defaultText(ShaderPackOptions options, String name) {
        var toggle = options.options().toggles().get(name);
        if (toggle != null) return Boolean.toString(toggle.option().defaultValue());
        var choice = options.options().choices().get(name);
        return choice == null ? null : choice.option().defaultValue();
    }

    private static Change compare(Variant variant, Outcome outcome, Outcome defaults) {
        SortedMap<String, List<String>> newlyRejected = new TreeMap<>(outcome.rejected());
        newlyRejected.keySet().removeAll(defaults.rejected().keySet());
        SortedSet<String> newFailures = new TreeSet<>(outcome.failures());
        newFailures.removeAll(defaults.failures());
        return new Change(variant.label(), newlyRejected, newFailures, outcome.error(), outcome.applied());
    }

    private static String markdown(String pack, int variants, Outcome defaults, Set<String> undeclared,
                                   List<Change> changes) {
        StringBuilder text = new StringBuilder("\n## ").append(pack).append("\n\n")
                .append(variants).append(" variants; ").append(changes.size()).append(" change what runs. ")
                .append("Defaults reject ").append(defaults.rejected().keySet()).append(" and fail ")
                .append(defaults.failures().size()).append(" compiles.\n\n");
        if (!undeclared.isEmpty()) {
            text.append("Profiles name options no source declares, which Iris ignores too: ")
                    .append(undeclared).append("\n\n");
        }
        for (Change change : changes) {
            text.append("- `").append(change.variant()).append("`");
            change.newlyRejected().forEach((program, reasons) ->
                    text.append("\n  - rejects ").append(program).append(": ").append(reasons));
            change.newFailures().forEach(failure -> text.append("\n  - fails ").append(failure));
            if (change.error() != null) text.append("\n  - error: ").append(change.error());
            if (!change.applied()) text.append("\n  - the value did not reach the pack");
            text.append('\n');
        }
        return text.toString();
    }
}

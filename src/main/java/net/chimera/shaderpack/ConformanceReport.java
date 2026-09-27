package net.chimera.shaderpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Stable, runtime-aware description of the shader-pack boundary.
 *
 * <p>The report deliberately keeps static support and runtime disposition
 * separate. A source can fit the current contract and still become an
 * identity fallback when conversion or pipeline creation fails.</p>
 */
public final class ConformanceReport {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    public enum SupportStatus {
        SUPPORTED,
        SUPPORTED_WITH_DEVIATION,
        IDENTITY_FALLBACK,
        UNSUPPORTED
    }

    public enum RuntimeDisposition {
        NOT_ATTEMPTED,
        INSTALLED,
        IDENTITY_FALLBACK
    }

    public record ProgramReport(
            String name,
            String family,
            String dialect,
            List<String> stages,
            Map<String, String> sourceHashes,
            List<String> samplers,
            List<String> uniforms,
            List<Integer> targets,
            SupportStatus support,
            RuntimeDisposition runtime,
            List<String> deviations
    ) {
        public ProgramReport {
            name = Objects.requireNonNull(name);
            family = Objects.requireNonNull(family);
            dialect = Objects.requireNonNull(dialect);
            stages = sortedStrings(stages);
            sourceHashes = Map.copyOf(new TreeMap<>(sourceHashes));
            samplers = sortedStrings(samplers);
            uniforms = sortedStrings(uniforms);
            targets = targets.stream().distinct().toList();
            support = Objects.requireNonNull(support);
            runtime = Objects.requireNonNull(runtime);
            deviations = sortedStrings(deviations);
        }
    }

    private final String packName;
    private final String selectedDimension;
    private final String selectedVariantFolder;
    private final boolean passListPresent;
    private final List<String> passInventory;
    private final Map<String, String> metadataHashes;
    private final List<String> settings;
    private final TreeSet<String> deviations;
    private final TreeMap<String, ProgramReport> programs = new TreeMap<>();

    public ConformanceReport(
            String packName,
            boolean passListPresent,
            Collection<String> passInventory,
            Map<String, String> metadataHashes,
            Collection<String> settings,
            Collection<String> deviations
    ) {
        this(packName, passListPresent, passInventory, metadataHashes, settings, deviations, null, null);
    }

    public ConformanceReport(
            String packName,
            boolean passListPresent,
            Collection<String> passInventory,
            Map<String, String> metadataHashes,
            Collection<String> settings,
            Collection<String> deviations,
            String selectedDimension,
            String selectedVariantFolder
    ) {
        this.packName = Objects.requireNonNull(packName);
        this.selectedDimension = selectedDimension;
        this.selectedVariantFolder = selectedVariantFolder;
        this.passListPresent = passListPresent;
        this.passInventory = sortedStrings(passInventory);
        this.metadataHashes = new TreeMap<>(metadataHashes);
        this.settings = sortedStrings(settings);
        this.deviations = new TreeSet<>(deviations);
    }

    public void addProgram(ProgramReport program) {
        programs.put(program.name(), program);
    }

    public ProgramReport program(String name) {
        return programs.get(name);
    }

    public List<ProgramReport> programs() {
        return List.copyOf(programs.values());
    }

    public List<String> deviations() {
        return List.copyOf(deviations);
    }

    public List<String> passInventory() {
        return passInventory;
    }

    public void addDeviation(String deviation) {
        if (deviation != null && !deviation.isBlank()) {
            deviations.add(deviation);
        }
    }

    /**
     * Returns true only for programs the runtime is allowed to build onto the
     * current fixed-vertex pipelines.
     */
    public boolean shouldAttempt(String name) {
        ProgramReport program = programs.get(name);
        return program != null
                && (program.support() == SupportStatus.SUPPORTED
                || program.support() == SupportStatus.SUPPORTED_WITH_DEVIATION)
                && program.runtime() == RuntimeDisposition.NOT_ATTEMPTED;
    }

    public void markRuntime(String name, RuntimeDisposition runtime, String deviation) {
        ProgramReport program = programs.get(name);
        if (program == null) {
            deviations.add("RUNTIME_PROGRAM_NOT_IN_PROBE:" + name);
            return;
        }
        List<String> programDeviations = new ArrayList<>(program.deviations());
        if (deviation != null && !deviation.isBlank()) {
            programDeviations.add(deviation);
            programDeviations = sortedStrings(programDeviations);
        }
        programs.put(name, new ProgramReport(
                program.name(),
                program.family(),
                program.dialect(),
                program.stages(),
                program.sourceHashes(),
                program.samplers(),
                program.uniforms(),
                program.targets(),
                program.support(),
                runtime,
                programDeviations
        ));
    }

    public void markUnattemptedAsFallback() {
        for (ProgramReport program : List.copyOf(programs.values())) {
            if (program.runtime() == RuntimeDisposition.NOT_ATTEMPTED) {
                markRuntime(program.name(), RuntimeDisposition.IDENTITY_FALLBACK,
                        "NOT_RUNTIME_INSTALLED");
            }
        }
    }

    /** Concise startup summary that does not change the stable JSON schema. */
    public String runtimeSummary() {
        int installed = 0;
        int fallback = 0;
        for (ProgramReport program : programs.values()) {
            if (program.runtime() == RuntimeDisposition.INSTALLED) {
                installed++;
            } else if (program.runtime() == RuntimeDisposition.IDENTITY_FALLBACK) {
                fallback++;
            }
        }
        return "installed=" + installed + ", fallback=" + fallback;
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 1);
        root.addProperty("pack", packName);
        if (selectedDimension != null && selectedVariantFolder != null
                && !selectedVariantFolder.isBlank()) {
            root.addProperty("selectedDimension", selectedDimension);
            root.addProperty("selectedVariantFolder", selectedVariantFolder);
        }
        root.addProperty("passListPresent", passListPresent);
        root.add("passInventory", strings(passInventory));
        root.add("metadataHashes", stringMap(metadataHashes));
        root.add("settings", strings(settings));
        root.add("deviations", strings(deviations));

        JsonArray programArray = new JsonArray();
        for (ProgramReport program : programs.values()) {
            JsonObject object = new JsonObject();
            object.addProperty("name", program.name());
            object.addProperty("family", program.family());
            object.addProperty("dialect", program.dialect());
            object.add("stages", strings(program.stages()));
            object.add("sourceHashes", stringMap(program.sourceHashes()));
            object.add("samplers", strings(program.samplers()));
            object.add("uniforms", strings(program.uniforms()));
            JsonArray targets = new JsonArray();
            for (Integer target : program.targets()) {
                targets.add(target);
            }
            object.add("targets", targets);
            object.addProperty("support", program.support().name());
            object.addProperty("runtime", program.runtime().name());
            object.add("deviations", strings(program.deviations()));
            programArray.add(object);
        }
        root.add("programs", programArray);
        return JSON.toJson(root);
    }

    public String sha256() {
        return sha256(toJson().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hash of a pack text file with CRLF folded to LF. A pack's line endings
     * depend on how it was saved or checked out, not on what it says, so the
     * same pack must identify the same way either way.
     */
    public static String textSha256(byte[] bytes) {
        int kept = 0;
        byte[] folded = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') continue;
            folded[kept++] = bytes[i];
        }
        return sha256(java.util.Arrays.copyOf(folded, kept));
    }

    public static String textSha256(String text) {
        return textSha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static List<String> sortedStrings(Collection<String> values) {
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                sorted.add(value);
            }
        }
        return List.copyOf(sorted);
    }

    private static JsonArray strings(Collection<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonObject stringMap(Map<String, String> values) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            object.addProperty(entry.getKey(), entry.getValue());
        }
        return object;
    }
}

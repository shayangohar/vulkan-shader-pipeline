package net.chimera.shaderpack;

import net.chimera.shaderpack.PackSettingsPlan.Option;
import net.chimera.shaderpack.PackSettingsPlan.Profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.chimera.shaderpack.PackSettingsPlan.COMMENTED_DEFINE;
import static net.chimera.shaderpack.PackSettingsPlan.DEFINE;
import static net.chimera.shaderpack.PackSettingsPlan.PASS_TEXTURE_STAGES;
import static net.chimera.shaderpack.PackSettingsPlan.isDimensionContextMacro;
import static net.chimera.shaderpack.PackSettingsPlan.isStageContextMacro;
import static net.chimera.shaderpack.PackSettingsPlan.stageIndependentDefaults;
import static net.chimera.shaderpack.PackSettingsPlan.valuesFrom;

/**
 * shaders.properties as Iris 1.21.1's ShaderProperties reads it: lines joined across
 * continuations, conditional blocks evaluated with the option macros, and each entry (an option
 * define, a profile, the features, a custom value, a program toggle, a resource declaration) read
 * into the settings plan's maps (PackSettingsPlan). Other properties files go through
 * PropertiesPreprocessor.
 */
final class ShadersPropertiesReader {
    private static final Pattern PROFILE = Pattern.compile(
            "^profile\\.([A-Za-z_]\\w*)\\s*=\\s*(.*)$");
    private static final String PROGRAM_PREFIX = "program.";
    private static final Pattern FEATURES = Pattern.compile(
            "^iris\\.features\\.(required|optional)\\s*=\\s*(.*)$");
    private static final Pattern CUSTOM_VALUE = Pattern.compile(
            "^\\s*(uniform|variable)\\.(float|int|bool|vec2|vec3|vec4)\\.([A-Za-z_]\\w*)\\s*=\\s*(.*)$");
    private static final Pattern CONDITION = Pattern.compile(
            "^\\s*#(if|ifdef|ifndef|elif|else|endif)\\b(.*)$");

    private ShadersPropertiesReader() {}

    /** Parses shaders.properties into the given maps and returns the macros in force at its end. */
    static Map<String, String> read(
            Path propertyFile,
            Map<String, Option> options,
            Map<String, String> defaults,
            Map<String, Profile> profiles,
            Map<String, String> values,
            Map<String, Boolean> programEnabled,
            Set<String> required,
            Set<String> optional,
            List<PackRuntimeSettings.Declaration> customValues,
            Map<String, PackResourceDeclaration> resourceDeclarations,
            List<String> deviations,
            Map<String, String> initialMacros,
            Set<String> toggleOptions
    ) {
        PropertiesParse parse = new PropertiesParse(propertyFile, options, defaults, profiles, values,
                programEnabled, required, optional, customValues, resourceDeclarations, deviations, toggleOptions);
        // Iris's macro set is taken whole: SHADOW or DH_TERRAIN may be the pack's own options.
        parse.run(initialMacros == null ? stageIndependentDefaults(defaults) : initialMacros);
        return parse.conditions.macros();
    }

    /** One pass over shaders.properties: its conditional blocks, defines and key=value entries. */
    private static final class PropertiesParse {
        private final Path propertyFile;
        private final Map<String, Option> options;
        private final Map<String, String> defaults;
        private final Map<String, Profile> profiles;
        private final Map<String, String> values;
        private final Map<String, Boolean> programEnabled;
        private final Set<String> required;
        private final Set<String> optional;
        private final List<PackRuntimeSettings.Declaration> customValues;
        private final Map<String, PackResourceDeclaration> resourceDeclarations;
        private final List<String> deviations;
        private final Set<String> toggleOptions;
        private PackConditionals.State conditions;
        // An #ifdef with no name takes the next line with text, as JCPP reads it.
        private boolean takeNextLine;

        PropertiesParse(Path propertyFile, Map<String, Option> options, Map<String, String> defaults,
                        Map<String, Profile> profiles, Map<String, String> values,
                        Map<String, Boolean> programEnabled, Set<String> required, Set<String> optional,
                        List<PackRuntimeSettings.Declaration> customValues,
                        Map<String, PackResourceDeclaration> resourceDeclarations, List<String> deviations,
                        Set<String> toggleOptions) {
            this.propertyFile = propertyFile;
            this.options = options;
            this.defaults = defaults;
            this.profiles = profiles;
            this.values = values;
            this.programEnabled = programEnabled;
            this.required = required;
            this.optional = optional;
            this.customValues = customValues;
            this.resourceDeclarations = resourceDeclarations;
            this.deviations = deviations;
            this.toggleOptions = toggleOptions;
        }

        void run(Map<String, String> initialMacros) {
            conditions = new PackConditionals.State(initialMacros);
            try {
                // Iris reads shaders.properties as ISO-8859-1, which never fails on a stray byte, and
                // JCPP carries a block comment across lines (PropertiesPreprocessor.commentLines).
                List<PropertiesPreprocessor.Line> lines = PropertiesPreprocessor.commentLines(
                        Files.readAllLines(propertyFile, StandardCharsets.ISO_8859_1));
                PropertyLineJoiner joiner = new PropertyLineJoiner();
                for (int index = 0; index <= lines.size(); index++) {
                    PropertiesPreprocessor.Line next = index < lines.size() ? lines.get(index) : null;
                    String raw = next == null ? null : next.text();
                    String line;
                    if (raw != null && PropertyLineJoiner.endsContinuation(raw) && joiner.pending()) {
                        // The # line ends the open continuation: handle that line first,
                        // then revisit the # line.
                        line = joiner.finish();
                        index--;
                    } else if (raw == null) {
                        line = joiner.finish();
                    } else if (skips(next)) {
                        continue;
                    } else {
                        line = PropertyLineJoiner.endsContinuation(raw) ? raw : joiner.accept(raw);
                    }
                    if (line != null) {
                        accept(line);
                    }
                }
                try {
                    conditions.finish();
                } catch (RuntimeException failure) {
                    deviations.add("PREPROCESSOR_CONDITION_UNSUPPORTED");
                }
            } catch (IOException e) {
                deviations.add("SHADERS_PROPERTIES_READ_FAILED");
            }
        }

        /**
         * Whether a line adds nothing to the settings: a {@code #} line inside a block comment
         * (comment text to JCPP, then a properties comment), a conditional, which it applies, a line
         * of an inactive branch, or the line an #ifdef with no name takes.
         */
        private boolean skips(PropertiesPreprocessor.Line next) {
            if (takeNextLine) {
                // The first line Iris's line pass leaves text on is taken; the emptied ones go anyway.
                takeNextLine = PropertiesPreprocessor.emptiedByLinePass(next.text());
                return true;
            }
            if (next.inComment() && PropertyLineJoiner.endsContinuation(next.text())) return true;
            return applyCondition(next.text()) || !conditions.active();
        }

        /**
         * Applies an #if, #ifdef, #else or #endif line as JCPP does, reading on past one Chimera
         * cannot evaluate (PropertiesPreprocessor.applyConditional); false when the line is not one.
         */
        private boolean applyCondition(String raw) {
            Matcher directive = CONDITION.matcher(raw);
            if (!directive.matches()) {
                return false;
            }
            PropertiesPreprocessor.Conditional applied = PropertiesPreprocessor.applyConditional(
                    conditions, directive.group(1), directive.group(2).trim());
            if (applied != PropertiesPreprocessor.Conditional.APPLIED) {
                deviations.add("PREPROCESSOR_CONDITION_UNSUPPORTED");
            }
            takeNextLine = applied == PropertiesPreprocessor.Conditional.TAKES_NEXT_LINE;
            return true;
        }

        /** One logical line: a define, a commented-out define, a comment, or a key=value entry. */
        private void accept(String line) {
            if (acceptDefine(line) || line.trim().startsWith("#")) {
                return;
            }
            int equals = line.indexOf('=');
            if (equals < 1) {
                return;
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (key.isBlank()) {
                return;
            }
            // Iris preprocesses shaders.properties as C (PropertiesPreprocessor), so a resource's
            // value reads the macros defined so far: Alpha Piscium sizes its restir textures with
            // RESTIR_REUSE_TEX_WIDTH. Switches and option lists keep their names; Chimera
            // evaluates those itself.
            if (RESOURCE_PREFIXES.stream().anyMatch(key::startsWith)) {
                value = PackConditionals.expandMacros(value, conditions.macros()).trim();
            }
            values.put(key, value);
            acceptEntry(line, key, value);
        }

        /** A #define declares an option and its default; a commented-out one declares it off. */
        private boolean acceptDefine(String line) {
            Matcher define = DEFINE.matcher(line);
            if (define.matches()) {
                String name = define.group(1);
                if (isStageContextMacro(name) || isDimensionContextMacro(name)) {
                    return true;
                }
                boolean bare = define.group(2) == null || define.group(2).isBlank();
                String value = bare ? "1" : PropertiesPreprocessor.withoutComments(define.group(2)).trim();
                options.putIfAbsent(name, new Option(name, "define", value, valuesFrom(line),
                        propertyFile.getFileName().toString()));
                defaults.put(name, value);
                // Iris preprocesses the file with JCPP, where a bare #define is empty: #ifdef holds,
                // #if fails.
                conditions.define(name, bare ? "" : value);
                return true;
            }
            Matcher commentedDefine = COMMENTED_DEFINE.matcher(line);
            if (!commentedDefine.matches()) {
                return false;
            }
            String name = commentedDefine.group(1);
            options.putIfAbsent(name, new Option(name, "define", "0", valuesFrom(line),
                    propertyFile.getFileName().toString()));
            return true;
        }

        /** A key=value entry: a resource, profile, program switch, feature list, custom value, or unsupported. */
        private void acceptEntry(String line, String key, String value) {
            PackResourceDeclaration resource = resourceDeclaration(key, value);
            if (resource != null) {
                resourceDeclarations.put(resource.key(), resource);
                return;
            }
            Matcher profile = PROFILE.matcher(line);
            if (profile.matches()) {
                profiles.put(profile.group(1), new Profile(profile.group(1), value));
                return;
            }
            String program = switchedProgram(key);
            if (program != null) {
                acceptProgramSwitch(program, value.trim());
                return;
            }
            Matcher feature = FEATURES.matcher(line);
            if (feature.matches()) {
                Set<String> destination = feature.group(1).equals("required") ? required : optional;
                destination.clear();
                // As Iris: separated by spaces only, so "A,B" is one (unknown) flag.
                for (String token : feature.group(2).trim().split(" +")) {
                    if (!token.isBlank()) {
                        destination.add(token);
                    }
                }
                return;
            }
            Matcher custom = CUSTOM_VALUE.matcher(line);
            if (custom.matches()) {
                // Iris preprocesses shaders.properties with the option values as macros
                // (PropertiesPreprocessor): Reverie's betaRfact reads RAYLEIGH_DENSITY_VARIANCE's value.
                customValues.add(PackRuntimeSettings.declaration(
                        custom.group(1).equals("uniform"), custom.group(2),
                        custom.group(3), PackConditionals.expandMacros(custom.group(4), conditions.macros()).trim()));
                return;
            }
            String unread = unreadKey(key);
            if (unread != null) deviations.add(unread + ":" + key);
        }

        /**
         * Why a key no other reading took is recorded, or null for a property Chimera reads
         * elsewhere (PackPropertyKeys). image.* is AdvancedImageDeclarations', which names an image
         * it cannot serve.
         */
        private static String unreadKey(String key) {
            // Bounded bufferObject.N declarations are admitted. The source plan remains responsible
            // for proving that a matching std430 block and supported schedule exist.
            if (key.startsWith("bufferObject.")) return "STORAGE_BUFFER_DECLARED";
            if (key.startsWith("texture.")) return "TEXTURE_STAGE_UNKNOWN";
            return PackPropertyKeys.unread(key);
        }

        /**
         * The program a {@code program.<name>.<anything>} key switches: Iris takes the name up to the
         * first dot after {@code program.} and any suffix (Solas writes
         * {@code program.world0/deferred..enabled}). Null for any other key.
         */
        private static String switchedProgram(String key) {
            int end = key.indexOf('.', PROGRAM_PREFIX.length());
            return key.startsWith(PROGRAM_PREFIX) && end > PROGRAM_PREFIX.length()
                    ? key.substring(PROGRAM_PREFIX.length(), end) : null;
        }

        /** A {@code program.<name>.enabled} switch, evaluated against the pack's option values. */
        private void acceptProgramSwitch(String program, String expression) {
            try {
                // Iris evaluates the switch against the pack's option values: a toggle
                // the pack declares but leaves off (`//#define DOF`) is false. Only a
                // name that is no option at all keeps the program enabled.
                Map<String, String> switches = new HashMap<>(conditions.macros());
                for (String toggle : toggleOptions) {
                    switches.putIfAbsent(toggle, "0");
                }
                programEnabled.put(program, PackConditionals.evaluate(expression, switches, true));
            } catch (RuntimeException failure) {
                programEnabled.put(program, true);
                deviations.add("PROGRAM_ENABLE_EXPRESSION_UNSUPPORTED:" + program);
            }
        }
    }

    /**
     * Joins shaders.properties' backslash continuations the way Iris's PropertiesPreprocessor
     * and Properties loader do. Iris drops blank lines before preprocessing, so a blank line does
     * not end a continuation. Every {@code #} line becomes an empty line: a comment at once, a
     * directive once JCPP has read it. An empty line ends a continuation in Properties.load: Photon
     * closes a multi-line {@code moon_phase_brightness} with {@code ") \"} right before its
     * {@code #else}, and Bliss's {@code screen} list ends at the commented-out one under it. (The
     * id maps differ: IdMap rejoins across those lines, see {@link IdMapProperties}.) Callers
     * evaluate conditions on the lines JCPP sees (PropertiesPreprocessor.commentLines), end an
     * open continuation at each {@code #} line, and feed only active other lines to {@link #accept}.
     */
    static final class PropertyLineJoiner {
        private final StringBuilder pending = new StringBuilder();

        /** A {@code #} line, directive or comment, which ends an open continuation. */
        static boolean endsContinuation(String raw) {
            return raw.strip().startsWith("#");
        }

        boolean pending() {
            return pending.length() > 0;
        }

        /**
         * The completed logical line, or null while a continuation is open or the line is dropped.
         * Leading whitespace goes, as Properties.load skips it: Photon indents its
         * {@code program.<world>/composite17.enabled} switches inside {@code #ifdef}.
         */
        String accept(String raw) {
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) return null;
            if (trimmed.endsWith("\\")) {
                pending.append(trimmed, 0, trimmed.length() - 1).append(' ');
                return null;
            }
            if (pending.length() == 0) return trimmed;
            String line = pending + trimmed;
            pending.setLength(0);
            return line;
        }

        /** The open continuation as one logical line, at a directive line or the end of the file. */
        String finish() {
            if (pending.length() == 0) return null;
            String line = pending.toString();
            pending.setLength(0);
            return line;
        }
    }

    /**
     * The keys whose values name pack resources, read after macro expansion as Iris reads them.
     * image. values resolve their size tokens themselves (AdvancedImageDeclarations), keeping the
     * option each size came from.
     */
    private static final List<String> RESOURCE_PREFIXES = List.of(
            "customTexture.", "texture.", "bufferObject.", "indirect.");
    /** The stages a {@code texture.<stage>.<sampler>} may name (Iris TextureStage.parse). */
    static final Set<String> TEXTURE_STAGES = Stream.concat(PASS_TEXTURE_STAGES.stream(), Stream.of("gbuffers"))
            .collect(Collectors.toUnmodifiableSet());

    private static PackResourceDeclaration resourceDeclaration(String key, String value) {
        // Iris: a value with more than one word is a raw texture (file, type, format, size).
        PackResourceKind textureKind = value.trim().split("\\s+").length > 1
                ? PackResourceKind.RAW_TEXTURE : PackResourceKind.PACK_TEXTURE;
        if (key.startsWith("customTexture.")) {
            String sampler = key.substring("customTexture.".length()).trim();
            return sampler.isBlank() ? null
                    : new PackResourceDeclaration(key, "*", sampler, value, textureKind);
        }
        if (key.equals("texture.noise")) {
            return new PackResourceDeclaration(key, "*", "noisetex", value, PackResourceKind.NOISE);
        }
        if (key.startsWith("texture.")) {
            String[] parts = key.split("\\.", 3);
            // Iris ignores, with a warning, a stage it does not know: a program name, shadow, *.
            if (parts.length == 3 && TEXTURE_STAGES.contains(parts[1]) && !parts[2].isBlank()) {
                // Iris keeps only the sampler name: `texture.deferred.colortex6.1` declares a
                // second texture for colortex6, told apart from the first by its type.
                String sampler = parts[2].split("\\.")[0];
                return sampler.isBlank() ? null
                        : new PackResourceDeclaration(key, parts[1], sampler, value, textureKind);
            }
        }
        return null;
    }
}

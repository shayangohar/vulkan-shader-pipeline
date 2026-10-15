package net.chimera.shaderpack.options;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One pack source file with the option each line declares, read the way OptiFine and Iris read it.
 *
 * <p>Every line is judged on its own. A {@code #define NAME} (or a commented-out one) is a toggle
 * candidate; it only becomes an option when some file it shares an include group with tests it in a
 * plain {@code #ifdef NAME} or {@code #ifndef NAME} line, never in {@code #if}. A
 * {@code #define NAME value // ... [a b c]} is a choice. A {@code const} declaration counts only for
 * the names the format makes configurable, and an int or float one only with a value list.
 *
 * <p>Applying values edits these lines and nothing else: a toggle is commented in or out, a define
 * choice is rewritten, and a const keeps its declaration with the value after the equals sign
 * replaced.
 */
final class AnnotatedSource {
    private static final Set<String> CONST_OPTIONS = constOptionNames();

    private final List<String> lines;
    private final Map<Integer, ShaderOption> options;
    private final Set<String> testedNames;

    private AnnotatedSource(List<String> lines, Map<Integer, ShaderOption> options, Set<String> testedNames) {
        this.lines = lines;
        this.options = Collections.unmodifiableMap(options);
        this.testedNames = Collections.unmodifiableSet(testedNames);
    }

    /** Annotates source text split on any line break, as the pack loader reads it. */
    static AnnotatedSource parse(String source) {
        return parse(List.of(source.split("\\R")));
    }

    static AnnotatedSource parse(List<String> lines) {
        Map<Integer, ShaderOption> options = new TreeMap<>();
        Set<String> tested = new HashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.contains("#define") && !line.contains("const")
                    && !line.contains("#ifdef") && !line.contains("#ifndef")) {
                continue;
            }
            LineCursor cursor = new LineCursor(line.trim());
            int lineIndex = index;
            if (cursor.take("#ifdef") || cursor.take("#ifndef")) {
                testedName(cursor).ifPresent(tested::add);
            } else if (cursor.take("const")) {
                constOption(cursor).ifPresent(option -> options.put(lineIndex, option));
            } else if (cursor.contains("#define")) {
                defineOption(cursor).ifPresent(option -> options.put(lineIndex, option));
            }
        }
        return new AnnotatedSource(List.copyOf(lines), options, tested);
    }

    List<String> lines() {
        return lines;
    }

    /** The option each declaring line holds, by line index. */
    Map<Integer, ShaderOption> options() {
        return options;
    }

    /** Names this file tests with a plain {@code #ifdef} or {@code #ifndef} line. */
    Set<String> testedNames() {
        return testedNames;
    }

    /** The lines with {@code values} applied; the same list when nothing changes. */
    List<String> apply(OptionValues values) {
        String[] edited = null;
        for (Map.Entry<Integer, ShaderOption> entry : options.entrySet()) {
            String line = lines.get(entry.getKey());
            String next = edit(entry.getValue(), line, values);
            if (next.equals(line)) continue;
            if (edited == null) edited = lines.toArray(String[]::new);
            edited[entry.getKey()] = next;
        }
        return edited == null ? lines : List.of(edited);
    }

    private static String edit(ShaderOption option, String line, OptionValues values) {
        if (option instanceof ShaderOption.Toggle toggle) {
            // A toggle at its default keeps its line: every declaring line already holds the
            // default, since locations with different defaults never merge into one option.
            return values.toggle(toggle.name()).map(on -> toggle.kind() == ShaderOption.Kind.DEFINE
                    ? editToggleDefine(line, on)
                    : editConst(line, toggle.defaultText(), Boolean.toString(on))).orElse(line);
        }
        ShaderOption.Choice choice = (ShaderOption.Choice) option;
        return values.choice(choice.name()).map(value -> choice.kind() == ShaderOption.Kind.DEFINE
                ? "#define " + choice.name() + " " + value + " // changed by the shader pack options"
                : editConst(line, choice.defaultValue(), value)).orElse(line);
    }

    /**
     * An on toggle loses its leading comment; an off one gains one, even when it already had one,
     * as OptiFine writes it.
     */
    private static String editToggleDefine(String line, boolean on) {
        if (!on) return "//" + line;
        if (!line.trim().startsWith("//")) return line;
        LineCursor cursor = new LineCursor(line);
        cursor.takeWhitespace();
        cursor.takeComment();
        return cursor.remainder();
    }

    private static String editConst(String line, String current, String next) {
        int equals = line.indexOf('=');
        if (equals < 0) throw new IllegalStateException("const option line without '=': " + line);
        return line.substring(0, equals) + line.substring(equals)
                .replaceFirst(Pattern.quote(current), Matcher.quoteReplacement(next));
    }

    private static Optional<String> testedName(LineCursor cursor) {
        if (!cursor.takeWhitespace()) return Optional.empty();
        String name = cursor.takeWord();
        cursor.takeWhitespace();
        return name == null || !cursor.atEnd() ? Optional.empty() : Optional.of(name);
    }

    /** {@code const int|float|bool NAME = value; // comment}, for the configurable names. */
    private static Optional<ShaderOption> constOption(LineCursor cursor) {
        if (!cursor.takeWhitespace()) return Optional.empty();
        boolean toggle;
        if (cursor.take("int") || cursor.take("float")) {
            toggle = false;
        } else if (cursor.take("bool")) {
            toggle = true;
        } else {
            return Optional.empty();
        }
        if (!cursor.takeWhitespace()) return Optional.empty();
        String name = cursor.takeWord();
        if (name == null) return Optional.empty();
        cursor.takeWhitespace();
        if (!cursor.take("=")) return Optional.empty();
        cursor.takeWhitespace();
        String value = cursor.takeWordOrNumber();
        if (value == null) return Optional.empty();
        cursor.takeWhitespace();
        if (!cursor.take(";")) return Optional.empty();
        cursor.takeWhitespace();
        return constValue(cursor, name, value, toggle);
    }

    /** What follows a const's semicolon: nothing, or a comment holding the value list. */
    private static Optional<ShaderOption> constValue(LineCursor cursor, String name, String value, boolean toggle) {
        String comment = null;
        if (cursor.takeComment()) {
            comment = cursor.remainder().trim();
        } else if (!cursor.atEnd()) {
            return Optional.empty();
        }
        if (!CONST_OPTIONS.contains(name)) return Optional.empty();
        if (!toggle) {
            return ShaderOption.Choice.fromComment(ShaderOption.Kind.CONST, name, comment, value).map(c -> c);
        }
        if (!value.equals("true") && !value.equals("false")) return Optional.empty();
        return Optional.of(new ShaderOption.Toggle(name, ShaderOption.Kind.CONST,
                Optional.ofNullable(comment), value.equals("true")));
    }

    /** {@code [//]#define NAME [// comment]} or {@code #define NAME value // comment [values]}. */
    private static Optional<ShaderOption> defineOption(LineCursor cursor) {
        boolean commentedOut = cursor.takeComment();
        cursor.takeWhitespace();
        if (!cursor.take("#define") || !cursor.takeWhitespace()) return Optional.empty();
        String name = cursor.takeWord();
        if (name == null) return Optional.empty();
        boolean spaced = cursor.takeWhitespace();
        if (cursor.atEnd()) {
            return Optional.of(new ShaderOption.Toggle(name, ShaderOption.Kind.DEFINE, Optional.empty(),
                    !commentedOut));
        }
        if (cursor.takeComment()) {
            return Optional.of(new ShaderOption.Toggle(name, ShaderOption.Kind.DEFINE,
                    Optional.of(cursor.remainder().trim()), !commentedOut));
        }
        // Only a toggle may be commented out; a value needs whitespace before it.
        if (!spaced || commentedOut) return Optional.empty();
        String value = cursor.takeWordOrNumber();
        if (value == null) return Optional.empty();
        cursor.takeWhitespace();
        if (cursor.atEnd() || !cursor.takeComment()) return Optional.empty();
        return ShaderOption.Choice.fromComment(ShaderOption.Kind.DEFINE, name, cursor.remainder().trim(), value)
                .map(choice -> choice);
    }

    private static Set<String> constOptionNames() {
        Set<String> names = new HashSet<>(List.of("shadowMapResolution", "shadowDistance", "voxelDistance",
                "shadowDistanceRenderMul", "entityShadowDistanceMul", "shadowIntervalSize", "generateShadowMipmap",
                "generateShadowColorMipmap", "shadowHardwareFiltering", "shadowtex0Mipmap", "shadowtexMipmap",
                "shadowtex1Mipmap", "shadowtex0Nearest", "shadowtexNearest", "shadow0MinMagNearest",
                "shadowtex1Nearest", "shadow1MinMagNearest", "wetnessHalflife", "drynessHalflife",
                "eyeBrightnessHalflife", "centerDepthHalflife", "sunPathRotation", "ambientOcclusionLevel",
                "superSamplingLevel", "noiseTextureResolution"));
        for (int index = 0; index < 8; index++) {
            for (String prefix : List.of("shadowcolor", "shadowColor")) {
                names.add(prefix + index + "Mipmap");
                names.add(prefix + index + "Nearest");
                names.add(prefix + index + "MinMagNearest");
            }
            names.add("shadowHardwareFiltering" + index);
        }
        return Set.copyOf(names);
    }
}

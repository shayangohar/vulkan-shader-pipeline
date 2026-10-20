package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The pack's options as Iris preprocesses its id maps with them: block.properties and
 * item.properties see every option the pack's shader files declare, at its current value, whichever
 * file declares it. Bliss defines {@code FORCE_TRANSLUCENT_GLASS} in {@code lib/settings.glsl} and
 * gates its {@code layer.translucent} line on it.
 *
 * <p>The options are found as Iris finds them, across every file the program files include, the
 * {@code #include} lines followed whatever their conditions:</p>
 * <ul>
 *   <li>{@code #define NAME} is a switch that is on, {@code //#define NAME} one that is off; either
 *       may carry a comment. A switch counts only when a bare {@code #ifdef NAME} or
 *       {@code #ifndef NAME} line names it somewhere.</li>
 *   <li>{@code #define NAME value // [a b]} and {@code const int|float NAME = value; // [a b]} are
 *       value options: the allowed-values list is what makes them options.</li>
 * </ul>
 * The annotations are line conventions on the text, commented-out lines included, so this reads
 * lines, not GLSL tokens.
 */
public final class PackOptionMacros {
    private static final Pattern REFERENCE = Pattern.compile("^#(?:ifdef|ifndef)\\s+(\\w+)$");
    private static final Pattern SWITCH = Pattern.compile("^(//+\\s*)?#define\\s+(\\w+)(?:\\s*//.*)?$");
    private static final Pattern VALUE_DEFINE = Pattern.compile("^#define\\s+(\\w+)\\s+([\\w.+-]+)\\s*//(.*)$");
    private static final Pattern VALUE_CONST = Pattern.compile(
            "^const\\s+(?:int|float)\\s+(\\w+)\\s*=\\s*([\\w.+-]+)\\s*;\\s*//(.*)$");
    private static final Pattern BOOLEAN_CONST = Pattern.compile(
            "^const\\s+bool\\s+(\\w+)\\s*=\\s*(true|false)\\s*;.*$");
    private static final Pattern INCLUDE = Pattern.compile("^#include\\s+[\"<]([^\">]+)[\">]");
    private static final Set<String> STAGE_EXTENSIONS = Set.of("vsh", "fsh", "gsh", "csh", "tcs", "tes");
    private static final int MAX_FILES = 4096;

    private PackOptionMacros() {}

    /** What the files declare: each switch's default, each value option's default, and the referenced names. */
    private record Declared(Map<String, Boolean> switches, Map<String, String> values, Set<String> references) {
        Declared() {
            this(new LinkedHashMap<>(), new LinkedHashMap<>(), new HashSet<>());
        }
    }

    /**
     * The macros the id maps are preprocessed with: the options the shader files declare, at their
     * defaults, then the launcher's choices and shaders.properties' own defines ({@code settings}).
     * A launcher switch turned off ({@code overridden} without a value in {@code settings}) is
     * undefined.
     */
    public static Map<String, String> forIdMaps(Path shadersDir, Map<String, String> settings, Set<String> overridden) {
        Declared declared = declared(shadersDir);
        Map<String, String> macros = new TreeMap<>();
        declared.switches().forEach((name, on) -> {
            if (on && declared.references().contains(name)) macros.put(name, "1");
        });
        macros.putAll(declared.values());
        if (overridden != null) macros.keySet().removeAll(overridden);
        if (settings != null) macros.putAll(settings);
        return macros;
    }

    /** The options every program file and the files it includes declare; the first declaration wins. */
    private static Declared declared(Path shadersDir) {
        Declared declared = new Declared();
        if (shadersDir == null || !Files.isDirectory(shadersDir)) return declared;
        Path root = shadersDir.toAbsolutePath().normalize();
        Deque<Path> pending = new ArrayDeque<>(programFiles(root));
        Set<Path> seen = new HashSet<>(pending);
        while (!pending.isEmpty() && seen.size() <= MAX_FILES) {
            Path file = pending.removeFirst();
            for (String line : lines(file)) {
                Path include = scanLine(line.trim(), file, root, declared);
                if (include != null && seen.add(include)) pending.addLast(include);
            }
        }
        return declared;
    }

    /** The stage files in the shaders folder and its world folders, as Iris looks for programs. */
    private static List<Path> programFiles(Path root) {
        List<Path> files = new ArrayList<>();
        List<Path> folders = new ArrayList<>(List.of(root));
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("world"))
                    .sorted().forEach(folders::add);
        } catch (IOException ignored) {
            // The shaders folder alone still holds the overworld's programs.
        }
        for (Path folder : folders) {
            try (Stream<Path> children = Files.list(folder)) {
                children.filter(Files::isRegularFile).filter(PackOptionMacros::isStageFile).sorted()
                        .forEach(path -> files.add(path.toAbsolutePath().normalize()));
            } catch (IOException ignored) {
                // An unreadable folder declares nothing.
            }
        }
        return files;
    }

    private static boolean isStageFile(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && STAGE_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /** Records one line's option or reference; returns the file an {@code #include} line names, if any. */
    private static Path scanLine(String line, Path file, Path root, Declared declared) {
        Matcher matcher;
        if ((matcher = REFERENCE.matcher(line)).matches()) {
            declared.references().add(matcher.group(1));
        } else if ((matcher = SWITCH.matcher(line)).matches()) {
            declared.switches().putIfAbsent(matcher.group(2), matcher.group(1) == null);
        } else if ((matcher = VALUE_DEFINE.matcher(line)).matches()
                || (matcher = VALUE_CONST.matcher(line)).matches()) {
            if (allowedValues(matcher.group(3))) declared.values().putIfAbsent(matcher.group(1), matcher.group(2));
        } else if ((matcher = BOOLEAN_CONST.matcher(line)).matches()) {
            declared.switches().putIfAbsent(matcher.group(1), matcher.group(2).equals("true"));
        } else if ((matcher = INCLUDE.matcher(line)).lookingAt()) {
            return resolve(matcher.group(1), file, root);
        }
        return null;
    }

    /** Whether a trailing comment lists allowed values, {@code [a b c]}. */
    private static boolean allowedValues(String comment) {
        int open = comment.indexOf('[');
        return open >= 0 && comment.indexOf(']', open) > open;
    }

    /** An include's file: from the shaders folder for {@code /path}, else beside the including file. */
    private static Path resolve(String name, Path file, Path root) {
        Path include = name.startsWith("/") ? root.resolve(name.substring(1)) : file.getParent().resolve(name);
        include = include.toAbsolutePath().normalize();
        return include.startsWith(root) && Files.isRegularFile(include) ? include : null;
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException notUtf8) {
            try {
                return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
            } catch (IOException unreadable) {
                return List.of();
            }
        } catch (IOException unreadable) {
            return List.of();
        }
    }
}

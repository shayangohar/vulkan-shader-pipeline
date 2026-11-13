package net.chimera.shaderpack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where a compile error sits, for a failure notice's Copy Info (TASK-922). shaderc names the
 * translated stage Chimera compiled (pack_final.fsh:518), which no pack author has. The notice
 * quotes that line, and names the authored lines that read the same, when there are a few: RedHat's
 * redefined lenscolor reads the same at mainlens.glsl:18 and :79. Chimera's translation keeps no
 * line map, so a line it rewrote names no file.
 */
public final class CompileErrorLocation {
    /** shaderc's report of an error: {@code <unit>:<line>: error: <message>}. */
    private static final Pattern ERROR = Pattern.compile("(?m)^(\\S+?):(\\d+): error: (.*)$");
    private static final List<String> SOURCE_EXTENSIONS = List.of(".glsl", ".fsh", ".vsh", ".gsh", ".csh",
            ".tcs", ".tes", ".inc", ".h");
    /** A translated line shorter than this matches too many authored ones to name any. */
    private static final int SHORTEST_MATCHED_LINE = 12;
    /** Past this many authored lines reading the same, naming them says nothing. */
    static final int MOST_MATCHES = 3;
    private static final int MOST_FILES = 4000;

    private CompileErrorLocation() {}

    /**
     * The Copy Info lines for {@code compilerMessage}, the error shaderc gave for {@code source}:
     * the message, the translated line it names, and the authored files and lines under
     * {@code shadersDir} that read the same, when a few do.
     */
    public static String describe(String compilerMessage, String source, Path shadersDir) {
        String message = compilerMessage == null ? "" : compilerMessage.strip();
        StringBuilder text = new StringBuilder(message);
        Matcher error = ERROR.matcher(message);
        if (source == null || !error.find()) return text.toString();
        String line = line(source, Integer.parseInt(error.group(2)));
        if (line == null) return text.toString();
        text.append("\nTranslated line ").append(error.group(1)).append(':').append(error.group(2)).append(": ")
                .append(line.strip());
        String authored = authoredLine(line, shadersDir);
        if (authored != null) text.append("\nAuthored: ").append(authored);
        return text.toString();
    }

    /** Line {@code number}, counted from 1, of {@code source}; null past its end. */
    static String line(String source, int number) {
        if (number < 1) return null;
        return source.lines().skip(number - 1L).findFirst().orElse(null);
    }

    /**
     * The authored files and lines under {@code shadersDir} whose text is {@code translated}'s, as
     * {@code relative/path:line} joined by "or"; null when none or more than {@link #MOST_MATCHES}
     * are, or the line is too short to tell.
     */
    static String authoredLine(String translated, Path shadersDir) {
        String wanted = translated.strip();
        if (wanted.length() < SHORTEST_MATCHED_LINE || shadersDir == null || !Files.isDirectory(shadersDir)) {
            return null;
        }
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(shadersDir)) {
            for (Path file : files.filter(Files::isRegularFile).filter(CompileErrorLocation::isSource)
                    .limit(MOST_FILES).sorted().toList()) {
                List<String> lines = readLines(file);
                for (int index = 0; index < lines.size(); index++) {
                    if (!lines.get(index).strip().equals(wanted)) continue;
                    found.add(shadersDir.relativize(file).toString().replace('\\', '/') + ':' + (index + 1));
                    if (found.size() > MOST_MATCHES) return null;
                }
            }
        } catch (IOException | UncheckedIOException unreadable) {
            return null;
        }
        return found.isEmpty() ? null : String.join(" or ", found);
    }

    private static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        return SOURCE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        } catch (IOException unreadable) {
            return List.of();
        }
    }
}

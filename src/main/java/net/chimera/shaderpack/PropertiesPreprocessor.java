package net.chimera.shaderpack;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pack's properties file as Iris 1.21.1 reads one other than shaders.properties: its
 * PropertiesPreprocessor runs the text through JCPP ({@link #preprocess}), then
 * OrderBackedProperties loads it ({@link #load}). The id maps (IdMapProperties) and
 * dimension.properties (DimensionProperties) go through both.
 */
final class PropertiesPreprocessor {
    /** A {@code #} line JCPP reads as a directive: {@code #} and a name with nothing between. */
    private static final Pattern DIRECTIVE = Pattern.compile("^#([A-Za-z_]\\w*)(.*)$");
    /** A {@code #define} argument: the name, a {@code (} right after it for a function-like macro, the body. */
    private static final Pattern MACRO = Pattern.compile("^([A-Za-z_]\\w*)(\\()?\\s*(.*?)\\s*$");
    private static final Set<String> CONDITIONALS = Set.of("if", "ifdef", "ifndef", "elif", "else", "endif");
    /**
     * JCPP 1.4.14's directive names (PreprocessorCommand): PropertiesPreprocessor keeps a {@code #}
     * line that starts with one and empties any other, a properties comment.
     */
    private static final List<String> JCPP_DIRECTIVES = List.of("define", "elif", "else", "endif", "error",
            "if", "ifdef", "ifndef", "include", "line", "pragma", "undef", "warning", "include_next", "import");
    /** Stands in for a backslash while a line expands, as Iris hides backslashes from JCPP. */
    private static final String BACKSLASH = "IRIS_PASSTHROUGHBACKSLASH";

    private PropertiesPreprocessor() {}

    /**
     * The preprocessed text. {@code failed} when Chimera could not evaluate a conditional and read
     * it false ({@link #applyConditional}). {@code open} when a conditional is still open at the
     * end, which JCPP keeps.
     */
    record Preprocessed(String text, boolean failed, boolean open) {}

    /**
     * The file as PropertiesPreprocessor hands it on. Each line is trimmed and blank lines are
     * dropped; a {@code #} line that starts with a JCPP directive's name is kept and any other
     * {@code #} line is emptied, and every other line loses its {@code #} signs. JCPP then reads the
     * text as C, so a block comment runs across lines (ShaderSourcePreprocessor.logicalLines): a
     * directive inside one is comment text, one right after its close on a line is a directive
     * (ShaderSourcePreprocessor.directiveStart), and one a directive line leaves open takes the
     * lines up to its close into the directive, which goes on after it. An active branch's comment text is
     * kept as written (KEEPCOMMENTS) and the rest of its text expands the macros defined before it.
     * Directive lines and inactive branches are left empty.
     */
    static Preprocessed preprocess(String text, Map<String, String> macros) {
        PackConditionals.State conditions = new PackConditionals.State(macros);
        Map<String, String> expansions = new HashMap<>(macros == null ? Map.of() : macros);
        StringBuilder out = new StringBuilder(text.length());
        boolean failed = false;
        for (ShaderSourcePreprocessor.LogicalLine line : ShaderSourcePreprocessor.logicalLines(jcppInput(text))) {
            int start = ShaderSourcePreprocessor.directiveStart(line.text(), line.inComment());
            Matcher directive = start < 0 ? null : DIRECTIVE.matcher(line.text().substring(start));
            if (directive != null && directive.matches()) {
                // A comment that closes before the directive on its line stays as text (KEEPCOMMENTS).
                if (conditions.active()) out.append(line.text(), 0, start);
                failed |= !apply(directive.group(1), stripComments(directive.group(2)), conditions, expansions);
            } else if (conditions.active()) {
                out.append(PackConditionals.expandOutsideComments(line.text(), line.inComment(), expansions));
            }
            out.append('\n');
        }
        return new Preprocessed(out.toString().replace(BACKSLASH, "\\"), failed, conditions.open());
    }

    /**
     * Applies a conditional directive in a properties file as JCPP does, which reports an error
     * there and reads on (checked with JCPP 1.4.14): an #if, #ifdef, #ifndef or #elif Chimera cannot
     * evaluate, an empty one among them, reads false; a second #else, or an #elif after #else, is
     * ignored; a stray #else or #elif turns the rest of the file off; a stray #endif is ignored.
     * False when Chimera could not evaluate it: JCPP may read it otherwise, as {@code #if 1 +}
     * reads 1 and a character literal or a function-like macro has a value there.
     */
    static boolean applyConditional(PackConditionals.State conditions, String name, String argument) {
        String directive = name.toLowerCase(Locale.ROOT);
        boolean closing = directive.equals("else") || directive.equals("elif");
        if (closing && conditions.afterElse()) return true;
        try {
            if (!conditions.apply(directive, argument) && closing) conditions.endAll();
            return true;
        } catch (RuntimeException unsupported) {
            conditions.apply(directive.equals("elif") ? "elif" : "if", "0");
            return false;
        }
    }

    /**
     * The entries {@code text} loads into OrderBackedProperties: java.util.Properties' syntax, each
     * key where it first appears with its last value. Like Properties.load, it throws
     * IllegalArgumentException for a malformed unicode escape.
     */
    static Map<String, String> load(String text) throws IOException {
        OrderedProperties properties = new OrderedProperties();
        properties.load(new StringReader(text));
        return Collections.unmodifiableMap(properties.entries);
    }

    /**
     * One line of a properties file as JCPP lexes it, its own text kept: a physical line, or a
     * directive line with the lines its open block comment takes. {@code inComment} when it starts
     * inside a block comment, where a {@code #} line is comment text and no directive.
     */
    record Line(String text, boolean inComment) {}

    /**
     * {@code physical}'s lines as JCPP lexes them after PropertiesPreprocessor's line pass
     * ({@link #jcppLine}): a block comment runs across lines, and a directive line that leaves one
     * open takes the lines up to its close and goes on after it, joined with spaces.
     * shaders.properties is read this way; the id maps and dimension.properties through
     * {@link #preprocess}.
     */
    static List<Line> commentLines(List<String> physical) {
        List<Line> result = new ArrayList<>(physical.size());
        boolean inComment = false;
        StringBuilder directive = null;
        for (String raw : physical) {
            String seen = jcppLine(raw);
            if (directive != null) {
                directive.append(' ').append(raw.strip());
                if (ShaderSourcePreprocessor.inCommentAfter(seen, true)) continue;
                result.add(new Line(directive.toString(), false));
                directive = null;
            } else if (!inComment && seen.startsWith("#") && ShaderSourcePreprocessor.inCommentAfter(seen, false)) {
                directive = new StringBuilder(raw.strip());
            } else {
                result.add(new Line(raw, inComment));
                inComment = ShaderSourcePreprocessor.inCommentAfter(seen, inComment);
            }
        }
        if (directive != null) result.add(new Line(directive.toString(), false));
        return result;
    }

    /**
     * The text PropertiesPreprocessor gives JCPP, one line per non-blank line ({@link #jcppLine}),
     * each backslash hidden (so no line ends in one, and none splices).
     */
    private static String jcppInput(String text) {
        StringJoiner lines = new StringJoiner("\n");
        for (String raw : text.split("\\R")) {
            // Iris trims first, so a line of control characters is blank too.
            if (!raw.trim().isBlank()) lines.add(jcppLine(raw));
        }
        return lines.toString();
    }

    /**
     * What PropertiesPreprocessor gives JCPP for one line: trimmed; a {@code #} line kept when it
     * starts with a JCPP directive's name and emptied otherwise; any other line without its
     * {@code #} signs; each backslash hidden behind an identifier.
     */
    private static String jcppLine(String raw) {
        String line = raw.trim();
        String kept;
        if (!line.startsWith("#")) {
            kept = line.replace("#", "");
        } else {
            kept = JCPP_DIRECTIVES.stream().anyMatch(name -> line.startsWith("#" + name)) ? line : "";
        }
        return kept.replace("\\", BACKSLASH);
    }

    /**
     * One directive. Conditionals nest inside inactive branches; {@code #define} and {@code #undef}
     * apply only in an active one. JCPP reports {@code #error} and {@code #warning}, cannot include a
     * file into a properties text, and skips a name it does not know; none of those changes the map.
     * False when a conditional could not be evaluated ({@link #applyConditional}).
     */
    private static boolean apply(String name, String argument, PackConditionals.State conditions,
                                 Map<String, String> expansions) {
        if (CONDITIONALS.contains(name)) return applyConditional(conditions, name, argument);
        if (!conditions.active()) return true;
        if (name.equals("define")) {
            Matcher macro = MACRO.matcher(argument);
            // A function-like macro is not expanded here.
            if (!macro.matches() || macro.group(2) != null) return true;
            conditions.define(macro.group(1), macro.group(3));
            // A macro defined with no body expands to nothing.
            expansions.put(macro.group(1), macro.group(3));
        } else if (name.equals("undef") && !argument.isEmpty()) {
            String macro = argument.split("\\s+")[0];
            conditions.undefine(macro);
            expansions.remove(macro);
        }
        return true;
    }

    /**
     * A properties directive's argument without its C comments, as JCPP reads it with each
     * backslash hidden behind an identifier: in {@code "a\" // b"} the string closes at the
     * backslash's quote, so the value is {@code "a\"}. shaders.properties' #define reader takes it.
     */
    static String withoutComments(String argument) {
        return PackConditionals.withoutComments(argument.replace("\\", BACKSLASH)).replace(BACKSLASH, "\\");
    }

    /** A directive's argument without its C comments, which JCPP skips (PackConditionals.withoutComments). */
    private static String stripComments(String argument) {
        return PackConditionals.withoutComments(argument == null ? "" : argument).trim();
    }

    /** java.util.Properties that also keeps each key where it first appeared, as OrderBackedProperties does. */
    private static final class OrderedProperties extends Properties {
        private final transient Map<String, String> entries = new LinkedHashMap<>();

        @Override
        public synchronized Object put(Object key, Object value) {
            entries.put((String) key, (String) value);
            return super.put(key, value);
        }
    }
}

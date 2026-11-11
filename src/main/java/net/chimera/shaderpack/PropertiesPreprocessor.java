package net.chimera.shaderpack;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
     * The preprocessed text. {@code failed} when Chimera cannot evaluate a directive (a function-like
     * macro or a character literal in an #if, an #elif or second #else after #else): the text then
     * ends before it. JCPP reports such an error and reads on, an #if it cannot evaluate taking 0,
     * so Iris keeps the lines after it; no pack the gate reads has one. {@code open} when a
     * conditional is still open at the end, which JCPP keeps.
     */
    record Preprocessed(String text, boolean failed, boolean open) {}

    /**
     * The file as PropertiesPreprocessor hands it on. Each line is trimmed and blank lines are
     * dropped; a {@code #} line that starts with a JCPP directive's name is kept and any other
     * {@code #} line is emptied, and every other line loses its {@code #} signs. JCPP then reads the
     * text as C, so a block comment runs across lines (ShaderSourcePreprocessor.logicalLines): a
     * directive inside one is comment text, and one a directive line leaves open takes the lines up
     * to its close into the directive, which goes on after it. An active branch's comment text is
     * kept as written (KEEPCOMMENTS) and the rest of its text expands the macros defined before it.
     * Directive lines and inactive branches are left empty.
     */
    static Preprocessed preprocess(String text, Map<String, String> macros) {
        PackConditionals.State conditions = new PackConditionals.State(macros);
        Map<String, String> expansions = new HashMap<>(macros == null ? Map.of() : macros);
        StringBuilder out = new StringBuilder(text.length());
        for (ShaderSourcePreprocessor.LogicalLine line : ShaderSourcePreprocessor.logicalLines(jcppInput(text))) {
            Matcher directive = line.inComment() ? null : DIRECTIVE.matcher(line.text());
            if (directive != null && directive.matches()) {
                try {
                    apply(directive.group(1), stripComments(directive.group(2)), conditions, expansions);
                } catch (RuntimeException unsupported) {
                    return new Preprocessed(out.toString().replace(BACKSLASH, "\\"), true, conditions.open());
                }
            } else if (conditions.active()) {
                out.append(PackConditionals.expandOutsideComments(line.text(), line.inComment(), expansions));
            }
            out.append('\n');
        }
        return new Preprocessed(out.toString().replace(BACKSLASH, "\\"), false, conditions.open());
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
     * The text PropertiesPreprocessor gives JCPP, one line per non-blank line, each backslash hidden
     * (so no line ends in one, and none splices).
     */
    private static String jcppInput(String text) {
        StringJoiner lines = new StringJoiner("\n");
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isBlank()) continue;
            String kept;
            if (!line.startsWith("#")) {
                kept = line.replace("#", "");
            } else {
                kept = JCPP_DIRECTIVES.stream().anyMatch(name -> line.startsWith("#" + name)) ? line : "";
            }
            lines.add(kept.replace("\\", BACKSLASH));
        }
        return lines.toString();
    }

    /**
     * One directive. Conditionals nest inside inactive branches; {@code #define} and {@code #undef}
     * apply only in an active one. JCPP reports {@code #error} and {@code #warning}, cannot include a
     * file into a properties text, and skips a name it does not know; none of those changes the map.
     */
    private static void apply(String name, String argument, PackConditionals.State conditions,
                              Map<String, String> expansions) {
        if (CONDITIONALS.contains(name)) {
            conditions.apply(name, argument);
            return;
        }
        if (!conditions.active()) return;
        if (name.equals("define")) {
            Matcher macro = MACRO.matcher(argument);
            // A function-like macro is not expanded here.
            if (!macro.matches() || macro.group(2) != null) return;
            conditions.define(macro.group(1), macro.group(3));
            // A macro defined with no body expands to nothing.
            expansions.put(macro.group(1), macro.group(3));
        } else if (name.equals("undef") && !argument.isEmpty()) {
            String macro = argument.split("\\s+")[0];
            conditions.undefine(macro);
            expansions.remove(macro);
        }
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

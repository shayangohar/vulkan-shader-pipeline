package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands the macros packs use to name samplers and storage qualifiers, while each is defined. */
final class GlslMacroAliases {
    private GlslMacroAliases() {}

    static final Pattern ALIAS_DEFINE = Pattern.compile(
            "^\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*(?://.*)?$");

    /** Group 1 is the directive through the macro name; group 2 is the name. */
    static final Pattern MACRO_DEFINE = Pattern.compile(
            "(\\s*#\\s*define\\s+([A-Za-z_]\\w*))");

    static final Pattern UNDEF = Pattern.compile(
            "^\\s*#\\s*undef\\s+([A-Za-z_]\\w*)\\s*$");

    static final Pattern SAMPLER_DECLARATION = Pattern.compile(
            "\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?[iu]?sampler\\w*\\s+([A-Za-z_]\\w*)");

    /** {@code #define attribute in} or {@code #define in_out out}: a macro standing for a storage qualifier. */
    static final Pattern STORAGE_ALIAS_DEFINE = Pattern.compile(
            "^\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s+(in|out|attribute|varying)\\s*(?://.*)?$");

    /**
     * Applies macros that stand for a storage qualifier and drops their definitions, so every
     * converter reads a declaration in the spelling it was compiled with. Photon defines
     * {@code attribute} as {@code in} for strict drivers; I Like Vanilla declares its varyings
     * with {@code in_out}. Each alias holds from its definition to
     * its #undef; the removed lines stay empty, so line numbers do not move.
     */
    static String expandStorageQualifierAliases(String source) {
        if (source == null || !source.contains("#")) return source;
        // Directives are read line by line; substitution runs over one token
        // stream, so a block comment spanning lines is never lexed in pieces.
        String[] lines = source.split("\n", -1);
        List<Map<String, String>> active = new ArrayList<>(lines.length);
        boolean[] drop = new boolean[lines.length];
        boolean[] directive = new boolean[lines.length];
        Map<String, String> aliases = new HashMap<>();
        boolean changed = false;
        for (int line = 0; line < lines.length; line++) {
            String body = withoutCarriageReturn(lines[line]);
            Matcher define = STORAGE_ALIAS_DEFINE.matcher(body);
            Matcher undef = UNDEF.matcher(body);
            if (define.matches()) {
                aliases.put(define.group(1), define.group(2));
                drop[line] = true;
                changed = true;
            } else if (undef.matches() && aliases.remove(undef.group(1)) != null) {
                drop[line] = true;
            } else {
                directive[line] = body.stripLeading().startsWith("#");
            }
            active.add(Map.copyOf(aliases));
        }
        if (!changed) return source;
        return applyStorageAliases(source, active, drop, directive);
    }

    /** The source with each line's live aliases applied outside directives and the dropped lines blanked. */
    private static String applyStorageAliases(String source, List<Map<String, String>> active, boolean[] drop,
                                              boolean[] directive) {
        StringBuilder result = new StringBuilder(source.length());
        int line = 0;
        for (GlslLexer.Token token : GlslLexer.lex(source)) {
            String text = token.text();
            if (drop[line]) {
                result.append(GlslTokenRewriter.lineBreaks(text));
            } else if (!directive[line] && token.kind() == GlslLexer.Kind.IDENTIFIER
                    && active.get(line).containsKey(text)) {
                result.append(active.get(line).get(text));
            } else {
                result.append(text);
            }
            line += newlines(text);
        }
        return result.toString();
    }

    private static String withoutCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static int newlines(String text) {
        int count = 0;
        for (int at = 0; at < text.length(); at++) if (text.charAt(at) == '\n') count++;
        return count;
    }

    /**
     * Expands object-like macros that name a declared sampler
     * ({@code #define SRC_SAMPLER colortex0}), as Iris's full preprocessing
     * does before any analysis, so sampler use is visible to planning. Only
     * those aliases are touched, in source order: a later {@code #undef} or
     * redefinition ends the alias, and every other macro stays as written.
     */
    static String expandSamplerAliases(String source) {
        if (source == null || !source.contains("#")) return source;
        Set<String> samplers = new HashSet<>();
        Matcher declared = SAMPLER_DECLARATION.matcher(source);
        while (declared.find()) samplers.add(declared.group(1));
        if (samplers.isEmpty()) return source;
        // Directives are read line by line; substitution runs over one token
        // stream, so a block comment spanning lines is never lexed in pieces.
        String[] lines = source.split("\n", -1);
        List<Map<String, String>> active = new ArrayList<>(lines.length);
        boolean[] drop = new boolean[lines.length];
        boolean[] macro = new boolean[lines.length];
        Map<String, String> aliases = new HashMap<>();
        Map<String, String> live = Map.of();
        boolean changed = false;
        for (int line = 0; line < lines.length; line++) {
            String body = withoutCarriageReturn(lines[line]);
            // Only a directive can change the aliases; every other line shares the last snapshot.
            if (!ShaderSourcePreprocessor.startsDirective(body)) {
                active.add(live);
                continue;
            }
            Matcher define = ALIAS_DEFINE.matcher(body);
            Matcher undef = UNDEF.matcher(body);
            Matcher named = MACRO_DEFINE.matcher(body);
            if (define.matches()) {
                String target = aliases.getOrDefault(define.group(2), define.group(2));
                aliases.remove(define.group(1));
                // Either way round: an alias of a declared sampler, or a declaration through the
                // macro (Photon declares `uniform sampler2D DEBUG_SAMPLER` with DEBUG_SAMPLER
                // defined as colortex1), which the preprocessor expands before anything reads it.
                boolean samplerAlias = samplers.contains(target) || samplers.contains(define.group(1));
                if (samplerAlias && !define.group(1).equals(target)) {
                    aliases.put(define.group(1), target);
                    drop[line] = true;
                    changed = true;
                } else {
                    macro[line] = true;
                }
            } else if (undef.matches() && aliases.remove(undef.group(1)) != null) {
                drop[line] = true;
            } else if (named.lookingAt()) {
                // Any other definition of an alias name ends that alias; other
                // macro bodies may use an alias and expand after it is gone.
                aliases.remove(named.group(2));
                macro[line] = true;
            }
            live = Map.copyOf(aliases);
            active.add(live);
        }
        if (!changed) return source;
        return applySamplerAliases(source, active, drop, macro);
    }

    /**
     * The source with each line's live aliases applied and the dropped lines blanked. A member
     * access and the name a #define defines are never replaced.
     */
    private static String applySamplerAliases(String source, List<Map<String, String>> active, boolean[] drop,
                                              boolean[] macro) {
        StringBuilder result = new StringBuilder(source.length());
        int line = 0;
        int previous = -1;
        List<GlslLexer.Token> tokens = GlslLexer.lex(source);
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            String text = token.text();
            if (drop[line]) {
                result.append(GlslTokenRewriter.lineBreaks(text));
            } else if (token.kind() == GlslLexer.Kind.IDENTIFIER
                    && !(previous >= 0 && tokens.get(previous).symbol("."))
                    && !(macro[line] && previous >= 0 && tokens.get(previous).identifier("define"))
                    && active.get(line).containsKey(text)) {
                result.append(active.get(line).get(text));
            } else {
                result.append(text);
            }
            if (token.significant()) previous = index;
            line += newlines(text);
        }
        return result.toString();
    }
}

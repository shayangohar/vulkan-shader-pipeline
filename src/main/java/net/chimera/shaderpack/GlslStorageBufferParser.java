package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Token-based parser and binding rewrite for the bounded std430 bridge. */
final class GlslStorageBufferParser {
    private static final Set<String> MEMORY_QUALIFIERS = Set.of(
            "readonly", "writeonly", "coherent", "volatile", "restrict");

    record Block(
            String blockName,
            String instanceName,
            int originalBinding,
            boolean std430,
            String qualifierMacro
    ) {}

    private GlslStorageBufferParser() {}

    static List<Block> scan(String source) {
        String input = source == null ? "" : source;
        List<GlslLexer.Token> tokens = GlslLexer.lex(input);
        List<Block> result = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (!tokens.get(index).identifier("buffer")) continue;
            int blockName = GlslLexer.nextSignificant(tokens, index);
            if (blockName < 0 || tokens.get(blockName).kind() != GlslLexer.Kind.IDENTIFIER) continue;
            int open = GlslLexer.nextSignificant(tokens, blockName);
            if (open < 0 || !tokens.get(open).symbol("{")) continue;
            int close = GlslLexer.matching(tokens, open, "{", "}");
            if (close < 0) throw new IllegalArgumentException("unbalanced GLSL storage block");
            int instance = GlslLexer.nextSignificant(tokens, close);
            String instanceName = instance >= 0 && tokens.get(instance).kind() == GlslLexer.Kind.IDENTIFIER
                    ? tokens.get(instance).text() : "";

            // Macro state is evaluated at the declaration. A later #define or
            // #undef must not change the meaning of an earlier storage block.
            Map<String, String> macros = objectMacrosAt(tokens, index);
            int before = findLayoutClose(tokens, index, macros);
            String macro = "";
            boolean std430 = false;
            int binding = -1;
            if (before >= 0 && tokens.get(before).symbol(")")) {
                int layout = matchingOpen(tokens, before, "(", ")");
                int layoutName = layout < 0 ? -1 : GlslLexer.previousSignificant(tokens, layout);
                if (layout >= 0 && layoutName >= 0 && tokens.get(layoutName).identifier("layout")) {
                    List<Integer> layoutTokens = significantBetween(tokens, layout + 1, before);
                    boolean expectBinding = false;
                    for (int position : layoutTokens) {
                        GlslLexer.Token token = tokens.get(position);
                        if (token.identifier("std430")) std430 = true;
                        if (token.identifier("binding")) expectBinding = true;
                        else if (expectBinding && token.kind() == GlslLexer.Kind.NUMBER) {
                            binding = parseInt(token.text());
                            expectBinding = false;
                        } else if (!token.symbol("=")) {
                            expectBinding = token.symbol(",") ? false : expectBinding;
                        }
                    }
                    int qualifier = GlslLexer.nextSignificant(tokens, before);
                    while (qualifier >= 0 && qualifier < index
                            && !tokens.get(qualifier).identifier("buffer")) {
                        GlslLexer.Token value = tokens.get(qualifier);
                        if (value.kind() != GlslLexer.Kind.IDENTIFIER) {
                            qualifier = GlslLexer.nextSignificant(tokens, qualifier);
                            continue;
                        }
                        if (MEMORY_QUALIFIERS.contains(value.text())) {
                            qualifier = GlslLexer.nextSignificant(tokens, qualifier);
                            continue;
                        }
                        String expansion = macros.get(value.text());
                        if (expansion == null || !supportedMacroExpansion(expansion, macros,
                                new HashSet<>())) {
                            throw new IllegalArgumentException("unsupported storage qualifier: " + value.text());
                        }
                        macro = value.text();
                        qualifier = GlslLexer.nextSignificant(tokens, qualifier);
                    }
                }
            } else {
                // A block without layout is inventory-only. The caller will
                // report it as unsupported rather than inventing a binding.
                std430 = false;
            }
            result.add(new Block(tokens.get(blockName).text(), instanceName, binding, std430, macro));
        }
        return List.copyOf(result);
    }

    static boolean hasBlock(String source) {
        try {
            return !scan(source).isEmpty();
        } catch (RuntimeException failure) {
            return source != null && source.matches("(?s).*\\bbuffer\\s+[A-Za-z_]\\w*\\s*\\{");
        }
    }

    static String rewrite(String source, Map<String, Integer> bindings) {
        if (source == null || bindings == null || bindings.isEmpty()) return source;
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.lex(source));
        List<Block> blocks = scan(source);
        for (Block block : blocks) {
            Integer replacement = bindings.get(key(block));
            if (replacement == null) continue;
            for (int index = 0; index < tokens.size(); index++) {
                if (!tokens.get(index).identifier("buffer")) continue;
                int blockName = GlslLexer.nextSignificant(tokens, index);
                if (blockName < 0 || !tokens.get(blockName).text().equals(block.blockName())) continue;
                int open = GlslLexer.nextSignificant(tokens, blockName);
                if (open < 0 || !tokens.get(open).symbol("{")) continue;
                int before = findLayoutClose(tokens, index, objectMacrosAt(tokens, index));
                if (before < 0 || !tokens.get(before).symbol(")")) continue;
                int layout = matchingOpen(tokens, before, "(", ")");
                if (layout < 0) continue;
                int binding = -1;
                for (int cursor = layout + 1; cursor < before; cursor++) {
                    if (!tokens.get(cursor).identifier("binding")) continue;
                    int equals = GlslLexer.nextSignificant(tokens, cursor);
                    int number = equals < 0 ? -1 : GlslLexer.nextSignificant(tokens, equals);
                    if (number >= 0 && tokens.get(number).kind() == GlslLexer.Kind.NUMBER) {
                        binding = number;
                        break;
                    }
                }
                if (binding >= 0) {
                    tokens.set(binding, new GlslLexer.Token(GlslLexer.Kind.NUMBER,
                            Integer.toString(replacement)));
                }
                break;
            }
        }
        return GlslLexer.render(tokens);
    }

    static String key(Block block) {
        return block.blockName() + "\u0000" + block.instanceName();
    }

    private static Map<String, String> objectMacros(String source) {
        Map<String, String> result = new HashMap<>();
        for (String line : source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#define ")) {
                String rest = trimmed.substring(8).trim();
                int space = rest.indexOf(' ');
                String name = space < 0 ? rest : rest.substring(0, space);
                if (name.contains("(")) {
                    result.put(name, "<function>");
                } else {
                    result.put(name, space < 0 ? "" : rest.substring(space + 1).trim());
                }
            } else if (trimmed.startsWith("#undef ")) {
                result.remove(trimmed.substring(7).trim());
            }
        }
        return result;
    }

    private static Map<String, String> objectMacrosAt(List<GlslLexer.Token> tokens, int end) {
        int safeEnd = Math.max(0, Math.min(end, tokens.size()));
        return objectMacros(GlslLexer.render(tokens.subList(0, safeEnd)));
    }

    private static boolean supportedMacroExpansion(
            String expansion,
            Map<String, String> macros,
            Set<String> visiting
    ) {
        String value = expansion == null ? "" : expansion.trim();
        if (value.isEmpty()) return true;
        if (value.equals("<function>")) return false;
        for (String token : value.split("\\s+")) {
            if (MEMORY_QUALIFIERS.contains(token)) continue;
            String nested = macros.get(token);
            if (nested == null || !visiting.add(token)
                    || !supportedMacroExpansion(nested, macros, visiting)) return false;
            visiting.remove(token);
        }
        return true;
    }

    private static int matchingOpen(List<GlslLexer.Token> tokens, int close,
                                    String left, String right) {
        int depth = 0;
        for (int index = close; index >= 0; index--) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol(right)) depth++;
            else if (token.symbol(left) && --depth == 0) return index;
        }
        return -1;
    }

    private static int findLayoutClose(
            List<GlslLexer.Token> tokens,
            int buffer,
            Map<String, String> macros
    ) {
        int cursor = GlslLexer.previousSignificant(tokens, buffer);
        while (cursor >= 0) {
            GlslLexer.Token token = tokens.get(cursor);
            if (token.kind() == GlslLexer.Kind.IDENTIFIER
                    && (MEMORY_QUALIFIERS.contains(token.text()) || macros.containsKey(token.text()))) {
                cursor = GlslLexer.previousSignificant(tokens, cursor);
                continue;
            }
            return token.symbol(")") ? cursor : -1;
        }
        return -1;
    }

    private static List<Integer> significantBetween(List<GlslLexer.Token> tokens, int start, int end) {
        List<Integer> result = new ArrayList<>();
        for (int index = start; index < end; index++) {
            if (tokens.get(index).significant()) result.add(index);
        }
        return result;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            return -1;
        }
    }
}

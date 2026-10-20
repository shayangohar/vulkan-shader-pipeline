package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The storage blocks of a prepared source, read from the parse tree, and the binding rewrite for
 * the bounded std430 bridge. Prepared sources hold no macros (KNOW-725), so a block's qualifiers
 * are the ones its declaration spells.
 */
final class GlslStorageBufferParser {
    private static final Set<String> MEMORY_QUALIFIERS = Set.of(
            "readonly", "writeonly", "coherent", "volatile", "restrict");

    record Block(
            String blockName,
            String instanceName,
            int originalBinding,
            boolean std430
    ) {}

    /** One storage block's node and the layout qualifier directly before its memory qualifiers. */
    private record Declared(GlslTree.InterfaceBlock block, GlslTree.Qualifier layout) {}

    private GlslStorageBufferParser() {}

    static List<Block> scan(String source) {
        GlslTree tree = GlslTree.parse(source == null ? "" : source);
        List<Block> result = new ArrayList<>();
        for (Declared declared : storageBlocks(tree)) {
            GlslTree.InterfaceBlock block = declared.block();
            String instanceName = block.instances().isEmpty() ? "" : tree.text(block.instances().get(0).name());
            if (declared.layout() == null) {
                // A block without layout is inventory-only. The caller will
                // report it as unsupported rather than inventing a binding.
                result.add(new Block(tree.text(block.name()), instanceName, -1, false));
                continue;
            }
            Map<String, String> arguments = GlslDeclarations.layoutArguments(tree, declared.layout());
            String binding = arguments.get("binding");
            result.add(new Block(tree.text(block.name()), instanceName, binding == null ? -1 : parseInt(binding),
                    arguments.containsKey("std430")));
        }
        return List.copyOf(result);
    }

    /** The file-scope {@code buffer} blocks, each with its layout when one precedes its memory qualifiers. */
    private static List<Declared> storageBlocks(GlslTree tree) {
        List<Declared> blocks = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.InterfaceBlock block)) continue;
            List<GlslTree.Qualifier> qualifiers = block.qualifiers();
            int buffer = qualifiers.size() - 1;
            while (buffer >= 0 && !tree.text(qualifiers.get(buffer).keyword()).equals("buffer")) buffer--;
            if (buffer < 0) continue;
            int layout = buffer - 1;
            while (layout >= 0 && MEMORY_QUALIFIERS.contains(tree.text(qualifiers.get(layout).keyword()))) layout--;
            boolean laidOut = layout >= 0 && tree.text(qualifiers.get(layout).keyword()).equals("layout");
            blocks.add(new Declared(block, laidOut ? qualifiers.get(layout) : null));
        }
        return blocks;
    }

    /**
     * Whether the source declares a storage block. A source the tree cannot parse still reports
     * one spelled as {@code buffer}, a name and an opening brace, so the report names the program.
     */
    static boolean hasBlock(String source) {
        try {
            return !scan(source).isEmpty();
        } catch (RuntimeException failure) {
            List<GlslLexer.Token> tokens = GlslLexer.lex(source);
            for (int index = 0; index < tokens.size(); index++) {
                if (!tokens.get(index).identifier("buffer")) continue;
                int name = GlslLexer.nextSignificant(tokens, index);
                int open = name < 0 ? -1 : GlslLexer.nextSignificant(tokens, name);
                if (open >= 0 && tokens.get(name).kind() == GlslLexer.Kind.IDENTIFIER
                        && tokens.get(open).symbol("{")) {
                    return true;
                }
            }
            return false;
        }
    }

    /** {@code source} with each keyed block's layout binding number replaced. */
    static String rewrite(String source, Map<String, Integer> bindings) {
        if (source == null || bindings == null || bindings.isEmpty()) return source;
        GlslTree tree = GlslTree.parse(source);
        List<GlslTree.Edit> edits = new ArrayList<>();
        for (Declared declared : storageBlocks(tree)) {
            GlslTree.InterfaceBlock block = declared.block();
            String instanceName = block.instances().isEmpty() ? "" : tree.text(block.instances().get(0).name());
            Integer replacement = bindings.get(tree.text(block.name()) + "\u0000" + instanceName);
            int number = declared.layout() == null ? -1 : bindingNumber(tree, declared.layout().range());
            if (replacement != null && number >= 0) {
                edits.add(new GlslTree.Edit(new GlslTree.Range(number, number + 1), Integer.toString(replacement)));
            }
        }
        return edits.isEmpty() ? source : tree.print(edits);
    }

    /** The first {@code binding = N} number token in a layout qualifier, or -1. */
    private static int bindingNumber(GlslTree tree, GlslTree.Range layout) {
        List<GlslLexer.Token> tokens = tree.tokens();
        for (int cursor = layout.start(); cursor < layout.end(); cursor++) {
            if (!tokens.get(cursor).identifier("binding")) continue;
            int equals = GlslLexer.nextSignificant(tokens, cursor);
            int number = equals < 0 ? -1 : GlslLexer.nextSignificant(tokens, equals);
            if (number >= 0 && number < layout.end() && tokens.get(number).kind() == GlslLexer.Kind.NUMBER) {
                return number;
            }
        }
        return -1;
    }

    static String key(Block block) {
        return block.blockName() + "\u0000" + block.instanceName();
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            return -1;
        }
    }
}

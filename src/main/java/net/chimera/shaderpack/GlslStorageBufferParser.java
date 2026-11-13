package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The storage blocks of a prepared source, read from the parse tree, and the binding rewrite for
 * the storage buffer bridge. Prepared sources hold no macros (KNOW-725), so a block's qualifiers
 * are the ones its declaration spells.
 */
final class GlslStorageBufferParser {
    private static final Set<String> MEMORY_QUALIFIERS = Set.of(
            "readonly", "writeonly", "coherent", "volatile", "restrict");

    /**
     * One storage block. {@code laidOut} is whether Vulkan lays it out as GL does: std430, std140,
     * or no packing qualifier, which glslang lays out std430 for Vulkan in every stage alike, as GL
     * lays out an unqualified (shared) block alike in every program. Shrimple qualifies none of its
     * blocks. Explicit shared or packed has no Vulkan layout. A block with no {@code layout(...)} at
     * all is not laid out and has no binding (-1).
     */
    record Block(
            String blockName,
            String instanceName,
            int originalBinding,
            boolean laidOut,
            List<String> accessNames
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
            List<String> accessNames = accessNames(tree, block, instanceName);
            if (declared.layout() == null) {
                // A block without layout is inventory-only. The caller will
                // report it as unsupported rather than inventing a binding.
                result.add(new Block(tree.text(block.name()), instanceName, -1, false, accessNames));
                continue;
            }
            Map<String, String> arguments = GlslDeclarations.layoutArguments(tree, declared.layout());
            String binding = arguments.get("binding");
            result.add(new Block(tree.text(block.name()), instanceName, binding == null ? -1 : parseInt(binding),
                    !arguments.containsKey("shared") && !arguments.containsKey("packed"), accessNames));
        }
        return List.copyOf(result);
    }

    /** The names a shader reads a block by: its instance, or each member of one without an instance. */
    private static List<String> accessNames(GlslTree tree, GlslTree.InterfaceBlock block, String instanceName) {
        if (!instanceName.isEmpty()) return List.of(instanceName);
        List<String> members = new ArrayList<>();
        for (GlslTree.Declaration member : block.members()) {
            member.declarators().forEach(declarator -> members.add(tree.text(declarator.name())));
        }
        return List.copyOf(members);
    }

    /** The source without its file-scope {@code buffer} blocks, whose bindings the plan rewrites. */
    static String withoutBlocks(String source) {
        return withoutBlocks(source, null);
    }

    /** The source without the file-scope {@code buffer} blocks whose keys {@code keys} holds; null for all. */
    static String withoutBlocks(String source, Set<String> keys) {
        if (keys != null && keys.isEmpty()) return source;
        GlslTree tree = GlslTree.parse(source == null ? "" : source);
        List<GlslTree.Edit> removals = storageBlocks(tree).stream()
                .filter(declared -> keys == null || keys.contains(key(tree, declared.block())))
                .map(declared -> new GlslTree.Edit(declared.block().range(), ""))
                .toList();
        return removals.isEmpty() ? source : tree.print(removals);
    }

    private static String key(GlslTree tree, GlslTree.InterfaceBlock block) {
        String instanceName = block.instances().isEmpty() ? "" : tree.text(block.instances().get(0).name());
        return tree.text(block.name()) + "\u0000" + instanceName;
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
            Integer replacement = bindings.get(key(tree, declared.block()));
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

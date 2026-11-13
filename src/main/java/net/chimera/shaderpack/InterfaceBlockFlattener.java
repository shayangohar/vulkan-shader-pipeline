package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A stage's {@code in} and {@code out} interface blocks as plain varyings. GL matches a block
 * across stages by its block name and its members by name, whatever each stage calls the instance
 * (Reverie's vertex writes {@code DataOut.texcoord}, its fragment reads {@code DataIn.texcoord}).
 * Chimera's stage interface matches varyings by name, so every member becomes
 * {@code <Block>_<member>} in each stage that declares the block. Member locations and components
 * go with the block: Chimera gives each varying its location by name (GlslInterfaceScanner). A
 * redeclared {@code gl_PerVertex} is dropped; the built-ins it lists stay.
 *
 * <p>An arrayed instance, a geometry or tessellation stage's per-vertex block, becomes arrayed
 * varyings: {@code vIn[i].color} reads {@code VertexData_color[i]}. GL matches each pair of stages
 * on its own, so a stage between the vertex and fragment stages may read one {@code VertexData}
 * and write another of the same name (Shrimple's shadow.gsh reads an int blockId and writes a uint
 * one). Each name therefore carries a suffix for the stage that writes it (PackPlanBuilder.prepareStages):
 * none for the vertex stage's outputs, so a vertex and fragment pair flattens as it always has.</p>
 */
final class InterfaceBlockFlattener {
    private static final Set<String> STORAGE = Set.of("in", "out");
    private static final Set<String> PRECISION = Set.of("lowp", "mediump", "highp");

    private InterfaceBlockFlattener() {}

    /** The flattened source and the blocks it flattened; the source unchanged when it has none. */
    record Result(String source, List<String> blocks) {}

    /**
     * One block this flattens: the prefix and suffix of its members' new names, the members a
     * reference may name, whether the declarator a reference resolves to is the block's instance or
     * one of its members, and whether that instance is an array.
     */
    private record Flattened(String prefix, String suffix, Set<String> members, boolean instance, boolean arrayed) {
        String rename(String member) {
            return prefix + "_" + member + suffix;
        }
    }

    /** A vertex or fragment stage, or a program of those two alone: no name carries a suffix. */
    static Result flatten(String source) {
        return flatten(source, "", "");
    }

    /**
     * Flattens every block of {@code source}: an input block's members take {@code inputSuffix}, the
     * suffix of the stage that writes them, and an output block's take {@code outputSuffix}.
     */
    static Result flatten(String source, String inputSuffix, String outputSuffix) {
        if (source == null || !source.contains("{")) return new Result(source, List.of());
        GlslTree tree;
        try {
            tree = GlslTree.parse(source);
        } catch (GlslParser.ParseFailure failure) {
            return new Result(source, List.of());
        }
        List<GlslTree.Edit> edits = new ArrayList<>();
        Map<GlslTree.Node, Flattened> owners = new IdentityHashMap<>();
        List<String> blocks = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.InterfaceBlock block) || !flattenable(tree, block)) continue;
            String name = tree.text(block.name());
            blocks.add(name);
            if (name.startsWith("gl_")) {
                edits.add(new GlslTree.Edit(block.range(), ""));
                continue;
            }
            String suffix = storage(tree, block).equals("in") ? inputSuffix : outputSuffix;
            Set<String> members = memberNames(tree, block);
            String instanceArrays = block.instances().isEmpty() ? ""
                    : arrays(tree, block.instances().getFirst().arrays());
            edits.add(new GlslTree.Edit(block.range(), declarations(tree, block, name, suffix, instanceArrays)));
            if (block.instances().isEmpty()) {
                Flattened flattened = new Flattened(name, suffix, members, false, false);
                block.members().forEach(member -> member.declarators()
                        .forEach(declarator -> owners.put(declarator, flattened)));
            } else {
                owners.put(block.instances().getFirst(),
                        new Flattened(name, suffix, members, true, !instanceArrays.isEmpty()));
            }
        }
        if (blocks.isEmpty()) return new Result(source, List.of());
        if (!addReferences(tree, owners, edits)) return new Result(source, List.of());
        return new Result(tree.print(edits), List.copyOf(blocks));
    }

    /** An {@code in} or {@code out} block, not a {@code patch} one, with at most one instance. */
    private static boolean flattenable(GlslTree tree, GlslTree.InterfaceBlock block) {
        boolean patch = block.qualifiers().stream()
                .anyMatch(qualifier -> tree.text(qualifier.keyword()).equals("patch"));
        return !storage(tree, block).isEmpty() && !patch && block.instances().size() <= 1;
    }

    /** The block's {@code in} or {@code out}, or an empty string for any other block. */
    private static String storage(GlslTree tree, GlslTree.InterfaceBlock block) {
        return block.qualifiers().stream().map(qualifier -> tree.text(qualifier.keyword()))
                .filter(STORAGE::contains).findFirst().orElse("");
    }

    private static Set<String> memberNames(GlslTree tree, GlslTree.InterfaceBlock block) {
        Set<String> names = new TreeSet<>();
        for (GlslTree.Declaration member : block.members()) {
            member.declarators().forEach(declarator -> names.add(tree.text(declarator.name())));
        }
        return names;
    }

    private static String arrays(GlslTree tree, List<GlslTree.Range> arrays) {
        StringBuilder text = new StringBuilder();
        arrays.forEach(array -> text.append(tree.text(array).trim()));
        return text.toString();
    }

    /**
     * One declaration per member: its own qualifiers and the block's, layouts dropped, then the
     * storage qualifier and any precision, as GLSL before 4.20 orders them. A member of an arrayed
     * instance takes the instance's dimension ahead of its own.
     */
    private static String declarations(GlslTree tree, GlslTree.InterfaceBlock block, String name, String suffix,
                                       String instanceArrays) {
        StringBuilder result = new StringBuilder();
        for (GlslTree.Declaration member : block.members()) {
            List<String> leading = new ArrayList<>();
            List<String> precision = new ArrayList<>();
            String storage = null;
            for (GlslTree.Qualifier qualifier : concat(member.qualifiers(), block.qualifiers())) {
                String keyword = tree.text(qualifier.keyword());
                if (keyword.equals("layout")) continue;
                if (STORAGE.contains(keyword)) storage = keyword;
                else if (PRECISION.contains(keyword)) precision.add(keyword);
                else if (!leading.contains(keyword)) leading.add(keyword);
            }
            String type = tree.text(member.type().range()).trim();
            for (GlslTree.Declarator declarator : member.declarators()) {
                List<String> words = new ArrayList<>(leading);
                words.add(storage);
                words.addAll(precision);
                words.add(type);
                words.add(name + "_" + tree.text(declarator.name()) + suffix + instanceArrays
                        + arrays(tree, declarator.arrays()) + ";");
                if (!result.isEmpty()) result.append('\n');
                result.append(String.join(" ", words));
            }
        }
        return result.toString();
    }

    private static List<GlslTree.Qualifier> concat(List<GlslTree.Qualifier> first, List<GlslTree.Qualifier> second) {
        List<GlslTree.Qualifier> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    /**
     * Renames each reference to a flattened block: {@code instance.member} of a named block,
     * {@code instance[i].member} of an arrayed one, or {@code member} of an unnamed one. False when
     * an instance is used another way, which plain varyings cannot express, or an index reads
     * another flattened block.
     */
    private static boolean addReferences(GlslTree tree, Map<GlslTree.Node, Flattened> owners,
                                         List<GlslTree.Edit> edits) {
        List<GlslLexer.Token> tokens = tree.tokens();
        List<GlslTree.Range> declared = edits.stream().map(GlslTree.Edit::range).toList();
        for (int index = 0; index < tokens.size(); index++) {
            int at = index;
            if (tokens.get(at).kind() != GlslLexer.Kind.IDENTIFIER
                    || declared.stream().anyMatch(range -> range.contains(at))) continue;
            Flattened owner = owner(tree, owners, at);
            if (owner == null) continue;
            if (!owner.instance()) {
                edits.add(new GlslTree.Edit(new GlslTree.Range(at, at + 1), owner.rename(tokens.get(at).text())));
                continue;
            }
            int end = owner.arrayed() ? arrayedReference(tree, owners, at, owner, edits)
                    : memberReference(tokens, at, owner, edits, "");
            if (end < 0) return false;
            index = end;
        }
        return true;
    }

    /** The flattened block the identifier at {@code at} resolves to, or null. */
    private static Flattened owner(GlslTree tree, Map<GlslTree.Node, Flattened> owners, int at) {
        GlslTree.Binding binding = tree.resolve(at);
        return binding == null ? null : owners.get(binding.node());
    }

    /**
     * Renames {@code instance.member} at {@code at}, with {@code index} after the new name; the
     * token of the member, or -1 when no member follows.
     */
    private static int memberReference(List<GlslLexer.Token> tokens, int at, Flattened owner,
                                       List<GlslTree.Edit> edits, String index) {
        int afterInstance = index.isEmpty() ? at : GlslLexer.matching(tokens, GlslLexer.nextSignificant(tokens, at),
                "[", "]");
        int dot = GlslLexer.nextSignificant(tokens, afterInstance);
        int member = dot < 0 ? -1 : GlslLexer.nextSignificant(tokens, dot);
        if (member < 0 || !tokens.get(dot).symbol(".") || !owner.members().contains(tokens.get(member).text())) {
            return -1;
        }
        edits.add(new GlslTree.Edit(new GlslTree.Range(at, member + 1),
                owner.rename(tokens.get(member).text()) + index));
        return member;
    }

    /** Renames {@code instance[i].member} at {@code at} to {@code Block_member[i]}; the member's token, or -1. */
    private static int arrayedReference(GlslTree tree, Map<GlslTree.Node, Flattened> owners, int at,
                                        Flattened owner, List<GlslTree.Edit> edits) {
        List<GlslLexer.Token> tokens = tree.tokens();
        int open = GlslLexer.nextSignificant(tokens, at);
        if (open < 0 || !tokens.get(open).symbol("[")) return -1;
        int close = GlslLexer.matching(tokens, open, "[", "]");
        if (close < 0) return -1;
        for (int inner = open + 1; inner < close; inner++) {
            if (tokens.get(inner).kind() == GlslLexer.Kind.IDENTIFIER && owner(tree, owners, inner) != null) return -1;
        }
        return memberReference(tokens, at, owner, edits, GlslLexer.render(tokens.subList(open, close + 1)));
    }
}

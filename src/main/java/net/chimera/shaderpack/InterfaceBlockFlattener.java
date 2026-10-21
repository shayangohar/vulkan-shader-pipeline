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
 * redeclared {@code gl_PerVertex} is dropped; the built-ins it lists stay. An arrayed block, a
 * geometry or tessellation stage's, is left for the scanner to report.
 */
final class InterfaceBlockFlattener {
    private static final Set<String> STORAGE = Set.of("in", "out");
    private static final Set<String> PRECISION = Set.of("lowp", "mediump", "highp");

    private InterfaceBlockFlattener() {}

    /** The flattened source and the blocks it flattened; the source unchanged when it has none. */
    record Result(String source, List<String> blocks) {}

    /**
     * One block this flattens: its name, the members a reference may name, and whether the
     * declarator a reference resolves to is the block's instance or one of its members.
     */
    private record Flattened(String name, Set<String> members, boolean instance) {}

    static Result flatten(String source) {
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
            Set<String> members = memberNames(tree, block);
            edits.add(new GlslTree.Edit(block.range(), declarations(tree, block, name)));
            if (block.instances().isEmpty()) {
                Flattened flattened = new Flattened(name, members, false);
                block.members().forEach(member -> member.declarators()
                        .forEach(declarator -> owners.put(declarator, flattened)));
            } else {
                owners.put(block.instances().getFirst(), new Flattened(name, members, true));
            }
        }
        if (blocks.isEmpty()) return new Result(source, List.of());
        if (!addReferences(tree, owners, edits)) return new Result(source, List.of());
        return new Result(tree.print(edits), List.copyOf(blocks));
    }

    /** An {@code in} or {@code out} block with at most one instance, which is not an array. */
    private static boolean flattenable(GlslTree tree, GlslTree.InterfaceBlock block) {
        boolean storage = block.qualifiers().stream()
                .anyMatch(qualifier -> STORAGE.contains(tree.text(qualifier.keyword())));
        boolean patch = block.qualifiers().stream()
                .anyMatch(qualifier -> tree.text(qualifier.keyword()).equals("patch"));
        return storage && !patch && block.instances().size() <= 1
                && block.instances().stream().allMatch(instance -> instance.arrays().isEmpty());
    }

    private static Set<String> memberNames(GlslTree tree, GlslTree.InterfaceBlock block) {
        Set<String> names = new TreeSet<>();
        for (GlslTree.Declaration member : block.members()) {
            member.declarators().forEach(declarator -> names.add(tree.text(declarator.name())));
        }
        return names;
    }

    /**
     * One declaration per member: its own qualifiers and the block's, layouts dropped, then the
     * storage qualifier and any precision, as GLSL before 4.20 orders them.
     */
    private static String declarations(GlslTree tree, GlslTree.InterfaceBlock block, String name) {
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
                StringBuilder arrays = new StringBuilder();
                declarator.arrays().forEach(array -> arrays.append(tree.text(array).trim()));
                List<String> words = new ArrayList<>(leading);
                words.add(storage);
                words.addAll(precision);
                words.add(type);
                words.add(name + "_" + tree.text(declarator.name()) + arrays + ";");
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
     * Renames each reference to a flattened block: {@code instance.member} of a named block, or
     * {@code member} of an unnamed one. False when an instance is used another way, which a block
     * of plain varyings cannot express.
     */
    private static boolean addReferences(GlslTree tree, Map<GlslTree.Node, Flattened> owners,
                                         List<GlslTree.Edit> edits) {
        List<GlslLexer.Token> tokens = tree.tokens();
        List<GlslTree.Range> declared = edits.stream().map(GlslTree.Edit::range).toList();
        for (int index = 0; index < tokens.size(); index++) {
            int at = index;
            if (tokens.get(at).kind() != GlslLexer.Kind.IDENTIFIER
                    || declared.stream().anyMatch(range -> range.contains(at))) continue;
            GlslTree.Binding binding = tree.resolve(at);
            Flattened owner = binding == null ? null : owners.get(binding.node());
            if (owner == null) continue;
            if (!owner.instance()) {
                edits.add(new GlslTree.Edit(new GlslTree.Range(at, at + 1),
                        owner.name() + "_" + tokens.get(at).text()));
                continue;
            }
            int dot = GlslLexer.nextSignificant(tokens, at);
            int member = dot < 0 ? -1 : GlslLexer.nextSignificant(tokens, dot);
            if (member < 0 || !tokens.get(dot).symbol(".") || !owner.members().contains(tokens.get(member).text())) {
                return false;
            }
            edits.add(new GlslTree.Edit(new GlslTree.Range(at, member + 1),
                    owner.name() + "_" + tokens.get(member).text()));
            index = member;
        }
        return true;
    }
}

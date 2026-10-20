package net.chimera.shaderpack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resource use analysis for prepared GLSL, read from the parse tree.
 *
 * <p>The analysis distinguishes source inventory from entry-point use. It is
 * intentionally conservative for overloaded calls, recursive calls, and
 * source snippets without a main function.</p>
 */
final class GlslResourceUsage {
    private static final Set<String> FUNCTION_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "sizeof");

    /** Complete token range for one top-level function definition. */
    record FunctionDefinition(
            String name,
            int definitionStart,
            int bodyStart,
            int bodyEnd,
            int definitionEnd
    ) {}

    record Analysis(
            Set<String> declaredSamplers,
            Set<String> referencedSamplers,
            Set<String> liveSamplers,
            Set<String> declaredImages,
            Set<String> liveImages,
            Set<String> declaredStorageBlocks,
            Set<String> liveStorageBlocks,
            List<FunctionDefinition> reachableFunctions,
            List<FunctionDefinition> unreachableFunctions,
            boolean successful,
            List<String> deviations
    ) {
        Analysis {
            declaredSamplers = sorted(declaredSamplers);
            referencedSamplers = sorted(referencedSamplers);
            liveSamplers = sorted(liveSamplers);
            declaredImages = sorted(declaredImages);
            liveImages = sorted(liveImages);
            declaredStorageBlocks = sorted(declaredStorageBlocks);
            liveStorageBlocks = sorted(liveStorageBlocks);
            reachableFunctions = sortedFunctions(reachableFunctions);
            unreachableFunctions = sortedFunctions(unreachableFunctions);
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }
    }

    private GlslResourceUsage() {}

    static Analysis analyze(String source) {
        return analyze(source, Set.of());
    }

    /**
     * Analyzes the source and also treats the supplied names as injected
     * sampler identifiers. Iris image properties can inject sampler names
     * without adding a GLSL uniform declaration. They still need the same
     * entry-point reachability rules as authored samplers.
     */
    static Analysis analyze(String source, Set<String> injectedSamplers) {
        AnalysisKey key = new AnalysisKey(source == null ? "" : source,
                injectedSamplers == null ? Set.of() : Set.copyOf(injectedSamplers));
        synchronized (RECENT) {
            Analysis known = RECENT.get(key);
            if (known != null) return known;
        }
        Analysis analysis = analyzeUncached(key.source(), key.injectedSamplers());
        synchronized (RECENT) {
            RECENT.put(key, analysis);
        }
        return analysis;
    }

    /** One analysis input: the result depends on nothing else. */
    private record AnalysisKey(String source, Set<String> injectedSamplers) {}

    /**
     * The latest analyses. Planning one program asks about the same prepared source many times, once
     * per sampler among others, and each answer would trace the whole program again.
     */
    private static final Map<AnalysisKey, Analysis> RECENT = new LinkedHashMap<>(32, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<AnalysisKey, Analysis> eldest) {
            return size() > 16;
        }
    };

    private static Analysis analyzeUncached(String text, Set<String> injectedSamplers) {
        try {
            GlslTree tree = GlslTree.parse(text);
            Declared declared = declared(tree, injectedSamplers);
            Map<FunctionDefinition, Uses> bodies = new LinkedHashMap<>();
            for (GlslTree.External external : tree.externals()) {
                if (external instanceof GlslTree.Function function && function.body() != null) {
                    GlslTree.Range body = function.body().range();
                    bodies.put(new FunctionDefinition(tree.text(function.name()), function.range().start(),
                            body.start() + 1, body.end() - 1, body.end() - 1), uses(tree, declared, body));
                }
            }
            Uses topLevel = topLevelUses(tree, declared);
            Resources referenced = new Resources();
            bodies.values().forEach(uses -> referenced.addAll(uses.reads()));
            referenced.addAll(topLevel.reads());
            List<FunctionDefinition> functions = List.copyOf(bodies.keySet());
            FunctionDefinition main = functions.stream().filter(value -> value.name().equals("main"))
                    .findFirst().orElse(null);
            Set<FunctionDefinition> reachable = new HashSet<>();
            Resources live = new Resources();
            if (main == null) {
                // Interface-only snippets and compute wrappers without a
                // conventional main are retained conservatively.
                reachable.addAll(functions);
                live.addAll(referenced);
            } else {
                trace(main, bodies, topLevel, reachable, live);
            }
            List<FunctionDefinition> unreachable = main == null ? List.of()
                    : functions.stream().filter(value -> !reachable.contains(value)).toList();
            Resources names = declared.names();
            return new Analysis(names.samplers(), referenced.samplers(), live.samplers(),
                    names.images(), live.images(), names.storage(), live.storage(),
                    functions.stream().filter(reachable::contains).toList(), unreachable, true, List.of());
        } catch (RuntimeException failure) {
            return new Analysis(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Set.of(), List.of(), List.of(), false,
                    List.of("RESOURCE_USAGE_UNCLASSIFIABLE"));
        }
    }

    /**
     * Follows calls from main through function bodies, collecting the functions it reaches and the
     * resources their bodies read. A global initializer runs before main, so what it reads is live
     * and what it calls is reached: Spring reads {@code vec4 CT4 = texture(colortex4, texcoord);} at
     * file scope. Every overload of a called name is reached.
     */
    private static void trace(FunctionDefinition main, Map<FunctionDefinition, Uses> bodies, Uses topLevel,
                              Set<FunctionDefinition> reachable, Resources live) {
        Map<String, List<FunctionDefinition>> byName = new HashMap<>();
        for (FunctionDefinition function : bodies.keySet()) {
            byName.computeIfAbsent(function.name(), ignored -> new ArrayList<>()).add(function);
        }
        Deque<FunctionDefinition> pending = new ArrayDeque<>();
        pending.add(main);
        topLevel.calls().forEach(name -> pending.addAll(byName.getOrDefault(name, List.of())));
        live.addAll(topLevel.reads());
        while (!pending.isEmpty()) {
            FunctionDefinition function = pending.removeFirst();
            if (!reachable.add(function)) continue;
            Uses uses = bodies.get(function);
            live.addAll(uses.reads());
            uses.calls().forEach(name -> pending.addAll(byName.getOrDefault(name, List.of())));
        }
    }

    /** Sampler, 3D image and storage block names. */
    private record Resources(Set<String> samplers, Set<String> images, Set<String> storage) {
        Resources() {
            this(new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
        }

        /** Adds {@code name} under every kind {@code declared} lists it as. */
        void addDeclared(String name, Resources declared) {
            if (declared.samplers().contains(name)) samplers.add(name);
            if (declared.images().contains(name)) images.add(name);
            if (declared.storage().contains(name)) storage.add(name);
        }

        void addAll(Resources other) {
            samplers.addAll(other.samplers());
            images.addAll(other.images());
            storage.addAll(other.storage());
        }

        Set<String> all() {
            Set<String> all = new HashSet<>(samplers);
            all.addAll(images);
            all.addAll(storage);
            return all;
        }
    }

    /** The resource names a source declares, every one of them, and the tokens that declare them. */
    private record Declared(Resources names, Set<String> all, Set<Integer> tokens) {}

    /** What one stretch of source reads and calls. */
    private record Uses(Resources reads, Set<String> calls) {}

    /**
     * The file-scope uniform samplers and 3D images (every name of a list: {@code writeonly uniform
     * image3D a, b;}) and storage blocks with their instances, starting from the injected samplers.
     */
    private static Declared declared(GlslTree tree, Set<String> injectedSamplers) {
        Resources names = new Resources();
        Set<Integer> tokens = new HashSet<>();
        if (injectedSamplers != null) {
            injectedSamplers.stream().filter(value -> value != null && !value.isBlank())
                    .forEach(names.samplers()::add);
        }
        for (GlslTree.External external : tree.externals()) {
            if (external instanceof GlslTree.Declaration declaration && declaration.type() != null
                    && declaration.type().name() >= 0) {
                String type = tree.text(declaration.type().name());
                boolean uniform = declaration.qualifiers().stream()
                        .anyMatch(qualifier -> tree.text(qualifier.keyword()).equals("uniform"));
                Set<String> kind = uniform && isSampler(type) ? names.samplers()
                        : isImageType(type) ? names.images() : null;
                if (kind == null) continue;
                for (GlslTree.Declarator declarator : declaration.declarators()) {
                    kind.add(tree.text(declarator.name()));
                    tokens.add(declarator.name());
                }
            } else if (external instanceof GlslTree.InterfaceBlock block && block.qualifiers().stream()
                    .anyMatch(qualifier -> tree.text(qualifier.keyword()).equals("buffer"))) {
                names.storage().add(tree.text(block.name()));
                tokens.add(block.name());
                for (GlslTree.Declarator instance : block.instances()) {
                    names.storage().add(tree.text(instance.name()));
                    tokens.add(instance.name());
                }
            }
        }
        return new Declared(names, names.all(), tokens);
    }

    /** What file scope reads and calls: global initializers, array sizes and prototypes. */
    private static Uses topLevelUses(GlslTree tree, Declared declared) {
        Uses uses = new Uses(new Resources(), new HashSet<>());
        for (GlslTree.External external : tree.externals()) {
            boolean definition = external instanceof GlslTree.Function function && function.body() != null;
            if (definition || external instanceof GlslTree.Directive) continue;
            Uses part = uses(tree, declared, external.range());
            uses.reads().addAll(part.reads());
            uses.calls().addAll(part.calls());
        }
        return uses;
    }

    /**
     * The resources {@code range} reads, where a name resolves to the global (a parameter or local
     * that shares a resource's name hides it), and the names it calls.
     */
    private static Uses uses(GlslTree tree, Declared declared, GlslTree.Range range) {
        List<GlslLexer.Token> tokens = tree.tokens();
        Uses uses = new Uses(new Resources(), new HashSet<>());
        for (int index = range.start(); index < range.end(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER || declared.tokens().contains(index)) continue;
            int next = GlslLexer.nextSignificant(tokens, index);
            if (next >= 0 && tokens.get(next).symbol("(") && !FUNCTION_KEYWORDS.contains(token.text())) {
                uses.calls().add(token.text());
            }
            if (declared.all().contains(token.text()) && readsGlobal(tree, index)) {
                uses.reads().addDeclared(token.text(), declared.names());
            }
        }
        return uses;
    }

    /** Whether the name at {@code index} reads a global: not a field, a declaration, a parameter or a local. */
    private static boolean readsGlobal(GlslTree tree, int index) {
        int previous = GlslLexer.previousSignificant(tree.tokens(), index);
        if (previous >= 0 && tree.tokens().get(previous).symbol(".")) return false;
        GlslTree.Binding binding = tree.resolve(index);
        return binding == null || binding.name() != index && !GlslScopedReferences.insideFunction(tree, binding);
    }

    static Set<String> referencedSamplers(String source) {
        return analyze(source).referencedSamplers();
    }

    static Set<String> liveSamplers(String source) {
        return analyze(source).liveSamplers();
    }

    /**
     * Finds canonical uniform names used as values, not calls or declarations.
     * This deliberately does not use a builtin-name denylist. A name is a
     * value reference only when the tree reads it as one.
     */
    static Set<String> referencedValueIdentifiers(String source, Set<String> candidates) {
        if (source == null || source.isBlank() || candidates == null || candidates.isEmpty()) {
            return Set.of();
        }
        try {
            GlslTree tree = GlslTree.parse(source);
            List<GlslLexer.Token> tokens = tree.tokens();
            Set<Integer> members = GlslScopedReferences.memberNames(tree);
            Set<Integer> directives = directiveTokens(tree);
            Set<String> result = new TreeSet<>();
            for (int index = 0; index < tokens.size(); index++) {
                GlslLexer.Token token = tokens.get(index);
                if (token.kind() == GlslLexer.Kind.IDENTIFIER && candidates.contains(token.text())
                        && !members.contains(index) && !directives.contains(index) && valueReference(tree, index)) {
                    result.add(token.text());
                }
            }
            return Collections.unmodifiableSet(result);
        } catch (RuntimeException failure) {
            return Set.of();
        }
    }

    /** Whether the name at {@code index} is a value: not a field, a call or a declaration's own name. */
    private static boolean valueReference(GlslTree tree, int index) {
        List<GlslLexer.Token> tokens = tree.tokens();
        int previous = GlslLexer.previousSignificant(tokens, index);
        int next = GlslLexer.nextSignificant(tokens, index);
        if (previous >= 0 && tokens.get(previous).symbol(".")) return false;
        if (next >= 0 && tokens.get(next).symbol("(")) return false;
        GlslTree.Binding binding = tree.resolve(index);
        return binding == null || binding.name() != index;
    }

    /** The tokens of file-scope directives. */
    private static Set<Integer> directiveTokens(GlslTree tree) {
        Set<Integer> tokens = new HashSet<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Directive directive)) continue;
            for (int index = directive.range().start(); index < directive.range().end(); index++) tokens.add(index);
        }
        return tokens;
    }

    private static boolean isSampler(String type) {
        return type.startsWith("sampler") || type.startsWith("isampler") || type.startsWith("usampler");
    }

    private static boolean isImageType(String type) {
        return type.equals("image3D") || type.equals("iimage3D") || type.equals("uimage3D");
    }

    private static Set<String> sorted(Set<String> values) {
        return Collections.unmodifiableSet(new TreeSet<>(values == null ? Set.of() : values));
    }

    private static List<FunctionDefinition> sortedFunctions(List<FunctionDefinition> values) {
        if (values == null) return List.of();
        return values.stream().distinct().sorted(Comparator
                .comparing(FunctionDefinition::name)
                .thenComparingInt(FunctionDefinition::definitionStart)
                .thenComparingInt(FunctionDefinition::definitionEnd)).toList();
    }
}

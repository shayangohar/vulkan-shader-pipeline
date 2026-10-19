package net.chimera.shaderpack;

import java.util.List;

/**
 * Name resolution over a {@link GlslTree}: a name declared in an inner block hides one outside it
 * until that block closes, and a declaration counts from its name on. Kappa names a parameter after
 * its {@code lightDir} uniform; inside that function, {@code lightDir} is the parameter.
 */
final class GlslScopes {
    private GlslScopes() {}

    /** The local, for-init or parameter declaration of {@code name} that {@code token} sees, if any. */
    static GlslTree.Binding inFunction(GlslTree tree, GlslTree.Function function, String name, int token) {
        GlslTree.Binding local = inStatement(tree, function.body(), name, token);
        if (local != null) return local;
        for (GlslTree.Parameter parameter : function.parameters()) {
            if (named(tree, parameter.name(), name, token)) return new GlslTree.Binding(parameter, parameter.name());
        }
        return null;
    }

    /** The file-scope declaration of {@code name} that {@code token} sees, if any. */
    static GlslTree.Binding atFileScope(GlslTree tree, String name, int token) {
        GlslTree.Binding found = null;
        for (GlslTree.External external : tree.externals()) {
            if (external.range().start() > token) break;
            GlslTree.Binding binding = switch (external) {
                case GlslTree.Declaration declaration -> declared(tree, declaration, name, token);
                case GlslTree.InterfaceBlock block -> inInterfaceBlock(tree, block, name, token);
                case GlslTree.Function function -> named(tree, function.name(), name, token)
                        ? new GlslTree.Binding(function, function.name()) : null;
                default -> null;
            };
            if (binding != null) found = binding;
        }
        return found;
    }

    /** Searches the innermost statement holding {@code token} first, then outwards. */
    private static GlslTree.Binding inStatement(GlslTree tree, GlslTree.Statement statement, String name,
                                                int token) {
        if (!statement.range().contains(token)) return null;
        if (statement instanceof GlslTree.Block block) {
            for (GlslTree.Statement inner : block.statements()) {
                if (!inner.range().contains(token)) continue;
                GlslTree.Binding nested = inStatement(tree, inner, name, token);
                if (nested != null) return nested;
                break;
            }
            return declaredBefore(tree, block.statements(), name, token);
        }
        if (statement instanceof GlslTree.Control control) {
            for (GlslTree.Statement body : control.bodies()) {
                GlslTree.Binding nested = inStatement(tree, body, name, token);
                if (nested != null) return nested;
            }
            return control.init() == null ? null : declared(tree, control.init(), name, token);
        }
        return null;
    }

    private static GlslTree.Binding declaredBefore(GlslTree tree, List<GlslTree.Statement> statements,
                                                   String name, int token) {
        GlslTree.Binding found = null;
        for (GlslTree.Statement statement : statements) {
            if (statement.range().start() > token) break;
            if (statement instanceof GlslTree.Declaration declaration) {
                GlslTree.Binding binding = declared(tree, declaration, name, token);
                if (binding != null) found = binding;
            }
        }
        return found;
    }

    /** A declarator or struct name of {@code declaration} that is {@code name} and starts by {@code token}. */
    private static GlslTree.Binding declared(GlslTree tree, GlslTree.Declaration declaration, String name,
                                             int token) {
        for (GlslTree.Declarator declarator : declaration.declarators()) {
            if (visible(tree, declarator, name, token)) return new GlslTree.Binding(declarator, declarator.name());
        }
        GlslTree.Struct struct = declaration.type() == null ? null : declaration.type().struct();
        if (struct != null && named(tree, struct.name(), name, token)) {
            return new GlslTree.Binding(struct, struct.name());
        }
        return null;
    }

    /** A named block's instance, or a member of an unnamed one, which file scope sees directly. */
    private static GlslTree.Binding inInterfaceBlock(GlslTree tree, GlslTree.InterfaceBlock block, String name,
                                                     int token) {
        for (GlslTree.Declarator instance : block.instances()) {
            if (named(tree, instance.name(), name, token)) return new GlslTree.Binding(instance, instance.name());
        }
        if (!block.instances().isEmpty()) return null;
        for (GlslTree.Declaration member : block.members()) {
            GlslTree.Binding binding = declared(tree, member, name, token);
            if (binding != null) return binding;
        }
        return null;
    }

    /**
     * A declarator names itself, and its scope begins after its initializer (GLSL 4.60 section
     * 4.2.2): in {@code { int x = x; }} the initializer reads the outer {@code x}.
     */
    private static boolean visible(GlslTree tree, GlslTree.Declarator declarator, String name, int token) {
        if (!named(tree, declarator.name(), name, token)) return false;
        return token == declarator.name() || declarator.initializer() == null
                || token >= declarator.initializer().end();
    }

    private static boolean named(GlslTree tree, int nameToken, String name, int token) {
        return nameToken >= 0 && nameToken <= token && tree.text(nameToken).equals(name);
    }
}

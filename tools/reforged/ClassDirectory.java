package reforged;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** A directory of class files, such as Chimera's build output or an extracted jar, read on demand. */
final class ClassDirectory implements ClassLookup {
    private final Path root;
    private final Map<String, Optional<ClassNode>> cache = new HashMap<>();

    ClassDirectory(String root) {
        this.root = Path.of(root);
    }

    @Override public ClassNode find(String internalName) {
        return cache.computeIfAbsent(internalName, this::read).orElse(null);
    }

    private Optional<ClassNode> read(String internalName) {
        if (!Files.exists(path(internalName))) return Optional.empty();
        ClassNode type = new ClassNode();
        new ClassReader(bytes(internalName)).accept(type, 0);
        return Optional.of(type);
    }

    byte[] bytes(String internalName) {
        try {
            return Files.readAllBytes(path(internalName));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path path(String internalName) {
        return root.resolve(internalName + ".class");
    }

    /** The internal names of the classes under {@code prefix}, sorted. */
    List<String> classNames(String prefix) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .filter(name -> name.startsWith(prefix))
                    .sorted().toList();
        }
    }
}

package net.chimera.gui.options;

import net.chimera.ChimeraMod;
import net.chimera.shaderpack.options.OptionFile;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The native file dialogs behind Import and Export. They open off the render thread, starting at
 * the pack's own option file, and offer {@code .txt} files.
 */
final class SettingsTransfer {
    private static final String FILTER = "*.txt";
    private static final String FILTER_LABEL = "Shader Pack Settings (.txt)";

    private SettingsTransfer() {}

    /** Asks for a settings file to import and hands the choice to {@code chosen}, off the render thread. */
    static void chooseImport(Path packPath, Consumer<Path> chosen) {
        CompletableFuture.runAsync(() -> {
            String file;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                file = TinyFileDialogs.tinyfd_openFileDialog("Import Shader Settings from File",
                        OptionFile.of(packPath).toString(), filter(stack), FILTER_LABEL, false);
            }
            if (file != null) chosen.accept(Path.of(file));
        });
    }

    /**
     * Asks where to export and writes the pack's applied settings there: what its option file holds
     * now, so changes not yet applied stay out. An all-default pack exports an empty file.
     */
    static void export(Path packPath) {
        CompletableFuture.runAsync(() -> {
            String file;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                file = TinyFileDialogs.tinyfd_saveFileDialog("Export Shader Settings to File",
                        OptionFile.of(packPath).toString(), filter(stack), FILTER_LABEL);
            }
            if (file == null) return;
            try {
                OptionFile.store(Path.of(file), OptionFile.read(OptionFile.of(packPath)));
            } catch (IOException failure) {
                ChimeraMod.LOGGER.error("[chimera] cannot export shader settings to {}", file, failure);
            }
        });
    }

    private static PointerBuffer filter(MemoryStack stack) {
        PointerBuffer patterns = stack.mallocPointer(1);
        patterns.put(stack.UTF8(FILTER));
        return patterns.flip();
    }
}

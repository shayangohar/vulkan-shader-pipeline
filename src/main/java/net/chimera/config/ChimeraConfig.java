package net.chimera.config;

import net.chimera.ChimeraMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * The persisted shader selection, {@code config/chimera.properties}, written with Iris's keys:
 * {@code shaderPack} names a pack in the shaderpacks folder and {@code enableShaders} says
 * whether it runs. The selector screen and the pack commands write it; startup reads it.
 */
public final class ChimeraConfig {
    private static final String SHADER_PACK = "shaderPack";
    private static final String ENABLE_SHADERS = "enableShaders";
    private static ChimeraConfig instance;

    private final Path file;
    private String shaderPack;
    private boolean enableShaders;

    private ChimeraConfig(Path file) {
        this.file = file;
    }

    public static synchronized ChimeraConfig get() {
        if (instance == null) {
            instance = load(FabricLoader.getInstance().getConfigDir().resolve("chimera.properties"));
        }
        return instance;
    }

    static ChimeraConfig load(Path file) {
        ChimeraConfig config = new ChimeraConfig(file);
        if (Files.isRegularFile(file)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException failure) {
                ChimeraMod.LOGGER.warn("[chimera] cannot read {}; starting with no pack", file, failure);
            }
            String pack = properties.getProperty(SHADER_PACK, "").trim();
            config.shaderPack = pack.isEmpty() ? null : pack;
            config.enableShaders = Boolean.parseBoolean(properties.getProperty(ENABLE_SHADERS, "false"));
        }
        return config;
    }

    public Optional<String> shaderPack() {
        return Optional.ofNullable(shaderPack);
    }

    public boolean shadersEnabled() {
        return enableShaders;
    }

    /** Records the selection and writes it; a failed write is logged and never thrown. */
    public synchronized void setSelection(String pack, boolean enabled) {
        this.shaderPack = pack == null || pack.isBlank() ? null : pack;
        this.enableShaders = enabled;
        save();
    }

    private void save() {
        Properties properties = new Properties();
        properties.setProperty(SHADER_PACK, shaderPack == null ? "" : shaderPack);
        properties.setProperty(ENABLE_SHADERS, Boolean.toString(enableShaders));
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                properties.store(out, "Chimera shader pack selection");
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            ChimeraMod.LOGGER.warn("[chimera] cannot write {}", file, failure);
        }
    }
}

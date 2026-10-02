package net.chimera.command;

import net.chimera.config.ChimeraConfig;
import net.chimera.config.ShaderpackDirectory;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.PackNanTripwire;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Client commands for selecting a shaderpack without restarting Minecraft. */
public final class ChimeraCommands {
    private ChimeraCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("chimera")
                        .then(ClientCommandManager.literal("pack")
                                .then(ClientCommandManager.literal("list")
                                        .executes(ChimeraCommands::listPacks))
                                .then(ClientCommandManager.literal("load")
                                        .then(ClientCommandManager.argument(
                                                        "nameOrPath", StringArgumentType.greedyString())
                                                .executes(ChimeraCommands::loadPack)))
                                .then(ClientCommandManager.literal("reload")
                                        .executes(ChimeraCommands::reloadPack))
                                .then(ClientCommandManager.literal("off")
                                        .executes(ChimeraCommands::turnPackOff))
                                .then(ClientCommandManager.literal("status")
                                        .executes(ChimeraCommands::packStatus)))
                        .then(ClientCommandManager.literal("debug")
                                .then(ClientCommandManager.literal("nan")
                                        .then(ClientCommandManager.literal("on")
                                                .executes(context -> setNanTripwire(context, true)))
                                        .then(ClientCommandManager.literal("off")
                                                .executes(context -> setNanTripwire(context, false)))
                                        .then(ClientCommandManager.literal("test")
                                                .executes(ChimeraCommands::nanTripwireSelfTest))
                                        .executes(ChimeraCommands::nanTripwireStatus)))));
    }

    private static int listPacks(CommandContext<FabricClientCommandSource> context) {
        Path root = shaderpacksRoot();
        try {
            List<String> names = listPackNames(root);
            if (names.isEmpty()) {
                context.getSource().sendFeedback(Component.literal(
                        "No shaderpacks found in " + root));
                return 0;
            }
            context.getSource().sendFeedback(Component.literal(
                    "Shaderpacks in " + root + ": " + String.join(", ", names)));
            return names.size();
        } catch (IOException failure) {
            context.getSource().sendError(Component.literal(
                    "Cannot list shaderpacks: " + failure.getMessage()));
            return -1;
        }
    }

    private static int loadPack(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "nameOrPath");
        Resolution resolution = resolvePackPath(shaderpacksRoot(), gameDirectory(), raw);
        if (!resolution.valid()) {
            context.getSource().sendError(Component.literal(resolution.error()));
            return -1;
        }
        ChimeraRenderer.PackRequestResult result = ChimeraRenderer.requestPack(resolution.path());
        if (result != ChimeraRenderer.PackRequestResult.NOT_READY) {
            rememberSelection(resolution.path(), true);
        }
        return reportRequest(context, result, "pack " + resolution.path().getFileName());
    }

    private static int reloadPack(CommandContext<FabricClientCommandSource> context) {
        ChimeraRenderer.PackRequestResult result = ChimeraRenderer.reloadPack();
        return reportRequest(context, result, "current pack");
    }

    private static int turnPackOff(CommandContext<FabricClientCommandSource> context) {
        ChimeraRenderer.PackRequestResult result = ChimeraRenderer.disablePack();
        if (result != ChimeraRenderer.PackRequestResult.NOT_READY) {
            ChimeraConfig config = ChimeraConfig.get();
            config.setSelection(config.shaderPack().orElse(null), false);
        }
        switch (result) {
            case QUEUED -> context.getSource().sendFeedback(Component.literal(
                    "Chimera shader pack off queued; vanilla rendering resumes at the next safe frame boundary."));
            case ALREADY_ACTIVE -> context.getSource().sendFeedback(Component.literal(
                    "No Chimera shader pack is loaded; rendering is already vanilla."));
            case NOT_READY -> context.getSource().sendError(Component.literal(
                    "Chimera is not ready; no pack change was queued."));
        }
        return result == ChimeraRenderer.PackRequestResult.NOT_READY ? -1 : 0;
    }

    /**
     * Saves a pack loaded from the shaderpacks folder as the selection, as the selector screen
     * does. A pack loaded by an explicit path outside that folder cannot be named in the config,
     * so the saved selection is left as it was.
     */
    private static void rememberSelection(Path pack, boolean enabled) {
        Path root = shaderpacksRoot();
        try {
            Path parent = pack.toRealPath().getParent();
            if (parent != null && Files.isDirectory(root) && parent.equals(root.toRealPath())) {
                ChimeraConfig.get().setSelection(pack.getFileName().toString(), enabled);
            }
        } catch (IOException failure) {
            // Not in the shaderpacks folder: nothing to remember.
        }
    }

    private static int packStatus(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Component.literal(ChimeraRenderer.packStatus()));
        return 0;
    }

    private static int setNanTripwire(CommandContext<FabricClientCommandSource> context, boolean on) {
        PackNanTripwire.setEnabled(on);
        context.getSource().sendFeedback(Component.literal(on
                ? "NaN tripwire on."
                : "NaN tripwire off."));
        return on ? 1 : 0;
    }

    private static int nanTripwireSelfTest(CommandContext<FabricClientCommandSource> context) {
        PackNanTripwire.requestSelfTest();
        context.getSource().sendFeedback(Component.literal(
                "NaN tripwire self-test queued."));
        return 1;
    }

    private static int nanTripwireStatus(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Component.literal(
                "NaN tripwire " + (PackNanTripwire.enabled() ? "on." : "off.")));
        return PackNanTripwire.enabled() ? 1 : 0;
    }

    private static int reportRequest(
            CommandContext<FabricClientCommandSource> context,
            ChimeraRenderer.PackRequestResult result,
            String target
    ) {
        switch (result) {
            case QUEUED -> context.getSource().sendFeedback(Component.literal(
                    "Chimera " + target + " change queued; it will apply at the next safe frame boundary."));
            case ALREADY_ACTIVE -> context.getSource().sendFeedback(Component.literal(
                    "Chimera " + target + " is already active."));
            case NOT_READY -> context.getSource().sendError(Component.literal(
                    "Chimera is not ready; no pack change was queued."));
        }
        return result == ChimeraRenderer.PackRequestResult.NOT_READY ? -1 : 0;
    }

    static Path shaderpacksRoot(Path gameDirectory) {
        return ShaderpackDirectory.root(gameDirectory);
    }

    private static Path shaderpacksRoot() {
        return shaderpacksRoot(gameDirectory());
    }

    private static Path gameDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    static List<String> listPackNames(Path root) throws IOException {
        return ShaderpackDirectory.list(root);
    }

    static Resolution resolvePackPath(Path shaderpacksRoot, Path gameDirectory, String raw) {
        String input = stripQuotes(raw);
        if (input.isBlank()) {
            return Resolution.invalid("Pack name or path is empty.");
        }

        try {
            Path candidate;
            if (isSimpleName(input)) {
                List<Path> matches = namedMatches(shaderpacksRoot, input);
                if (matches.isEmpty()) {
                    return Resolution.invalid("Shaderpack not found in " + shaderpacksRoot + ": " + input);
                }
                if (matches.size() > 1) {
                    return Resolution.invalid("Shaderpack name is ambiguous: " + input);
                }
                candidate = matches.get(0);
            } else {
                candidate = Path.of(input);
                if (!candidate.isAbsolute()) {
                    candidate = gameDirectory.resolve(candidate);
                }
            }

            candidate = candidate.normalize();
            if (candidate.toString().startsWith("\\\\")) {
                return Resolution.invalid("Network paths are not supported: " + input);
            }
            if (!Files.exists(candidate)) {
                return Resolution.invalid("Shaderpack path does not exist: " + candidate);
            }
            Path real = candidate.toRealPath();
            if (!isPackPath(real)) {
                return Resolution.invalid("Path is not a shaderpack directory or ZIP archive: " + real);
            }
            return Resolution.valid(real);
        } catch (InvalidPathException failure) {
            return Resolution.invalid("Invalid shaderpack path: " + input);
        } catch (IOException failure) {
            return Resolution.invalid("Cannot resolve shaderpack path: " + failure.getMessage());
        }
    }

    private static List<Path> namedMatches(Path root, String input) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        String zipName = input.toLowerCase().endsWith(".zip") ? input : input + ".zip";
        try (var entries = Files.list(root)) {
            return entries
                    .filter(ChimeraCommands::isPackPath)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.equalsIgnoreCase(input) || name.equalsIgnoreCase(zipName);
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    private static boolean isSimpleName(String input) {
        return !input.equals(".") && !input.equals("..")
                && input.indexOf('/') < 0 && input.indexOf('\\') < 0 && input.indexOf(':') < 0;
    }

    private static boolean isPackPath(Path path) {
        return ShaderpackDirectory.isValidPack(path);
    }

    private static String stripQuotes(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    static final class Resolution {
        private final Path path;
        private final String error;

        private Resolution(Path path, String error) {
            this.path = path;
            this.error = error;
        }

        static Resolution valid(Path path) {
            return new Resolution(path, null);
        }

        static Resolution invalid(String error) {
            return new Resolution(null, error);
        }

        boolean valid() {
            return path != null;
        }

        Path path() {
            return path;
        }

        String error() {
            return error;
        }
    }
}

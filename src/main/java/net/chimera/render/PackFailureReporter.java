package net.chimera.render;

import net.chimera.ChimeraMod;
import net.chimera.shaderpack.StagedOptions;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes the running game's failure reports (PackFailureReport) from what the pass holds: the
 * versions, the device, and the pack as loaded with its options and conformance report.
 */
final class PackFailureReporter implements PackFailureNotices.Reporter {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private final ChimeraMainPass pass;

    PackFailureReporter(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /**
     * Writes {@code failure}'s report; null when it cannot. It runs inside failure handlers, a post
     * window's among them, so nothing it does may throw out of it.
     */
    @Override
    public Path write(PackFailureNotices.Failure failure) {
        try {
            LocalDateTime now = LocalDateTime.now();
            String text = redact(PackFailureReport.text(failure, failure.cause(), environment(), pack(failure), now));
            return PackFailureReport.write(Minecraft.getInstance().gameDirectory.toPath(), failure.pack(), text, now);
        } catch (IOException | RuntimeException unwritable) {
            LOGGER.warn("[chimera] cannot write the failure report for {}: {}", failure.subject(),
                    unwritable.toString());
            return null;
        }
    }

    @Override
    public String redact(String text) {
        List<String> names = new ArrayList<>();
        names.add(System.getProperty("user.name"));
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getUser() != null) names.add(minecraft.getUser().getName());
        return PackFailureReport.redact(text, List.of(System.getProperty("user.home", "")), names);
    }

    /** The pack the failure struck, as loaded; null when it is not the loaded one (it did not load). */
    private PackFailureReport.Pack pack(PackFailureNotices.Failure failure) {
        Path path = pass.sessions.packPath;
        if (path == null || !PackSessionLifecycle.packLabel(path).equals(failure.pack())) return null;
        StagedOptions options = pass.packSource == null ? null : pass.packSource.options();
        return new PackFailureReport.Pack(failure.pack(), PackFailureReport.hash(path),
                options == null ? "none" : options.profileName(),
                options == null ? Map.of() : options.applied().changes(),
                pass.conformanceReport == null ? "none" : pass.conformanceReport.toJson());
    }

    private static PackFailureReport.Environment environment() {
        Device device = DeviceManager.device;
        return new PackFailureReport.Environment(ChimeraMod.version(), PackFailureReport.commit(),
                modVersion("vulkanmod"), modVersion("neoforge"), modVersion("minecraft"),
                device == null ? "unknown" : device.deviceName + " (" + device.vendorIdString + ")",
                device == null ? "unknown" : device.driverVersion,
                device == null ? "unknown" : device.vkVersion,
                System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                        + System.getProperty("os.arch"),
                System.getProperty("java.version") + " " + System.getProperty("java.vendor"));
    }

    private static String modVersion(String id) {
        return ModList.get() == null ? "unknown" : ModList.get().getModContainerById(id)
                .map(container -> container.getModInfo().getVersion().toString()).orElse("not loaded");
    }
}

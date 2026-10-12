package net.chimera.render;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.ProgramImageBindingManifest;

/**
 * Post bypass regression fixture (DOC-348 repair 3): when pack post cannot
 * run, presentation goes through the host identity pipeline, which never
 * requests pack auxiliary samplers. A missing mask-critical family now makes
 * the whole frame host-rendered (DOC-497 B), so the report carries every
 * program as fallback, not just post.
 */
public final class M87PostBypassHarness {
    private M87PostBypassHarness() {}

    /** Installed pack composite shape: HDR input plus one auxiliary target. */
    private static ProgramImageBindingManifest packComposite() {
        return new ProgramImageBindingManifest(List.of(
                new ProgramImageBindingManifest.Entry("Sampler0", 0, 1, 63,
                        ProgramImageBindingManifest.Kind.COLOR_TARGET, "colortex0", 0, 0, "colortex0"),
                new ProgramImageBindingManifest.Entry("Sampler1", 1, 1, 63,
                        ProgramImageBindingManifest.Kind.COLOR_TARGET, "colortex1", 0, 1, "colortex1")));
    }

    /** Host identity shape: HDR input only, mirroring the renderer seam. */
    private static ProgramImageBindingManifest identityImages() {
        return new ProgramImageBindingManifest(List.of(
                new ProgramImageBindingManifest.Entry("Sampler0", 0, 1, 63,
                        ProgramImageBindingManifest.Kind.COLOR_TARGET, "colortex0", 0, 0, "colortex0")));
    }

    /** Availability snapshot keyed by selector slot; absent means unbound. */
    private static final class SlotStore implements ProgramImageBindingTransaction.Store<String> {
        private final Map<Integer, String> available;
        private final Map<Integer, String> bound = new HashMap<>();

        SlotStore(Map<Integer, String> available) {
            this.available = new HashMap<>(available);
        }

        public String capture(int slot) {
            return available.get(slot);
        }

        public void bind(int slot, String value) {
            bound.put(slot, value);
        }

        public void restore(int slot, String value) {
            if (value == null) bound.remove(slot);
            else bound.put(slot, value);
        }

        public boolean available(String value) {
            return value != null;
        }
    }

    public static void main(String[] args) {
        // Blocked post holds only HDR target 0: binding the installed pack
        // composite must fail exactly the way the shader-load crash did.
        SlotStore hdrOnly = new SlotStore(Map.of(0, "hdr"));
        try (ProgramImageBindingTransaction<String> ignored = ProgramImageBindingTransaction.bind(
                "composite", packComposite(), hdrOnly, entry -> hdrOnly.capture(entry.slot()))) {
            throw new AssertionError("pack composite bound without its auxiliary target");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().startsWith(
                    "RESOURCE_BINDING_UNAVAILABLE:composite:Sampler1:slot=1"),
                    "wrong binding failure: " + expected.getMessage());
        }

        // The bypass presents through identity: Sampler0-only, no pack
        // auxiliary request, binds cleanly on the same HDR-only inputs.
        ProgramImageBindingManifest identity = identityImages();
        assertTrue(identity.entries().stream().allMatch(entry -> entry.slot() == 0),
                "identity manifest requests a non-zero slot: " + identity.entries());
        try (ProgramImageBindingTransaction<String> transaction = ProgramImageBindingTransaction.bind(
                "identity", identity, hdrOnly, entry -> hdrOnly.capture(entry.slot()))) {
            assertTrue(transaction.containsSlot(0), "identity resolve missed HDR slot 0");
        }

        // A missing mask-critical family makes the whole frame host-rendered
        // (DOC-497 B): installed geometry must not keep writing targets that
        // nothing presents, so every program is carried as fallback.
        ConformanceReport report = new ConformanceReport("bypass-fixture", false, List.of(),
                Map.of(), List.of(), List.of());
        report.addProgram(new ConformanceReport.ProgramReport("composite", "post", "glsl",
                List.of("fragment"), Map.of(), List.of("colortex0", "colortex1"),
                List.of(), List.of(0), ConformanceReport.SupportStatus.SUPPORTED,
                ConformanceReport.RuntimeDisposition.INSTALLED, List.of()));
        report.addProgram(new ConformanceReport.ProgramReport("final", "post", "glsl",
                List.of("fragment"), Map.of(), List.of("colortex0"),
                List.of(), List.of(0), ConformanceReport.SupportStatus.SUPPORTED,
                ConformanceReport.RuntimeDisposition.INSTALLED, List.of()));
        report.addProgram(new ConformanceReport.ProgramReport("gbuffers_terrain", "geometry", "glsl",
                List.of("vertex", "fragment"), Map.of(), List.of("texture"),
                List.of(), List.of(0), ConformanceReport.SupportStatus.SUPPORTED,
                ConformanceReport.RuntimeDisposition.INSTALLED, List.of()));
        report.markFrameFallback("PACK_FRAME_UNSUPPORTED:gbuffers_block", "PACK_FRAME_UNSUPPORTED");
        assertTrue(report.deviations().contains("PACK_FRAME_UNSUPPORTED:gbuffers_block"),
                "frame fallback deviation missing: " + report.deviations());
        for (String name : List.of("composite", "final", "gbuffers_terrain")) {
            assertTrue(report.program(name).runtime() == ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK
                            && report.program(name).deviations().contains("PACK_FRAME_UNSUPPORTED"),
                    "half-installed frame left " + name + " active");
        }
        System.out.println("[chimera] m8.7 post bypass conformance: PASS");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package net.chimera.render;

import net.chimera.shaderpack.PackProbe;

import java.nio.file.Path;
import java.util.List;

/** FlipSidesTest's corpus check, for CorpusChecks in the shaderpack package. */
public final class FlipSidesCorpus {
    private FlipSidesCorpus() {}

    /** Plays one corpus pack's sides against Iris's (FlipSidesTest.checkCorpusPack); returns its steps. */
    public static int check(Path pack, PackProbe.Analysis analysis, List<String> failures) {
        return FlipSidesTest.checkCorpusPack(pack, analysis, failures);
    }
}

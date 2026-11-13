package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A skip that changes nothing Iris would draw is expected and stays in debug.log; any other is told
 * (TASK-922). The labels are the ones the report gives the defaults of Complementary, BSL, Shrimple
 * and Photon, which skip only expected programs.
 */
final class SkippedProgramsTest {
    @Test
    void whatThePackOrIrisWouldNotRunIsExpected() {
        assertTrue(SkippedPrograms.expected("composite2", List.of("PROGRAM_DISABLED:composite2")));
        assertTrue(SkippedPrograms.expected("clrwl_gbuffers", List.of("IRIS_PROGRAM_UNUSED:clrwl_gbuffers",
                "PLAN_INELIGIBLE:clrwl_gbuffers")));
        assertTrue(SkippedPrograms.expected("physics_ocean", List.of("IRIS_PROGRAM_UNUSED:physics_ocean")));
        assertTrue(SkippedPrograms.expected("gbuffers_textured_lit", List.of("IRIS_FALLBACK_SOURCE_ONLY",
                "PLAN_INELIGIBLE:gbuffers_textured_lit")));
        assertTrue(SkippedPrograms.expected("gbuffers_terrain_solid",
                List.of("TERRAIN_LAYER_MERGED_BY_HOST:gbuffers_terrain_solid")));
        assertTrue(SkippedPrograms.expected("composite1", List.of("MISSING_FRAGMENT_SOURCE", "NOT_RUNTIME_INSTALLED")));
        assertTrue(SkippedPrograms.expected("dh_terrain", List.of("FAMILY_LANE_UNSUPPORTED:dh_terrain",
                "PLAN_INELIGIBLE:dh_terrain")), "Chimera hosts no Distant Horizons");
        assertTrue(SkippedPrograms.expected("gbuffers_clouds", List.of(PackProgramPlan.CLOUD_AUTHORED_NO_OUTPUT)),
                "a cloud program that draws nothing draws nothing in Iris either");
    }

    @Test
    void aProgramIrisWouldRunIsTold() {
        assertFalse(SkippedPrograms.expected("gbuffers_hand", List.of("CONTRACT_UNSUPPORTED")));
        assertFalse(SkippedPrograms.expected("final", List.of("PACK_PIPELINE_BUILD_FAILED:final")));
        assertFalse(SkippedPrograms.expected("composite98", List.of("PLAN_INELIGIBLE:composite98",
                "TRANSLATION_UNSUPPORTED:x")));
        assertFalse(SkippedPrograms.expected("gbuffers_water", List.of(PackPlanBuilder.PLANNING_FAILED + ":x")));
    }

    @Test
    void theRefusalsSayWhyTheMostTellingFirst() {
        List<String> deviations = List.of("CONTRACT_UNSUPPORTED", "LIVE_UNIFORM_BRIDGE", "PLAN_INELIGIBLE:x",
                "POST_TARGET_SIZE_CONFLICT:colortex4", "SAMPLER_NOT_MAPPED:tex", "PACK_PIPELINE_BUILD_FAILED:x");
        assertEquals(List.of("PACK_PIPELINE_BUILD_FAILED:x", "POST_TARGET_SIZE_CONFLICT:colortex4",
                "CONTRACT_UNSUPPORTED", "PLAN_INELIGIBLE:x"), SkippedPrograms.refusals(deviations));
        assertEquals("PACK_PIPELINE_BUILD_FAILED:x", SkippedPrograms.reason(deviations));
        assertEquals("not installed", SkippedPrograms.reason(List.of("LIVE_UNIFORM_BRIDGE")));
    }
}

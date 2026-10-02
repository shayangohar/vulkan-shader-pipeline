package net.chimera.render.shader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.chimera.shaderpack.BiomeIds;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.LegacyGlslConverter;
import net.chimera.shaderpack.PackExpression;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.PackRuntimeSettings;
import net.chimera.shaderpack.UniformRegistry;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic M7.3 checks for live world-state semantics and custom scalars. */
public final class M73ConformanceHarness {
    private M73ConformanceHarness() {}

    public static void main(String[] args) throws Exception {
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path fixture = fixtureRoot.resolve("m7_3/uniform_semantics");
        Path baselinePath = Path.of(System.getProperty(
                "chimera.m73.baseline", fixtureRoot.resolve("baselines/m7_3.json").toString()));

        verifyCatalog();
        verifyWorldSemantics();
        verifyCelestial();
        verifyDerivedLanguage();
        verifyPreviousMatrixRotation();
        verifyProjectionContract();
        verifyForwardDepthCopyShader();
        verifyEntityProjectionSeparation();
        verifyFixture(fixture, baselinePath);
        System.out.println("[chimera] M7.3 standard uniform and world-state conformance: PASS");
    }

    private static void verifyCatalog() {
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("wetness").availability(), "M7.3 wetness catalog status");
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("eyeBrightnessSmooth").availability(),
                "M7.3 eye smoothing catalog status");
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("dimension").availability(), "M7.3 dimension catalog status");
        assertEquals(UniformRegistry.Availability.DEFAULTED,
                UniformRegistry.descriptor("centerDepthSmooth").availability(),
                "M7.3 depth history remained defaulted");
        assertTrue(UniformRegistry.catalog().stream()
                        .map(UniformRegistry.UniformDescriptor::name).toList().stream().sorted().toList()
                        .equals(UniformRegistry.catalog().stream()
                                .map(UniformRegistry.UniformDescriptor::name).toList()),
                "M7.3 catalog ordering is not deterministic");
    }

    private static void verifyWorldSemantics() {
        assertEquals(0, PackFrameState.irisWorldTimeForTest(12345L, true, false),
                "M7.3 fixed-time world semantics");
        assertEquals(12345, PackFrameState.irisWorldTimeForTest(12345L, true, true),
                "M7.3 Nether/End time semantics");
        assertEquals(3, PackFrameState.irisWorldDayForTest(72000L),
                "M7.3 world day semantics");
        assertEquals(0.007f, PackFrameState.quantizedFrameSecondsForTest(7_250_000L),
                "M7.3 frame time millisecond quantization");
        assertEquals(0.0f, PackFrameState.smooth(0.0f, 1.0f, 600.0f, 200.0f, 0.0f),
                "M7.3 zero-delta smoothing hold");
        assertTrue(PackFrameState.smooth(0.0f, 1.0f, 600.0f, 200.0f, 0.1f) > 0.0f,
                "M7.3 smoothing did not advance");
    }

    /**
     * The celestial frame is one derivation: the pack-facing angles and positions, the up
     * direction, and the shadow light vector must all come from the same host sample, or a shadow
     * caster and a shadow receiver disagree about where the light is.
     *
     * <p>The angles are the host's own sky attribute plus the quarter turn the sky places a body
     * with, so an instance that moves the sky moves the shadows with it. The eye-space positions
     * are that same frame carried by the model view, which is what makes them yaw and pitch
     * invariant as a world direction.
     */
    private static void verifyCelestial() {
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("skyColor").availability(),
                "M7.3 sky colour must be a live host sample, not a default");
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("shadowAngle").availability(),
                "M7.3 shadow angle catalog status");
        assertEquals(UniformRegistry.Availability.LIVE,
                UniformRegistry.descriptor("upPosition").availability(),
                "M7.3 up position catalog status");

        // Four controlled times: the raw host sky angle, the pack-facing angle it becomes, and
        // whether the sun is the body that lights the world.
        assertNear(0.0f, CelestialSnapshot.angle01(-90.0F), 1.0e-6f,
                "M7.3 the bottom of the wrap lands on zero, as the reference carries it");
        assertNear(0.25f, CelestialSnapshot.angle01(0.0f), 1.0e-6f,
                "M7.3 noon is a quarter turn");
        assertNear(0.5f, CelestialSnapshot.angle01(90.0f), 1.0e-6f,
                "M7.3 sunrise is half a turn");
        assertNear(0.75f, CelestialSnapshot.angle01(180.0f), 1.0e-6f,
                "M7.3 midnight is three quarters");
        assertNear(1.0f, CelestialSnapshot.angle01(270.0f), 1.0e-6f,
                "M7.3 the top of the wrap stays at one turn, as the reference carries it");
        assertTrue(CelestialSnapshot.isDay(0.0f) && !CelestialSnapshot.isDay(90.0f)
                        && !CelestialSnapshot.isDay(180.0f) && !CelestialSnapshot.isDay(270.0f),
                "M7.3 day selection must follow the raw sky angle, and hand the light to the moon"
                        + " at the horizon crossing itself");

        // The light each controlled time produces, in the world the shadow matrix is built from.
        org.joml.Vector3f world = new org.joml.Vector3f();
        CelestialSnapshot.lightVector(0.0f, 0.0f, 0.0f, world);
        assertVectorNear(0.0f, 1.0f, 0.0f, world, "M7.3 noon light must be overhead");
        CelestialSnapshot.lightVector(0.0f, 0.0f, 90.0f, world);
        assertVectorNear(-1.0f, 0.0f, 0.0f, world, "M7.3 sunrise light must lie on the horizon");
        CelestialSnapshot.lightVector(0.0f, 0.0f, 270.0f, world);
        assertVectorNear(1.0f, 0.0f, 0.0f, world, "M7.3 sunset light must lie opposite the sunrise");

        // Pack/host agreement: the eye-space position is the world light carried by the view, so
        // the two agree at every controlled time and under any camera yaw and pitch.
        org.joml.Matrix4f modelView = new org.joml.Matrix4f()
                .translate(12.0f, -60.0f, 8.0f)
                .rotateY((float) Math.toRadians(37.0))
                .rotateX((float) Math.toRadians(-18.0));
        org.joml.Matrix4f inverse = new org.joml.Matrix4f(modelView).invert();
        org.joml.Matrix4f turned = new org.joml.Matrix4f()
                .translate(12.0f, -60.0f, 8.0f)
                .rotateY((float) Math.toRadians(190.0))
                .rotateX((float) Math.toRadians(22.0));
        org.joml.Vector3f eye = new org.joml.Vector3f();
        org.joml.Vector3f turnedEye = new org.joml.Vector3f();
        for (float angle : new float[] {0.0f, 90.0f, 180.0f, 270.0f}) {
            CelestialSnapshot.lightVector(0.0f, 0.0f, angle, world);
            CelestialSnapshot.position(modelView, 0.0f, 0.0f, angle, eye);
            CelestialSnapshot.position(turned, 0.0f, 0.0f, angle, turnedEye);
            org.joml.Vector3f roundTrip =
                    inverse.transformPosition(new org.joml.Vector3f(eye)).normalize();
            assertVectorNear(world.x, world.y, world.z, roundTrip,
                    "M7.3 eye-space sun disagreed with the world light at raw " + angle);
            org.joml.Vector3f turnedRoundTrip = new org.joml.Matrix4f(turned).invert()
                    .transformPosition(new org.joml.Vector3f(turnedEye)).normalize();
            assertVectorNear(world.x, world.y, world.z, turnedRoundTrip,
                    "M7.3 a camera rotation moved the world light at raw " + angle);
            assertTrue(eye.distance(turnedEye) > 1.0f,
                    "M7.3 a camera rotation must move the eye-space sun at raw " + angle);
        }

        // The pack's sun path is a spatial turn of the frame, not a phase added to time.
        CelestialSnapshot.lightVector(-40.0f, 0.0f, 0.0f, world);
        assertNear((float) Math.cos(Math.toRadians(40.0)), world.y, 1.0e-4f,
                "M7.3 the pack's sun path must tilt the noon light off the vertical");
        assertNear((float) Math.sin(Math.toRadians(40.0)), world.z, 1.0e-4f,
                "M7.3 the sun path turn must move the light on the axis the reference uses");
        assertNear(1.0f, world.length(), 1.0e-6f, "M7.3 light vector must stay a unit vector");

        PackFrameState state = new PackFrameState();
        state.rotateViewForTest(modelView, forwardProjection(0.05f, 1000.0f, 16.0f / 9.0f));
        state.installSunPath(0.0f, 0.0f);
        state.setCelestialForTest(0.0f, 0.0f, 0x4C7BC8);
        org.joml.Vector3f expectedSun = modelView.transformPosition(
                new org.joml.Vector3f(0.0f, 100.0f, 0.0f));
        org.joml.Vector3f expectedUp = modelView.transformDirection(
                new org.joml.Vector3f(0.0f, 100.0f, 0.0f));
        assertNear(0.25f, writtenFloat(state, "sunAngle"), 1.0e-6f,
                "M7.3 pack sun angle must be the host sky angle plus the quarter turn");
        assertNear(0.25f, writtenFloat(state, "shadowAngle"), 1.0e-6f,
                "M7.3 noon shadow angle must follow the sun");
        verifyWrittenVec3(state, "skyColor",
                new org.joml.Vector3f(0x4C / 255.0f, 0x7B / 255.0f, 0xC8 / 255.0f),
                "M7.3 sky colour must be the host's packed colour");
        verifyWrittenVec3(state, "upPosition", expectedUp,
                "M7.3 up position must be the sky's quarter turn carried by the view");
        verifyWrittenVec3(state, "sunPosition", expectedSun,
                "M7.3 noon sun must sit on the up direction");
        assertNear(1.0f, state.sunLightVector().y, 1.0e-6f,
                "M7.3 noon shadow light must be overhead");

        // Receiver consistency at every controlled time, under a straight path and a turned one:
        // the position the pack reads is the world light the matrix is built from, carried by the
        // view, and the shadow angle follows the body that actually lights the world.
        for (float[] sunPath : new float[][] {{0.0f, 0.0f}, {-40.0f, 4.0f}}) {
            state.installSunPath(sunPath[0], sunPath[1]);
            for (float[] time : new float[][] {{0.0f, 0.0f}, {90.0f, 90.0f},
                    {180.0f, 30.0f}, {270.0f, 270.0f}}) {
                state.setCelestialForTest(time[0], time[1], 0x102030);
                float lightAngle = CelestialSnapshot.isDay(time[0]) ? time[0] : time[1];
                assertNear(CelestialSnapshot.angle01(time[0]), writtenFloat(state, "sunAngle"),
                        1.0e-6f,
                        "M7.3 pack sun angle left the host sky angle at raw " + time[0]);
                assertNear(CelestialSnapshot.angle01(lightAngle), writtenFloat(state, "shadowAngle"),
                        1.0e-6f,
                        "M7.3 shadow angle left the light body at raw " + time[0]);
                org.joml.Vector3f packLight = writtenVec3(state, "shadowLightPosition");
                org.joml.Vector3f carried =
                        inverse.transformPosition(new org.joml.Vector3f(packLight)).normalize();
                assertVectorNear(state.sunLightVector().x, state.sunLightVector().y,
                        state.sunLightVector().z, carried,
                        "M7.3 pack light position left the shadow light at raw " + time[0]);
                // The caster rotation (Iris's construction) looks along the same light. Raw 0 with
                // a straight path is the sun overhead, where a fixed-up look-at had no basis.
                org.joml.Vector3f depthAxis = state.shadowLightRotation()
                        .transformDirection(new org.joml.Vector3f(state.sunLightVector()));
                assertVectorNear(0.0f, 0.0f, 1.0f, depthAxis,
                        "M7.3 shadow caster rotation left the shadow light at raw " + time[0]);
            }
        }
    }

    private static float writtenFloat(PackFrameState state, String name) {
        UniformRegistry.UniformDescriptor descriptor = UniformRegistry.descriptor(name, "float");
        assertTrue(descriptor != null, "M7.3 scalar uniform is missing from the catalog: " + name);
        MappedBuffer buffer = new MappedBuffer(4);
        state.write(descriptor, "float", buffer);
        return buffer.getFloat(0);
    }

    private static org.joml.Vector3f writtenVec3(PackFrameState state, String name) {
        UniformRegistry.UniformDescriptor descriptor = UniformRegistry.descriptor(name, "vec3");
        assertTrue(descriptor != null, "M7.3 vector uniform is missing from the catalog: " + name);
        MappedBuffer buffer = new MappedBuffer(16);
        state.write(descriptor, "vec3", buffer);
        return new org.joml.Vector3f(buffer.getFloat(0), buffer.getFloat(4), buffer.getFloat(8));
    }

    private static void verifyWrittenVec3(PackFrameState state, String name,
                                          org.joml.Vector3f expected, String message) {
        assertVectorNear(expected.x, expected.y, expected.z, writtenVec3(state, name), message);
    }

    private static void assertVectorNear(float x, float y, float z, org.joml.Vector3f actual,
                                         String message) {
        assertNear(x, actual.x, 1.0e-4f, message + " at component 0");
        assertNear(y, actual.y, 1.0e-4f, message + " at component 1");
        assertNear(z, actual.z, 1.0e-4f, message + " at component 2");
    }

    /**
     * The pack-authored language at the values the pinned packs depend on. Complementary's
     * {@code eyeBrightnessM} smooths the raw sky brightness over its full nought-to-two-hundred-
     * and-forty range and its cave factor clamps against the result, so a synthetic fifteen put
     * that clamp outside its own domain; BSL's {@code timeBrightness} is a half-rectified sine of
     * the raw world time and its {@code timeAngle} is the raw time over the day.
     */
    private static void verifyDerivedLanguage() {
        String caveFactor = "smooth(4, eyeBrightness.y / 240.0, 5, 5)";
        assertNear(0.0f, (float) smoothedValue(caveFactor, 0.0, 1, 0.05f), 1.0e-6f,
                "M7.3 dark skylight must give a nought cave factor");
        assertNear(1.0f, (float) smoothedValue(caveFactor, 240.0, 1, 0.05f), 1.0e-6f,
                "M7.3 full skylight must give one");

        PackExpression.Program program = PackExpression.parse(caveFactor, BiomeIds.constants());
        PackExpression.State state = program.newState();
        program.evaluate((name, component) -> 0.0, state, 0.05f);
        double rising = program.evaluate((name, component) -> 240.0, state, 0.05f);
        assertTrue(rising > 0.0 && rising < 1.0,
                "M7.3 a smoothed value must move towards its target rather than jump: " + rising);
        for (int frame = 0; frame < 60; frame++) {
            double value = program.evaluate((name, component) -> 240.0, state, 0.05f);
            assertTrue(value >= rising && value <= 1.0,
                    "M7.3 a smoothed value must stay bounded and rising at frame " + frame);
            rising = value;
        }

        // Two declarations under one id keep their own accumulators, which Complementary needs:
        // eyeBrightnessM and eyeBrightnessM2 both smooth under id 4 with different half lives.
        PackExpression.Program fast = PackExpression.parse(
                "smooth(4, eyeBrightness.y / 240.0, 2, 2)", BiomeIds.constants());
        PackExpression.Program slow = PackExpression.parse(
                "smooth(4, eyeBrightness.y / 240.0, 20, 20)", BiomeIds.constants());
        PackExpression.State fastState = fast.newState();
        PackExpression.State slowState = slow.newState();
        fast.evaluate((name, component) -> 0.0, fastState, 0.05f);
        slow.evaluate((name, component) -> 0.0, slowState, 0.05f);
        double fastRise = fast.evaluate((name, component) -> 240.0, fastState, 0.05f);
        double slowRise = slow.evaluate((name, component) -> 240.0, slowState, 0.05f);
        assertTrue(fastRise > slowRise,
                "M7.3 two call sites under one smoothing id must not share an accumulator");

        // BSL: timeAngle is the raw world time over the day, so noon is a quarter and midnight
        // is three quarters; timeBrightness is the half-rectified sine of its turn.
        assertNear(0.0f, (float) smoothedValue("worldTime / 24000", 0.0, 1, 0.05f), 1.0e-6f,
                "M7.3 sunrise time angle");
        assertEquals(0.25f, (float) smoothedValue("worldTime / 24000", 6000.0, 1, 0.05f), "M7.3 noon time angle");
        assertEquals(0.5f, (float) smoothedValue("worldTime / 24000", 12000.0, 1, 0.05f), "M7.3 sunset time angle");
        assertEquals(0.75f, (float) smoothedValue("worldTime / 24000", 18000.0, 1, 0.05f), "M7.3 midnight time angle");
        String brightness = "max(sin(worldTime / 24000 * 6.28318530718), 0.0)";
        assertNear(0.0f, (float) smoothedValue(brightness, 0.0, 1, 0.05f), 1.0e-4f,
                "M7.3 time brightness at sunrise");
        assertNear(1.0f, (float) smoothedValue(brightness, 6000.0, 1, 0.05f), 1.0e-4f,
                "M7.3 time brightness at noon");
        assertNear(0.0f, (float) smoothedValue(brightness, 12000.0, 1, 0.05f), 1.0e-4f,
                "M7.3 time brightness at sunset");
        assertNear(0.0f, (float) smoothedValue(brightness, 18000.0, 1, 0.05f), 1.0e-4f,
                "M7.3 time brightness at midnight");

        // The named biome constants, the biome input they are compared against, and the math the
        // packs reach for, all in one expression.
        String biomeTest = "if(in(biome, BIOME_GROVE, BIOME_DESERT), 1, 0) * clamp(sqrt(16.0), 0.0, 3.0)";
        PackExpression.Program biome = PackExpression.parse(biomeTest, BiomeIds.constants());
        PackExpression.State biomeState = biome.newState();
        int desert = BiomeIds.id(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                "minecraft", "desert"));
        assertNear(3.0f, (float) biome.evaluate((name, component) -> desert, biomeState, 0.05f),
                1.0e-6f, "M7.3 a named biome constant must match the biome it names");
        assertNear(0.0f, (float) biome.evaluate((name, component) -> 1.0, biomeState, 0.05f),
                1.0e-6f, "M7.3 a biome outside the constants must not match one");
        assertTrue(BiomeIds.id(null) == BiomeIds.UNKNOWN
                        && BiomeIds.id(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                        "other", "desert")) == BiomeIds.UNKNOWN,
                "M7.3 an unlisted or foreign biome must match no constant");

        assertRejected("sinh(frameTime)", "CUSTOM_EXPRESSION_UNKNOWN_FUNCTION");
        assertRejected("smooth(1, 2)", "CUSTOM_EXPRESSION_ARITY");
        assertRejected("frameTime +", "CUSTOM_EXPRESSION_UNSUPPORTED");
    }

    /** One frame of a smoothing chain, for the values the packs write. */
    private static double smoothedValue(String source, double input, int frames, float delta) {
        PackExpression.Program program = PackExpression.parse(source, BiomeIds.constants());
        PackExpression.State state = program.newState();
        double value = 0.0;
        for (int frame = 0; frame < frames; frame++) {
            value = program.evaluate((name, component) -> input, state, delta);
        }
        return value;
    }

    private static void assertRejected(String source, String code) {
        try {
            PackExpression.parse(source, BiomeIds.constants());
            throw new AssertionError("M7.3 expression was accepted: " + source);
        } catch (PackExpression.Unsupported failure) {
            assertEquals(code, failure.code(), "M7.3 refusal code for " + source);
        }
    }

    /**
     * Previous-frame matrices must track the previous presented world
     * frame: the first frame seeds previous from current, later frames
     * shift current into previous, a stationary camera converges, and a
     * fresh session restarts the sequence instead of leaking old matrices.
     */
    private static void verifyPreviousMatrixRotation() {
        org.joml.Matrix4f firstModel = new org.joml.Matrix4f().translate(2.0f, -30.0f, 5.0f);
        org.joml.Matrix4f firstProjection = forwardProjection(0.05f, 1000.0f, 16.0f / 9.0f);
        org.joml.Matrix4f secondModel = new org.joml.Matrix4f().translate(2.5f, -30.0f, 5.0f);
        org.joml.Matrix4f secondProjection = forwardProjection(0.05f, 1000.0f, 16.0f / 9.0f);
        secondProjection.m20(0.001f);

        org.joml.Matrix4f firstPackProjection = legacyProjection(firstProjection);
        org.joml.Matrix4f secondPackProjection = legacyProjection(secondProjection);
        PackFrameState state = new PackFrameState();
        state.rotateViewForTest(firstModel, firstProjection);
        assertMatrixNear(firstModel, state.modelViewForTest(), "M7.3 first-frame model view");
        assertMatrixNear(firstProjection, state.rasterProjectionForTest(),
                "M7.3 host raster projection must remain unchanged");
        assertMatrixNear(firstPackProjection, state.projectionForTest(),
                "M7.3 first-frame pack projection");
        assertMatrixNear(firstModel, state.previousModelViewForTest(),
                "M7.3 first frame must seed previous model view from current");
        assertMatrixNear(firstPackProjection, state.previousProjectionForTest(),
                "M7.3 first frame must seed previous pack projection from current");
        assertMatrixNear(new org.joml.Matrix4f(firstProjection).mul(firstModel), state.mvpForTest(),
                "M7.3 host MVP must use the unconverted raster projection");

        state.rotateViewForTest(secondModel, secondProjection);
        assertMatrixNear(secondModel, state.modelViewForTest(), "M7.3 second-frame model view");
        assertMatrixNear(firstModel, state.previousModelViewForTest(),
                "M7.3 previous model view must be the first frame, not the current one");
        assertMatrixNear(firstPackProjection, state.previousProjectionForTest(),
                "M7.3 previous pack projection must be the first frame, not the current one");
        assertMatrixNear(secondPackProjection, state.projectionForTest(),
                "M7.3 current pack projection must be converted once");
        // DHCompat without Distant Horizons serves the gbuffer projection and its derivations.
        for (String[] pair : new String[][] {{"dhProjection", "gbufferProjection"},
                {"dhProjectionInverse", "gbufferProjectionInverse"},
                {"dhPreviousProjection", "gbufferPreviousProjection"}}) {
            MappedBuffer dh = new MappedBuffer(64);
            MappedBuffer gbuffer = new MappedBuffer(64);
            state.write(UniformRegistry.descriptor(pair[0], "mat4"), "mat4", dh);
            state.write(UniformRegistry.descriptor(pair[1], "mat4"), "mat4", gbuffer);
            for (int i = 0; i < 16; i++) {
                assertNear(gbuffer.getFloat(i * 4), dh.getFloat(i * 4), 0.0f,
                        "M7.3 " + pair[0] + " must equal " + pair[1] + " without Distant Horizons");
            }
        }

        state.rotateViewForTest(secondModel, secondProjection);
        assertMatrixNear(secondModel, state.previousModelViewForTest(),
                "M7.3 stationary camera must converge previous model view");
        assertMatrixNear(secondPackProjection, state.previousProjectionForTest(),
                "M7.3 stationary camera must converge previous pack projection");

        PackFrameState reloaded = new PackFrameState();
        reloaded.rotateViewForTest(secondModel, secondProjection);
        assertMatrixNear(secondModel, reloaded.previousModelViewForTest(),
                "M7.3 reloaded session must restart previous model view from current");
        assertMatrixNear(secondPackProjection, reloaded.previousProjectionForTest(),
                "M7.3 reloaded session must restart previous pack projection from current");

        PackFrameState delayedCapture = new PackFrameState();
        delayedCapture.rotateViewForTest(null, null);
        delayedCapture.rotateViewForTest(secondModel, secondProjection);
        assertMatrixNear(secondModel, delayedCapture.previousModelViewForTest(),
                "M7.3 missing initial capture must not seed history with identity");
        assertMatrixNear(secondPackProjection, delayedCapture.previousProjectionForTest(),
                "M7.3 first valid projection must seed its own history");

        PackFrameState resized = new PackFrameState();
        assertTrue(!resized.updateViewport(2560, 1440),
                "M7.3 initial viewport must not count as a resize");
        resized.rotateViewForTest(firstModel, firstProjection);
        resized.rotateViewForTest(secondModel, secondProjection);
        assertTrue(resized.updateViewport(1920, 1080),
                "M7.3 viewport size change was not detected");
        resized.rotateViewForTest(firstModel, firstProjection);
        assertMatrixNear(firstModel, resized.previousModelViewForTest(),
                "M7.3 resize must seed previous model view from the resized frame");
        assertMatrixNear(firstPackProjection, resized.previousProjectionForTest(),
                "M7.3 resize must seed previous projection from the resized frame");
        assertTrue(!resized.updateViewport(1920, 1080),
                "M7.3 stable viewport was incorrectly treated as another resize");

        PackFrameState camera = new PackFrameState();
        camera.advanceCameraForTest(30_001.0, 64.0, -12.0, false);
        camera.advanceCameraForTest(30_002.0, 64.25, -12.5, true);
        MappedBuffer currentCamera = new MappedBuffer(16);
        MappedBuffer previousCamera = new MappedBuffer(16);
        camera.write(UniformRegistry.descriptor("cameraPosition", "vec3"), "vec3", currentCamera);
        camera.write(UniformRegistry.descriptor("previousCameraPosition", "vec3"),
                "vec3", previousCamera);
        for (int index = 0; index < 3; index++) {
            assertNear(currentCamera.getFloat(index * 4), previousCamera.getFloat(index * 4), 1.0e-6f,
                    "M7.3 resize must reseed camera-origin history with the projection history");
        }
    }

    private static void verifyProjectionContract() {
        float[][] cases = {
                {0.05f, 256.0f, 16.0f / 9.0f},
                {0.10f, 2048.0f, 4.0f / 3.0f},
                {0.25f, 8192.0f, 21.0f / 9.0f}
        };
        float[][] points = {
                {0.0f, 0.0f, -0.5f},
                {0.2f, -0.1f, -4.0f},
                {-1.5f, 0.8f, -32.0f},
                {8.0f, -4.0f, -200.0f}
        };
        for (float[] values : cases) {
            org.joml.Matrix4f rendered = forwardProjection(values[0], values[1], values[2]);
            org.joml.Vector4f nearClip = transform(rendered, 0.0f, 0.0f, -values[0]);
            org.joml.Vector4f farClip = transform(rendered, 0.0f, 0.0f, -values[1]);
            assertNear(0.0f, nearClip.z / nearClip.w, 2.0e-5f,
                    "M8.7 host forward-Z near plane must map to zero");
            assertNear(1.0f, farClip.z / farClip.w, 2.0e-5f,
                    "M8.7 host forward-Z far plane must map to one");
            rendered.m20(0.003f).m21(-0.002f);
            // Captured projections can include the camera's bob/tilt. The
            // conversion must preserve these terms instead of rebuilding FOV.
            rendered.mul(new org.joml.Matrix4f().rotateX(0.009f).rotateZ(-0.004f));

            PackFrameState state = new PackFrameState();
            state.rotateViewForTest(new org.joml.Matrix4f(), rendered);
            org.joml.Matrix4f packProjection = state.projectionForTest();
            org.joml.Matrix4f packInverse = state.projectionInverseForTest();
            assertMatrixNear(rendered, state.rasterProjectionForTest(),
                    "M7.3 projection conversion changed the host raster matrix");

            for (float[] point : points) {
                org.joml.Vector4f hostClip = transform(rendered, point[0], point[1], point[2]);
                org.joml.Vector4f packClip = transform(packProjection, point[0], point[1], point[2]);
                float hostDepth = hostClip.z / hostClip.w;
                float packDepth = packClip.z / packClip.w;
                assertNear(2.0f * hostDepth - 1.0f, packDepth, 2.0e-5f,
                        "M7.3 pack clip depth must match converted depthtex value");

                float u = 0.5f * (hostClip.x / hostClip.w + 1.0f);
                float v = 0.5f * (hostClip.y / hostClip.w + 1.0f);
                float depthTex = hostDepth;
                org.joml.Vector4f reconstructed = new org.joml.Vector4f(
                        2.0f * u - 1.0f, 2.0f * v - 1.0f, 2.0f * depthTex - 1.0f, 1.0f);
                packInverse.transform(reconstructed);
                divideByW(reconstructed);
                float depthPrecisionTolerance = Math.max(3.0e-3f,
                        Math.abs(point[2]) * 5.0e-4f);
                assertNear(point[0], reconstructed.x, depthPrecisionTolerance,
                "M8.7 pack inverse must reconstruct view X from copied forward depth");
                assertNear(point[1], reconstructed.y, depthPrecisionTolerance,
                "M8.7 pack inverse must reconstruct view Y from copied forward depth");
                assertNear(point[2], reconstructed.z, depthPrecisionTolerance,
                "M8.7 pack inverse must reconstruct view Z from copied forward depth");
            }
        }

        verifyFiniteBoundedReprojection();

        PackFrameState state = new PackFrameState();
        org.joml.Matrix4f hostProjection = forwardProjection(0.05f, 512.0f, 16.0f / 9.0f);
        state.rotateViewForTest(new org.joml.Matrix4f(), hostProjection);
        verifyWrittenMatrix(state, "gbufferProjection", legacyProjection(hostProjection));
        verifyWrittenMatrix(state, "ProjMat", hostProjection);
    }

    private static void verifyFiniteBoundedReprojection() {
        org.joml.Matrix4f hostProjection = forwardProjection(0.05f, 512.0f, 16.0f / 9.0f);
        org.joml.Matrix4f previousModelView = new org.joml.Matrix4f();
        org.joml.Matrix4f currentModelView = new org.joml.Matrix4f().translation(-0.025f, 0.01f, 0.0f);
        PackFrameState state = new PackFrameState();
        state.rotateViewForTest(previousModelView, hostProjection);
        state.rotateViewForTest(currentModelView, hostProjection);

        float[] worldPoint = {0.12f, 0.08f, -5.0f};
        org.joml.Vector4f currentView = transform(currentModelView,
                worldPoint[0], worldPoint[1], worldPoint[2]);
        org.joml.Vector4f hostClip = new org.joml.Vector4f(currentView);
        hostProjection.transform(hostClip);
        float hostDepth = hostClip.z / hostClip.w;
        org.joml.Vector4f currentClip = new org.joml.Vector4f(
                hostClip.x / hostClip.w,
                hostClip.y / hostClip.w,
                2.0f * hostDepth - 1.0f,
                1.0f);
        state.projectionInverseForTest().transform(currentClip);
        divideByW(currentClip);

        org.joml.Matrix4f inverseCurrentModelView = new org.joml.Matrix4f(currentModelView).invert();
        inverseCurrentModelView.transform(currentClip);
        divideByW(currentClip);
        previousModelView.transform(currentClip);
        org.joml.Vector4f previousClip = state.previousProjectionForTest().transform(
                new org.joml.Vector4f(currentClip));
        float previousU = 0.5f * (previousClip.x / previousClip.w + 1.0f);
        float previousV = 0.5f * (previousClip.y / previousClip.w + 1.0f);
        assertTrue(Float.isFinite(previousU) && Float.isFinite(previousV),
                "M7.3 camera motion produced non-finite reprojection UVs");
        assertTrue(previousU >= 0.0f && previousU <= 1.0f
                        && previousV >= 0.0f && previousV <= 1.0f,
                "M7.3 camera motion produced out-of-bounds reprojection UVs");
        assertTrue(Math.abs(previousU - 0.5f) < 0.05f && Math.abs(previousV - 0.5f) < 0.05f,
                "M7.3 small camera translation produced an unexpectedly large reprojection shift");
    }

    private static void verifyEntityProjectionSeparation() {
        String vertex = """
                #version 120
                uniform mat4 gbufferProjection;
                uniform mat4 gbufferProjectionInverse;
                varying vec2 texCoord;
                void main() {
                    vec4 position = gbufferProjection * gl_Vertex;
                    gl_Position = gl_ProjectionMatrix * gl_Vertex;
                    texCoord = (gbufferProjectionInverse * position).xy;
                }
                """;
        String fragment = """
                #version 120
                varying vec2 texCoord;
                void main() { gl_FragColor = vec4(texCoord, 0.0, 1.0); }
                """;
        var uniforms = List.of(
                new UniformRegistry.UniformDeclaration("gbufferProjection", "mat4"),
                new UniformRegistry.UniformDeclaration("gbufferProjectionInverse", "mat4"));
        LegacyGlslConverter.TerrainVertexConversion converted =
                LegacyGlslConverter.convertEntityVertexChecked(
                        vertex, null, fragment, Map.of(), false, uniforms);
        assertTrue(converted != null, "M7.3 entity projection bridge was rejected");
        assertTrue(converted.source().contains("mat4 gbufferProjection;"),
                "M7.3 entity shader lost the pack projection uniform");
        assertTrue(converted.source().contains("mat4 gbufferProjectionInverse;"),
                "M7.3 entity shader lost the pack inverse projection uniform");
        assertTrue(converted.source().contains("ProjMat * chimeraEntityVertexValue()"),
                "M7.3 host gl_ProjectionMatrix no longer uses the raster projection");
        assertTrue(converted.source().contains("gbufferProjection * chimeraEntityVertexValue()"),
                "M7.3 pack gbufferProjection was incorrectly aliased to host ProjMat");
        assertTrue(!converted.source().contains("chimeraEntityInverseProjection()"),
                "M7.3 pack inverse projection was incorrectly derived from host ProjMat");
    }

    private static org.joml.Matrix4f forwardProjection(float near, float far, float aspect) {
        float scale = 1.0f / (float) Math.tan(Math.toRadians(70.0) * 0.5);
        org.joml.Matrix4f projection = new org.joml.Matrix4f().zero();
        projection.m00(scale / aspect);
        projection.m11(scale);
        projection.m22(far / (near - far));
        projection.m32(near * far / (near - far));
        projection.m23(-1.0f);
        return projection;
    }

    private static org.joml.Matrix4f legacyProjection(org.joml.Matrix4f rendered) {
        org.joml.Matrix4f converted = new org.joml.Matrix4f(rendered);
        converted.m02(2.0f * rendered.m02() - rendered.m03());
        converted.m12(2.0f * rendered.m12() - rendered.m13());
        converted.m22(2.0f * rendered.m22() - rendered.m23());
        converted.m32(2.0f * rendered.m32() - rendered.m33());
        return converted;
    }

    private static void verifyForwardDepthCopyShader() throws Exception {
        String shader = Files.readString(Path.of(
                "src/main/resources/assets/chimera/shaders/chimera_depth/chimera_depth.fsh"),
                StandardCharsets.UTF_8);
        assertTrue(shader.contains("outDepth = vec4(depth, 0.0, 0.0, 1.0)"),
                "M8.7 pack depth snapshot must preserve forward host depth");
        assertTrue(!shader.contains("1.0 - depth"),
                "M8.7 depth snapshot still inverts forward host depth");
    }

    private static org.joml.Vector4f transform(org.joml.Matrix4f matrix, float x, float y, float z) {
        org.joml.Vector4f vector = new org.joml.Vector4f(x, y, z, 1.0f);
        matrix.transform(vector);
        return vector;
    }

    private static void divideByW(org.joml.Vector4f vector) {
        assertTrue(Float.isFinite(vector.w) && Math.abs(vector.w) > 1.0e-7f,
                "M7.3 homogeneous transform reached a non-finite or singular w");
        vector.div(vector.w);
    }

    private static void verifyWrittenMatrix(PackFrameState state, String name,
                                            org.joml.Matrix4f expected) {
        UniformRegistry.UniformDescriptor descriptor = UniformRegistry.descriptor(name, "mat4");
        assertTrue(descriptor != null, "M7.3 matrix uniform is missing from the catalog: " + name);
        MappedBuffer buffer = new MappedBuffer(64);
        state.write(descriptor, "mat4", buffer);
        float[] expectedValues = new float[16];
        expected.get(expectedValues);
        for (int index = 0; index < expectedValues.length; index++) {
            assertNear(expectedValues[index], buffer.getFloat(index * 4), 1.0e-6f,
                    "M7.3 matrix uniform routed to the wrong projection: " + name + "[" + index + "]");
        }
    }

    private static void assertMatrixNear(org.joml.Matrix4f expected, org.joml.Matrix4f actual,
                                         String message) {
        float[] expectedValues = new float[16];
        float[] actualValues = new float[16];
        expected.get(expectedValues);
        actual.get(actualValues);
        for (int index = 0; index < expectedValues.length; index++) {
            assertNear(expectedValues[index], actualValues[index], 1.0e-6f,
                    message + " at matrix element " + index);
        }
    }

    private static void assertNear(float expected, float actual, float epsilon, String message) {
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void verifyFixture(Path fixture, Path baselinePath) throws Exception {
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        assertEquals(first.report().toJson(), second.report().toJson(),
                "M7.3 report stability");

        PackRuntimeSettings settings = first.plan().runtimeSettings();
        assertTrue(settings.customDescriptors().containsKey("m73Marker"),
                "M7.3 exposed custom uniform was not planned");
        assertTrue(!settings.customDescriptors().containsKey("m73CycleA"),
                "M7.3 invalid cycle became executable");
        assertTrue(settings.deviations().contains("CUSTOM_VALUE_CYCLE:m73CycleA"),
                "M7.3 cycle was not reported");
        assertTrue(settings.deviations().contains("CUSTOM_EXPRESSION_UNKNOWN:m73Unknown:missingM73Value"),
                "M7.3 unknown custom dependency was not reported");
        assertEquals(300.0f, settings.wetnessRiseHalfLife(),
                "M7.3 pack wetness half-life");
        assertEquals(20.0f, settings.eyeBrightnessHalfLife(),
                "M7.3 pack eye brightness half-life");

        PackRuntimeSettings.Session session = settings.newSession();
        settings.evaluate((name, component) -> name.equals("frameTimeCounter") ? 1.0 : 0.25,
                session, 0.05f);
        int marker = settings.indexOf("m73Marker");
        assertTrue(marker >= 0 && session.values()[marker] > 2.0f
                        && session.values()[marker] < 2.5f,
                "M7.3 custom dependency evaluation was incorrect");
        int derived = settings.indexOf("m73Derived");
        assertNear(6.25f, session.values()[derived], 1.0e-4f,
                "M7.3 derived value must be the smoothing, the biome test, the root and the "
                        + "continued declaration together");
        int component = settings.indexOf("m73Component");
        assertTrue(component >= 0, "M7.3 component reference was not evaluated");
        int nonFinite = settings.indexOf("m73NonFinite");
        assertTrue(nonFinite >= 0 && session.failures().containsKey("m73NonFinite"),
                "M7.3 a non-finite derived value was not reported");
        assertTrue(session.failures().get("m73NonFinite").contains("sqrt(0.0 - 1.0)"),
                "M7.3 the non-finite report must name the expression that produced it");
        assertTrue(session.failures().get("m73NonFinite").contains("frame=1"),
                "M7.3 the non-finite report must carry the frame context");
        assertTrue(Float.isFinite(session.values()[nonFinite]),
                "M7.3 a non-finite derived value must hold a finite result rather than a NaN");
        assertTrue(settings.deviations().contains(
                        "CUSTOM_EXPRESSION_UNKNOWN_FUNCTION:m73BadFunction:sinh"),
                "M7.3 unknown function was not reported");
        assertTrue(settings.deviations().contains("CUSTOM_EXPRESSION_ARITY:m73BadArity:smooth"),
                "M7.3 wrong arity was not reported");
        assertTrue(settings.deviations().contains(
                        "CUSTOM_EXPRESSION_COMPONENT:m73BadComponent:frameTime.x"),
                "M7.3 component of a scalar was not reported");
        assertTrue(settings.deviations().contains(
                        "CUSTOM_EXPRESSION_UNKNOWN:m73BadConstant:BIOME_NOT_A_BIOME"),
                "M7.3 unknown constant was not reported");
        assertEquals(UniformRegistry.Availability.REJECTED,
                settings.customDescriptors().get("m73BadFunction").availability(),
                "M7.3 a refused declaration must be refused rather than served");
        assertEquals(UniformRegistry.Availability.REJECTED,
                settings.customDescriptors().get("m73BadConstant").availability(),
                "M7.3 a declaration with an unresolved reference must be refused");
        assertTrue(settings.rejected().contains("m73BadArity"),
                "M7.3 refused declaration was not recorded as rejected");

        PackProgramPlan composite = first.plan().program("composite");
        assertTrue(composite != null && composite.executable(),
                "M7.3 fixture composite was not executable");
        assertTrue(composite.interfacePlan().uniforms().stream()
                        .anyMatch(value -> value.name().equals("m73Marker")),
                "M7.3 custom uniform was not in the shared interface plan");
        assertTrue(composite.interfacePlan().deviations().contains("CUSTOM_UNIFORM_BRIDGE:m73Marker"),
                "M7.3 custom uniform bridge deviation was not recorded");

        PackUniformProvider.resetSession();
        PackUniformProvider.installRuntimeSettings(settings);
        Uniform.Info info = Uniform.createUniformInfo("float", "m73Marker");
        MappedBuffer firstBuffer = PackUniformProvider.shared().supplier(info).get();
        MappedBuffer secondBuffer = PackUniformProvider.shared().supplier(info).get();
        assertTrue(firstBuffer == secondBuffer, "M7.3 custom uniform buffer was recreated");
        PackUniformProvider.resetSession();

        verifyBaseline(first, baselinePath);
    }

    private static void verifyBaseline(PackProbe.Analysis analysis, Path baselinePath) throws Exception {
        JsonObject baseline = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        if ("TO_BE_FILLED".equals(baseline.get("reportSha256").getAsString())) {
            System.out.println("[chimera] M7.3 report hash: " + analysis.report().sha256());
            System.out.println("[chimera] M7.3 source hashes: " + sourceHashes(analysis.report()));
            System.out.println("[chimera] M7.3 settings deviations: "
                    + analysis.plan().runtimeSettings().deviations());
            return;
        }
        assertEquals(baseline.get("reportSha256").getAsString(), analysis.report().sha256(),
                "M7.3 report hash");
        Map<String, String> expected = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry
                : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expected.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expected, sourceHashes(analysis.report()), "M7.3 source hashes");
        assertEquals(strings(baseline.getAsJsonArray("expectedCustomUniforms")),
                analysis.plan().runtimeSettings().customDescriptors().entrySet().stream()
                        .filter(entry -> entry.getValue().availability()
                                == UniformRegistry.Availability.LIVE)
                        .map(java.util.Map.Entry::getKey).sorted().toList(),
                "M7.3 served custom uniform baseline");
        assertEquals(strings(baseline.getAsJsonArray("expectedSettingsDeviations")),
                analysis.plan().runtimeSettings().deviations(),
                "M7.3 settings deviation baseline");
        assertEquals(strings(baseline.getAsJsonArray("expectedExecutablePrograms")),
                analysis.report().programs().stream()
                        .filter(value -> value.support() == ConformanceReport.SupportStatus.SUPPORTED
                                || value.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION)
                        .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                "M7.3 executable program baseline");
        assertEquals(strings(baseline.getAsJsonArray("expectedFallbackPrograms")),
                analysis.report().programs().stream()
                        .filter(value -> value.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK)
                        .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                "M7.3 fallback program baseline");
    }

    private static Map<String, String> sourceHashes(ConformanceReport report) {
        Map<String, String> result = new TreeMap<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            program.sourceHashes().forEach(result::putIfAbsent);
        }
        return result;
    }

    private static List<String> strings(Iterable<JsonElement> values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsString()));
        return result;
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}

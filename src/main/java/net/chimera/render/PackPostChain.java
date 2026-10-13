package net.chimera.render;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraPostPipelines;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.PackCoveragePlan;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PackProgram;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.TargetStep;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Runs the pack's post programs in each frame window: the composite and deferred steps of the
 * target graph, the final pass before the hand, and the identity boundaries that keep the frame
 * presentable when a step cannot run.
 */
final class PackPostChain {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private final ChimeraMainPass pass;

    /** All discovered post programs, including static fallback stages. */
    final List<PackPostExecution> packPostExecution = new ArrayList<>();

    /** Identity-boundary diagnostics are emitted once per pack session. */
    final Set<String> packPostBoundaryLogged = new HashSet<>();
    PackPostExecution packFinalExecution;

    /** Debug NaN detector over pack targets; idle unless enabled. */
    final PackNanTripwire nanTripwire = new PackNanTripwire();

    PackPostChain(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /** Immutable post-stage order and output contract retained for the pack session. */
    record PackPostExecution(
            String name,
            List<Integer> outputTargets,
            PackPipelines.PackPost post,
            boolean finalStage
    ) {
        PackPostExecution {
            outputTargets = outputTargets == null
                    ? List.of()
                    : outputTargets.stream().distinct().sorted().toList();
        }
    }

    /**
     * Debug: scans every committed colortex, its history side, and the float depth copies, so
     * the first dirty check after a clean one names where a NaN entered. Also snapshots the
     * frame's uniforms once.
     */
    void sweepNanTripwire(VkCommandBuffer commandBuffer, String when) {
        if (!PackNanTripwire.enabled()) return;
        this.nanTripwire.checkUniforms();
        for (int target = 0; target <= PackTargetGraphPlan.MAX_TARGET; target++) {
            VulkanImage active = pass.packPostTargets.activeTarget(target);
            this.nanTripwire.check(commandBuffer, when + ": colortex" + target, active);
            VulkanImage history = pass.packPostTargets.previousTarget(target);
            if (history != null && history != active) {
                this.nanTripwire.check(commandBuffer, when + ": colortex" + target + " history", history);
            }
        }
        if (pass.packDepthTargets.isConfigured()) {
            for (String name : List.of("depthtex0", "depthtex1", "depthtex2")) {
                if (pass.packDepthTargets.currentAvailable(name)) {
                    this.nanTripwire.check(commandBuffer, when + ": " + name, pass.packDepthTargets.image(name));
                }
            }
        }
    }

    /** Debug: scans what one post stage just wrote. */
    void checkNanTripwireOutputs(VkCommandBuffer commandBuffer, PackPostExecution stage) {
        if (!PackNanTripwire.enabled()) return;
        for (int target : stage.outputTargets()) {
            this.nanTripwire.check(commandBuffer, stage.name() + " -> colortex" + target,
                    pass.packPostTargets.activeTarget(target));
        }
    }

    /** Opens the frame's target executor and NaN tripwire, and scene coverage when it runs. */
    void beginPostFrame(VkCommandBuffer commandBuffer) {
        VulkanImage hdrColor = pass.targets.hdrFramebuffer.getColorAttachment();
        pass.packPostTargets.beginFrame(commandBuffer, hdrColor);
        pass.packPostFrameStarted = true;
        this.nanTripwire.beginFrame(commandBuffer,
                pass.sessions.packPath == null ? "?" : String.valueOf(pass.sessions.packPath.getFileName()));
        if (pass.coverage.packCoverageRuntimeEnabled() && pass.packPostChainActive) {
            pass.packPostTargets.prepareGeometryTarget(commandBuffer);
            pass.coverage.packCoverageOwner.beginFrame(commandBuffer);
            pass.coverage.packCoverageState.beginFrame();
        }
    }

    /** Runs the PREPARE window over the freshly cleared world target, then reopens it. */
    void runPrepareWindow(VkCommandBuffer commandBuffer) {
        Renderer.getInstance().endRenderPass(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage hdrColor = pass.targets.hdrFramebuffer.getColorAttachment();
            hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            runPackPostWindow(commandBuffer, hdrColor, PackFrameSchedulePlan.PostWindow.PREPARE);
            if (pass.packPostChainActive) {
                pass.packPostTargets.adoptTarget0(commandBuffer, hdrColor);
            }
            hdrColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
        pass.rebindMainTarget();
    }

    /**
     * Runs the world's post windows at level end, EARLY unless the pre-translucent seam already
     * ran it and then LATE, and commits the frame's target state.
     */
    void runWorldPostWindows(VkCommandBuffer commandBuffer, VulkanImage hdrColor) {
        if (!pass.packEarlyPostCompleted
                && pass.packFrameSchedule.hasWindow(PackFrameSchedulePlan.PostWindow.EARLY)) {
            runPackPostWindow(commandBuffer, hdrColor, PackFrameSchedulePlan.PostWindow.EARLY);
            pass.packEarlyPostCompleted = true;
        }
        runPackPostWindow(commandBuffer, hdrColor, PackFrameSchedulePlan.PostWindow.LATE);
        commitPostFrame();
    }

    /** Commits the frame's target state if this frame opened it. */
    void commitPostFrame() {
        if (pass.packPostFrameStarted) {
            pass.packPostTargets.commitFrame();
            pass.packPostFrameStarted = false;
        }
    }

    /** Runs one scheduled non-final post window through the pack target graph. */
    void runPackPostWindow(
            VkCommandBuffer commandBuffer,
            VulkanImage hdrColor,
            PackFrameSchedulePlan.PostWindow window
    ) {
        if (!pass.packPostChainActive || !pass.packPostTargets.isConfigured()) {
            return;
        }
        ChimeraMainPass.PackRenderState previousState = pass.capturePackRenderState();
        try {
            if (!pass.packPostFrameStarted) {
                throw new IllegalStateException("PACK_TARGET_FRAME_NOT_STARTED:post window " + window);
            }
            pass.packPostTargets.requireFrameStarted();
            // Each Iris composite renderer starts with every target base-level sampled.
            pass.packPostTargets.resetMipmapSampling();
            ChimeraMainPass.preparePackFullscreenState();
            boolean tripwireSwept = false;
            for (PackPostExecution stage : this.packPostExecution) {
                if (!scheduledIn(stage, window) || !stageRunnable(stage)) {
                    continue;
                }
                if (!tripwireSwept) {
                    tripwireSwept = true;
                    sweepNanTripwire(commandBuffer, "before " + window);
                }
                runPostStage(commandBuffer, hdrColor, stage);
            }
        } catch (RuntimeException e) {
            abortPostWindow(commandBuffer, window, e);
        } finally {
            pass.restorePackRenderState(previousState);
        }
        if (pass.conformanceReport != null) {
            pass.conformanceReport.addDeviation("PING_PONG_TARGETS_APPLIED");
            pass.conformanceReport.addDeviation("UNWRITTEN_TARGET_PRESERVED");
        }
    }

    /** True when the frame schedule runs this non-final stage in the window. */
    private boolean scheduledIn(PackPostExecution stage, PackFrameSchedulePlan.PostWindow window) {
        PackFrameSchedulePlan.PostStage scheduled = pass.packFrameSchedule.postStage(stage.name());
        return !stage.finalStage() && scheduled != null && scheduled.window() == window;
    }

    /** True when the stage is installed, executable and has its inputs; otherwise marks its identity boundary. */
    private boolean stageRunnable(PackPostExecution stage) {
        PackPipelines.PackPost post = stage.post();
        if (post == null) {
            markPostStageIdentityBoundary(stage, "not installed");
            return false;
        }
        TargetStep graphStep = postGraphStep(stage);
        if (graphStep == null || !graphStep.executable()) {
            markPostGraphIdentityBoundary(stage, graphStep);
            return false;
        }
        if (!pass.packPostTargets.areInputsAvailable(post.requiredColorInputs())
                || !packDepthInputsAvailable(post.samplerNames())) {
            markPostStageIdentityBoundary(stage,
                    unavailablePostInputs(post));
            return false;
        }
        return true;
    }

    /** Draws one post stage into its graph targets; a failure aborts the targets it opened and rethrows. */
    private void runPostStage(VkCommandBuffer commandBuffer, VulkanImage hdrColor, PackPostExecution stage) {
        PackPipelines.PackPost post = stage.post();
        ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> previous = null;
        boolean attempted = false;
        try {
            pass.packPostTargets.generateMipmaps(commandBuffer, post.name());
            pass.prepareProgramImages(post.pipeline());
            previous = pass.images.bindProgramImages(post.pipeline(), pass.packPostTargets.sourceImages(),
                    hdrColor,
                    DrawMaterialContext.captureLive());
            attempted = true;
            pass.packPostTargets.prepare(post, commandBuffer);
            pass.drawFullscreen(commandBuffer, post.pipeline());
            pass.packPostTargets.finish(commandBuffer);
            checkNanTripwireOutputs(commandBuffer, stage);
        } catch (RuntimeException stageFailure) {
            if (attempted) {
                pass.packPostTargets.abort(commandBuffer);
            }
            markPostStageFailure(post, stageFailure);
            throw stageFailure;
        } finally {
            if (previous != null) previous.close();
        }
    }

    /** Aborts the frame's open targets and disables the post chain after a window fails. */
    private void abortPostWindow(VkCommandBuffer commandBuffer, PackFrameSchedulePlan.PostWindow window,
            RuntimeException failure) {
        try {
            if (pass.packPostFrameStarted) {
                pass.packPostTargets.abort(commandBuffer);
                pass.packPostFrameStarted = false;
            }
        } catch (RuntimeException abortFailure) {
            pass.logPackRuntimeFailure("post-abort:" + window, abortFailure);
        }
        String reason = ChimeraMainPass.runtimeFailureReason("post-window:" + window, failure);
        disablePackPostChain("POST_RUNTIME_FAILED:" + reason);
        pass.logPackRuntimeFailure("post-window:" + window, failure);
    }

    /** Runs the pack final stage after world post passes and before host hand. */
    public void finishPackFinalBeforeHand() {
        if (!pass.packWorldResolved || pass.packFinalApplied) {
            return;
        }
        Arrays.fill(pass.packFinalInputs, null);
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        if (pass.packDepthTargets.isConfigured()) {
            pass.packDepthTargets.commitFrame();
        }
        if (pass.pipelines.finalPost == null || !pass.packPostChainActive || !finalStageRunnable()) {
            pass.packFinalApplied = true;
            return;
        }

        try {
            VulkanImage finalColor = renderPackFinal(commandBuffer);
            this.nanTripwire.check(commandBuffer, "final", finalColor);
            pass.resolveWorldToOutput(commandBuffer, finalColor);
            pass.packFinalApplied = true;
        } catch (RuntimeException failure) {
            markPostStageFailure(pass.pipelines.finalPost, failure);
            try {
                Renderer.getInstance().endRenderPass(commandBuffer);
                renderIdentityResolve(commandBuffer, pass.packPostTargets.activeTarget(0));
            } catch (RuntimeException fallbackFailure) {
                pass.logPackRuntimeFailure("final-identity-resolve", fallbackFailure);
            }
            pass.packFinalApplied = true;
            pass.logPackRuntimeFailure("final", failure);
        }
    }

    /** True when final's graph step is executable and its inputs are live; otherwise marks its boundary. */
    private boolean finalStageRunnable() {
        TargetStep finalGraphStep = postGraphStep(this.packFinalExecution);
        boolean finalGraphExecutable = finalGraphStep != null && finalGraphStep.executable();
        boolean finalInputsAvailable = finalGraphExecutable
                && pass.packPostTargets.areInputsAvailable(pass.pipelines.finalPost.requiredColorInputs())
                && packDepthInputsAvailable(pass.pipelines.finalPost.samplerNames());
        if (finalInputsAvailable) {
            return true;
        }
        if (this.packFinalExecution != null) {
            if (!finalGraphExecutable) {
                markPostGraphIdentityBoundary(this.packFinalExecution, finalGraphStep);
            } else {
                markPostStageIdentityBoundary(this.packFinalExecution,
                        unavailablePostInputs(pass.pipelines.finalPost));
            }
        }
        return false;
    }

    /** Draws the final program into its own image and returns that image, readable by the resolve. */
    private VulkanImage renderPackFinal(VkCommandBuffer commandBuffer) {
        pass.prepareProgramImages(pass.pipelines.finalPost.pipeline());
        // Final reads pack targets in GL row order. It renders
        // into a GL-ordered image; the flipped resolve below moves that
        // image into the host-ordered output.
        Renderer.getInstance().endRenderPass(commandBuffer);
        // No pack target is written between the last post output check and final.
        // Re-reading every target/history/depth here adds cost but no new evidence.
        pass.packPostTargets.resetMipmapSampling();
        pass.packPostTargets.generateMipmaps(commandBuffer, pass.pipelines.finalPost.name());
        Renderer.getInstance().beginRenderPass(pass.targets.packFinalRenderPass, pass.targets.packFinalFramebuffer);
        ChimeraMainPass.PackRenderState finalState = pass.capturePackRenderState();
        ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> previous = null;
        try {
            ChimeraMainPass.preparePackFullscreenState();
            for (int target = 0; target < pass.packFinalInputs.length; target++) {
                pass.packFinalInputs[target] = pass.packPostTargets.activeTarget(target);
            }
            VulkanImage resolved = pass.packPostTargets.activeTarget(0);
            previous = pass.images.bindProgramImages(pass.pipelines.finalPost.pipeline(), pass.packFinalInputs,
                    resolved,
                    DrawMaterialContext.captureLive());
            pass.drawFullscreen(commandBuffer, pass.pipelines.finalPost.pipeline());
        } finally {
            if (previous != null) {
                previous.close();
            }
            pass.restorePackRenderState(finalState);
        }
        Renderer.getInstance().endRenderPass(commandBuffer);
        VulkanImage finalColor = pass.targets.packFinalFramebuffer.getColorAttachment();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            finalColor.transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        return finalColor;
    }

    TargetStep postGraphStep(PackPostExecution stage) {
        if (stage == null || pass.packPostTargets.graph() == null) {
            return null;
        }
        return pass.packPostTargets.graph().step(stage.name());
    }

    /** Converts a static graph rejection into a per-stage identity boundary. */
    void markPostGraphIdentityBoundary(PackPostExecution stage, TargetStep step) {
        if (this.packPostBoundaryLogged.contains(stage.name())) {
            if (!stage.finalStage()) {
                pass.packPostTargets.invalidateOutputs(step == null
                        ? stage.outputTargets() : step.outputTargets());
            }
            return;
        }
        String detail = step == null
                ? "graph step unavailable"
                : "graph step rejected " + step.deviations();
        if (step != null && pass.conformanceReport != null) {
            String code = graphRejectionCode(stage.name(), step);
            pass.conformanceReport.addDeviation(code);
            pass.conformanceReport.markRuntime(stage.name(),
                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK, code);
        }
        markPostStageIdentityBoundary(
                stage.name(),
                step == null ? stage.outputTargets() : step.outputTargets(),
                stage.finalStage(),
                detail);
    }

    static String graphRejectionCode(String name, TargetStep step) {
        for (String deviation : step.deviations()) {
            if (deviation.startsWith("POST_TARGET_ATTACHMENT_LIMIT:")
                    || deviation.startsWith("POST_TARGET_SIZE_CONFLICT:")
                    || deviation.startsWith("POST_TARGET_FORMAT_DEVICE_UNSUPPORTED:")
                    || deviation.startsWith("POST_TARGET_FEEDBACK_UNSUPPORTED:")
                    || deviation.equals("FINAL_MRT_UNSUPPORTED")) {
                return deviation;
            }
        }
        return "POST_STAGE_GRAPH_REJECTED:" + name;
    }

    /** Restores the stable host resolve after a pack post pass fails. */
    @SuppressWarnings("try") // the binding scope restores descriptor state on close
    void renderIdentityResolve(VkCommandBuffer commandBuffer, VulkanImage hdrColor) {
        ChimeraMainPass.PackRenderState previousState = pass.capturePackRenderState();
        ChimeraMainPass.preparePackFullscreenState();
        try (var bindings = pass.images.bindProgramImages(pass.compositePipeline, null, hdrColor,
                DrawMaterialContext.captureLive())) {
            Renderer.getInstance().beginRenderPass(pass.targets.compositeRenderPass, pass.targets.compositeFramebuffer);
            pass.drawFullscreen(commandBuffer, pass.compositePipeline);
            pass.currentFramebuffer = pass.targets.compositeFramebuffer;
        } finally {
            pass.restorePackRenderState(previousState);
        }
    }

    void markPostStageFailure(PackPipelines.PackPost post, RuntimeException failure) {
        String reason = ChimeraMainPass.runtimeFailureReason("post-stage:" + post.name(), failure);
        markPostStageIdentityBoundary(post, reason);
        if (pass.conformanceReport != null) {
            pass.conformanceReport.markRuntime(post.name(),
                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                    "POST_RUNTIME_STAGE_FAILED:" + post.name() + ":" + reason);
        }
    }

    void markPostStageIdentityBoundary(PackPipelines.PackPost post, String detail) {
        PostTargetPlan plan = post.targetPlan();
        markPostStageIdentityBoundary(
                post.name(),
                plan == null ? List.of() : plan.targetSlots(),
                plan != null && plan.isFinal(),
                detail);
    }

    void markPostStageIdentityBoundary(PackPostExecution stage, String detail) {
        markPostStageIdentityBoundary(stage.name(), stage.outputTargets(), stage.finalStage(), detail);
    }

    void markPostStageIdentityBoundary(
            String name,
            List<Integer> outputTargets,
            boolean finalStage,
            String detail
    ) {
        if (!finalStage) {
            pass.packPostTargets.invalidateOutputs(outputTargets);
        }
        if (!this.packPostBoundaryLogged.add(name)) {
            return;
        }
        String boundary = "POST_STAGE_IDENTITY_BOUNDARY:" + name;
        if (pass.conformanceReport != null) {
            pass.conformanceReport.addDeviation(boundary);
            pass.conformanceReport.markRuntime(name,
                    ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                    boundary);
        }
        if (pass.shouldLogPackRuntimeFailure(detail)) {
            LOGGER.warn("[chimera] pack {}: fallback=IDENTITY (outputs={}, {})",
                    name, outputTargets, detail);
        }
    }

    /** Disables all pack post seams while retaining objects for safe teardown. */
    void disablePackPostChain(String reason) {
        pass.packTargetResourcesReady = false;
        pass.packPostFrameStarted = false;
        pass.windows.restorePackResourceWindow();
        if (PackGeometryContext.active()) PackGeometryContext.close();
        pass.pipelines.terrainRoute = null;
        pass.pipelines.waterRoute = null;
        ChimeraTerrainPipelines.setGeometryOverride(null);
        ChimeraTerrainPipelines.setTranslucentOverride(null);
        ChimeraEntityBridge.setEnabled(false);
        ChimeraSkyBridge.setEnabled(false);
        pass.packPostChainActive = false;
        pass.pipelines.compositePipeline = null;
        pass.pipelines.compositeSlots = null;
        pass.pipelines.compositeSamplerNames = List.of();
        pass.pipelines.finalPipeline = null;
        pass.pipelines.finalSlots = null;
        pass.pipelines.finalSamplerNames = List.of();
        pass.pipelines.finalPost = null;
        pass.packPostChainRejected = true;
        if (pass.conformanceReport != null) {
            pass.conformanceReport.addDeviation(reason);
            for (PackPipelines.PackPost post : pass.pipelines.postStages) {
                ConformanceReport.ProgramReport current = pass.conformanceReport.program(post.name());
                if (current == null
                        || current.runtime() != ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK) {
                    pass.conformanceReport.markRuntime(post.name(),
                            ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK, reason);
                }
            }
            LOGGER.info("[chimera] conformance {}", pass.conformanceReport.toJson());
            pass.sessions.logConformanceSummary();
        }
    }

    static VulkanImage resolvePackColorInput(
            Integer logicalTarget,
            VulkanImage[] colorInputs,
            VulkanImage fallbackColortex
    ) {
        if (logicalTarget == null) {
            return fallbackColortex;
        }
        if (logicalTarget == 0
                && (colorInputs == null || logicalTarget >= colorInputs.length
                || colorInputs[logicalTarget] == null)) {
            return fallbackColortex;
        }
        if (logicalTarget < 0 || colorInputs == null || logicalTarget >= colorInputs.length) {
            return null;
        }
        return colorInputs[logicalTarget];
    }

    boolean packDepthInputsAvailable(List<String> samplerNames) {
        if (samplerNames == null) return true;
        for (String sampler : samplerNames) {
            if (sampler != null && sampler.startsWith("depthtex")
                    && pass.packDepthTargets.image(sampler) == null) {
                return false;
            }
        }
        for (String sampler : samplerNames) {
            if (("shadowcolor0".equals(sampler) || "shadowcolor1".equals(sampler))
                    && (!pass.shadowPass.shadowFrameReady || pass.shadowPass.shadowMap.shadowColor(
                    sampler.equals("shadowcolor1") ? 1 : 0) == null)) return false;
        }
        return true;
    }

    String unavailablePostInputs(PackPipelines.PackPost post) {
        ArrayList<String> missing = new ArrayList<>();
        for (int target : post.requiredColorInputs()) {
            if (!pass.packPostTargets.currentTargetAvailable(target)) missing.add("colortex" + target);
        }
        for (String sampler : post.samplerNames()) {
            if (!packDepthInputsAvailable(List.of(sampler))) missing.add(sampler);
        }
        return "unavailable inputs " + missing;
    }

    void configurePackPostTargets(int width, int height) {
        if (pass.packPostChainRejected) {
            return;
        }
        try {
            if (pass.packPlan == null) {
                return;
            }
            int maxAttachments = ChimeraMainPass.deviceMaxColorAttachments();
            pass.packTargetGraph = PackTargetGraphPlan.build(
                    pass.packPlan.activePrograms(), pass.packConfig, pass.packPlan.resources(), width, height,
                    maxAttachments, ChimeraMainPass.deviceMaxImageDimension());
            pass.coverage.packCoveragePlan = PackCoveragePlan.from(
                    pass.packTargetGraph, pass.packPlan, width, height);
            if (pass.coverage.packCoveragePlan.enabled() && pass.packCoverageRuntimeEnabled()) {
                pass.coverage.packCoverageOwner.ensure(width, height);
                if (pass.coverage.sceneSeedPipeline != null) {
                    pass.coverage.sceneSeedPipeline.cleanUp();
                }
                MrtPipelineContext.begin(new int[] {pass.packGeometryTargetFormat()}, maxAttachments);
                try {
                    pass.coverage.sceneSeedPipeline = ChimeraPostPipelines.createSceneSeedPipeline();
                } finally {
                    MrtPipelineContext.end();
                }
                LOGGER.info("[chimera] scene seed: planned families={} deviations={}",
                        pass.coverage.packCoveragePlan.families(), pass.coverage.packCoveragePlan.deviations());
            } else if (!pass.coverage.packCoveragePlan.deviations().isEmpty()) {
                LOGGER.info("[chimera] scene seed: fallback deviations={}",
                        pass.coverage.packCoveragePlan.deviations());
            }
            pass.packFrameSchedule = PackFrameSchedulePlan.build(
                    pass.packPlan.activePrograms(), pass.packTargetGraph);
            pass.packNeedsHdrDepth = pass.packTargetGraph.depth().any();
            pass.packDepthTargets.configure(width, height, pass.packTargetGraph.depth());
            pass.packTargetResourcesReady = pass.packPostTargets.configure(pass.packTargetGraph);
            pass.packPostChainActive = pass.packTargetResourcesReady
                    && !pass.packTargetGraph.steps().isEmpty();
            if (pass.packPostChainActive) {
                LOGGER.info("[chimera] pack frame schedule: phases={}, prepare={}, early={}, late={}, final={}, "
                        + "depth={}, deviations={}",
                        pass.packFrameSchedule.phases(),
                        pass.packFrameSchedule.stages(PackFrameSchedulePlan.PostWindow.PREPARE).stream()
                                .map(PackFrameSchedulePlan.PostStage::name).toList(),
                        pass.packFrameSchedule.stages(PackFrameSchedulePlan.PostWindow.EARLY).stream()
                                .map(PackFrameSchedulePlan.PostStage::name).toList(),
                        pass.packFrameSchedule.stages(PackFrameSchedulePlan.PostWindow.LATE).stream()
                                .map(PackFrameSchedulePlan.PostStage::name).toList(),
                        pass.packFrameSchedule.stages(PackFrameSchedulePlan.PostWindow.FINAL).stream()
                                .map(PackFrameSchedulePlan.PostStage::name).toList(),
                        pass.packTargetGraph.depth().names(),
                        pass.packFrameSchedule.deviations());
                LOGGER.info("[chimera] pack target graph: targets={}, steps={}, depth={}, "
                        + "maxAttachments={}, fingerprint={}",
                        pass.packTargetGraph.targets().size(), pass.packTargetGraph.steps().size(),
                        pass.packTargetGraph.depth().names(), pass.packTargetGraph.maxAttachments(),
                        pass.packTargetGraph.fingerprint());
            }
        } catch (RuntimeException e) {
            pass.packPostTargets.cleanUp();
            pass.packDepthTargets.cleanUp();
            pass.coverage.packCoverageOwner.close();
            pass.coverage.packCoverageState.endFrame();
            pass.coverage.packCoveragePlan = PackCoveragePlan.disabled();
            pass.packPostChainActive = false;
            pass.packTargetResourcesReady = false;
            pass.packTargetGraph = null;
            pass.packFrameSchedule = PackFrameSchedulePlan.empty();
            pass.packPostChainRejected = true;
            discardPackPostPipelines();
            if (pass.conformanceReport != null) {
                pass.conformanceReport.addDeviation("POST_RESOURCE_ALLOCATION_FAILED");
                LOGGER.info("[chimera] conformance {}", pass.conformanceReport.toJson());
            }
            pass.logPackRuntimeFailure("post-resource-allocation", e);
        }
    }

    /** Builds the immutable post execution order once for the active pack. */
    void buildPackPostExecutionPlan() {
        this.packPostExecution.clear();
        this.packFinalExecution = null;
        if (pass.packPrograms == null) {
            return;
        }
        List<PackProgram> postPrograms = pass.packPrograms.stream()
                .filter(program -> PostTargetPlan.isPostProgramName(program.name()))
                // A pass the pack switches off is not in the chain at all, as in Iris.
                .filter(program -> pass.packPlan == null || !pass.packPlan.isProgramDisabled(program.name()))
                .sorted(Comparator.comparing(PackProgram::name, PostTargetPlan.programComparator()))
                .toList();
        String previousName = null;
        for (PackProgram program : postPrograms) {
            if (program.name().equals(previousName)) {
                continue;
            }
            previousName = program.name();
            PackPipelines.PackPost installed = null;
            for (PackPipelines.PackPost candidate : pass.pipelines.postStages) {
                if (candidate.name().equals(program.name())) {
                    installed = candidate;
                    break;
                }
            }
            List<Integer> outputs = List.of();
            PackProgramPlan planned = pass.packPlan == null
                    ? null : pass.packPlan.program(program.name());
            if (planned != null && planned.targetPlan() != null) {
                outputs = planned.targetPlan().targetSlots();
            } else if (installed != null && installed.targetPlan() != null) {
                outputs = installed.targetPlan().targetSlots();
            } else if (pass.conformanceReport != null) {
                ConformanceReport.ProgramReport report = pass.conformanceReport.program(program.name());
                if (report != null) {
                    outputs = report.targets();
                }
            }
            PackPostExecution execution = new PackPostExecution(
                    program.name(), outputs, installed, program.name().equals("final"));
            this.packPostExecution.add(execution);
            if (execution.finalStage()) {
                this.packFinalExecution = execution;
            }
        }
    }

    /** Discards installed post pipelines when target graph setup fails. */
    void discardPackPostPipelines() {
        for (PackPipelines.PackPost post : pass.pipelines.postStages) {
            post.pipeline().cleanUp();
            if (pass.conformanceReport != null) {
                pass.conformanceReport.markRuntime(post.name(),
                        ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK,
                        "POST_CHAIN_FAILED");
            }
        }
        pass.pipelines.postStages.clear();
        this.packPostExecution.clear();
        this.packPostBoundaryLogged.clear();
        pass.pipelines.finalPost = null;
        this.packFinalExecution = null;
        pass.pipelines.compositePipeline = null;
        pass.pipelines.compositeSlots = null;
        pass.pipelines.compositeSamplerNames = List.of();
        pass.pipelines.finalPipeline = null;
        pass.pipelines.finalSlots = null;
        pass.pipelines.finalSamplerNames = List.of();
        LOGGER.warn("[chimera] pack post chain: fallback=IDENTITY (POST_CHAIN_FAILED)");
    }
}

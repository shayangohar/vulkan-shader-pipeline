package net.chimera.render;

import net.chimera.shaderpack.PassComputePlan;
import net.chimera.shaderpack.ShadowCullingPlan;
import net.chimera.shaderpack.ShadowPassUse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One pack session's flags, once-only logs and plans that no owner closes: everything that must
 * not outlive the session. PackSessionLifecycle.cleanUpPackVariant replaces it whole
 * (ChimeraMainPass.session), so a field added here starts each session clear. A field left out of
 * a reset list is how state went stale before (pre-merge audit D4: shadowSegmentObserved and
 * passComputes were never reset).
 *
 * <p>Kept out on purpose: what must outlive the cleanup (packFrameUnsupported, which renderVanilla
 * sets before it cleans up; the conformance report a failure's report reads after it), the plan
 * and the resource owners, which close in order, and per-frame flags, which each frame resets.</p>
 */
final class PackSessionState {
    // Main pass
    /** Pack pipelines are loaded once a session and kept until it ends. */
    boolean packPipelinesLoaded;
    boolean packPostChainActive;
    /** Target images may be needed by geometry even when no post pass is installed. */
    boolean packTargetResourcesReady;
    /** Set only when the target graph itself cannot be configured. */
    boolean packPostChainRejected;
    boolean packStorageInitializationLogged;
    /** Suppresses the secondary frame-not-started flood until the next pack session. */
    boolean packTargetFrameNotStartedLogged;
    boolean packTranslucentSuspendLogged;
    /** The loaded pack's pass computes, as its analysis translated them. */
    PassComputePlan passComputes = PassComputePlan.empty();

    // Shadow pass
    /** Set once the caster pass failed; the shadow map keeps its terrain for the rest of the session. */
    boolean shadowCastersFailed;
    /** Iris's shadow culling for this pack session (shadow.culling and the shadow distances). */
    ShadowCullingPlan shadowCulling;
    /** Who observes the pack's shadow pass, and so whether it draws (Iris's ShadowRenderer rule). */
    ShadowPassUse shadowUse = ShadowPassUse.ALWAYS;
    boolean shadowSegmentObserved;
    /** Prevents an invalid shadow resource from producing per-frame warnings. */
    boolean shadowTransitionFallbackLogged;
    /** Runtime report/log marker for the host's unique opaque/cutout policy. */
    boolean shadowCutoutDispositionLogged;
    /** Programs whose routed shadow sampler was already reported once. */
    final Set<String> loggedShadowSamplerPrograms = new HashSet<>();

    // Post chain
    /** All discovered post programs, including static fallback stages. */
    final List<PackPostChain.PackPostExecution> packPostExecution = new ArrayList<>();
    /** Identity-boundary diagnostics are emitted once per pack session. */
    final Set<String> packPostBoundaryLogged = new HashSet<>();
    /** The stages that threw while recording this pack session, which now run as identity. */
    final PostStageFailures stageFailures = new PostStageFailures();
    PackPostChain.PackPostExecution packFinalExecution;
}

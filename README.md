# Chimera

Shaderpack pipeline for VulkanMod - the goal is to run Iris/OptiFine-class community shaderpacks natively on Vulkan, and eventually on Minecraft's own Vulkan renderer.

**Status: M6.6 complete. M7.0 real-pack parity baseline complete. M7.1 resolution slice complete. M7.2 shared translation core is in progress.**

M4 is confirmed against the checked-in `testpacks/simplex` fixture. The loader discovers pack programs from disk, converts legacy fragment GLSL, compiles the converted stages through runtime shaderc, and drives `gbuffers_terrain`, `composite`, and `final` without source edits.

M4 was a narrow loader wedge, not general Iris compatibility. M5.6 added a bounded four-target post chain with real target routing and ping-pong resources. M5.7 added directory and ZIP loading for real packs, standard dimension variants, root-relative includes, a narrow GLSL 130 post path, explicit common RGB format approximations, deterministic fingerprints, lifecycle evidence, and performance measurements. M6.1 shares one prepared-source, program-plan, interface, and token-translation path between conformance and runtime pipeline construction. M6.2 adds one canonical live uniform catalog and one reusable frame snapshot for pack UBOs, including camera history, matrices, weather, player, lighting, and shadow values. M6.3 adds an isolated legacy gbuffers_entities adapter with an append-only entity format, basic entity IDs, and guarded host-state draw dispatch. M6.4 extends post targets beyond the original four-target bridge and validates MRT against device limits. Unsupported stages, resources, and syntax still use explicit fallback. It is still not a general Iris transformer.

## Requirements

- Minecraft 1.21.11 (Fabric)
- [VulkanMod](https://modrinth.com/mod/vulkanmod) 0.6.8+1.21.11 or newer
- Fabric API

## Building

Requires Java 21. Build output stays in `build/libs/`. Run `powershell -NoProfile -ExecutionPolicy Bypass -File tools\stage-chimera.ps1` after each build to replace the Chimera jar in the CHIMERA Prism instance and verify `LATEST-SHA256.txt`.

## Roadmap

| Milestone | Scope |
|---|---|
| M1 | Project bootstrap and main-pass takeover |
| M2 | Terrain through Chimera-compiled SPIR-V pipelines |
| M3 | Shadow pass, HDR frame, and composite/final machinery |
| M4 | Shaderpack loader core: declarations to pipelines |
| M5.1 | Iris compatibility contract and fixture harness: define the supported subset and locked regression scenes |
| M5.2 | Terrain and material slice: `gbuffers_terrain`, `mc_Entity`, texture/lightmap inputs, and material attributes |
| M5.3 | Uniform and resource slice: live OptiFine/Iris uniforms, samplers, depth inputs, defaults, and bindings |
| M5.4 | Lighting and shadow slice: shadow programs, `shadowtex`/`depthtex`, shadow settings, and lighting uniforms |
| M5.5 | Translucency family slice: legacy gbuffers_water on the host translucent terrain lane |
| M5.6 | Post and frame-graph slice: deferred/composite/final, `RENDERTARGETS`/`DRAWBUFFERS`, ping-pong, and supported MRT |
| M5.7 | Multi-pack evidence slice: Complementary plus a second Iris/OptiFine pack, lifecycle checks, performance, and deviations |
| M6.1 | Shared source and program translation core: immutable prepared sources, cross-stage plans, bounded preprocessing, and token-based legacy translation |
| M6.2 | Canonical live uniform catalog: one typed catalog, one frame snapshot, stable buffers, camera history, and standard runtime values |
| M6.3 | Entity geometry adapter: legacy gbuffers_entities, append-only entity format, basic entity IDs, and guarded world-entity dispatch |
| M6.4 | Device-aware post targets and pack resources: sparse target allocation, ping-pong preservation, dynamic post MRT, and explicit limit fallback |
| M6.5 | Real-pack parity qualification: Complementary, BSL, reference comparisons, lifecycle coverage, and measured performance evidence |
| M6.6 | Command-driven runtime pack switching with safe GPU-idle replacement and rollback |
| M7 | Core Iris visual parity: program resolution, translation, uniforms, targets, resources, family adapters, frame sequencing, and qualification |
| M7.0 | Lock the real-pack parity gap: static eligibility, runtime installation, execution evidence, and visual claims |
| M7.1 | Resolve dimensions, standard program families, settings, options, feature flags, and explicit fallback aliases |
| M7.2 | Expand the bounded shared GLSL translator for real-pack declarations, built-ins, texture operations, outputs, interfaces, and safe fallback |

M5 is a sequence of pack-visible vertical slices. Each slice must carry a real Iris/OptiFine-format fixture or pack from source loading through runtime output, preserve the Simplex regression control, and document unsupported features explicitly. Complementary is a reference pack for breadth, not a source of hardcoded special cases.

The normal `conformanceTest` checks the checked-in M5.1 through M5.7 fixtures. `m62ConformanceTest` checks the local M6.2 uniform catalog fixture and frame-state invariants. `m63ConformanceTest` checks the M6.3 entity geometry format, mapping, translation, and fallback fixture. `m64ConformanceTest` checks device-aware target allocation and MRT limits. M6.1 uses its strict task for the checked-in program-plan fixture plus explicit real-pack paths. M5.7 uses the strict external-pack task and requires explicit paths and versions:

`powershell -NoProfile -ExecutionPolicy Bypass -File tools\m57-conformance.ps1 -ComplementaryPath <pack-or-relative-name> -IndependentPath <pack-or-relative-name> -ComplementaryVersion <version> -IndependentVersion <version>`

M6.5 uses the exact Complementary Reimagined r5.8.1 and BSL v10.1.3 packs. It compares deterministic static reports with user-provided Chimera and Sodium + Iris reference evidence. It does not add compatibility behavior and does not claim universal visual or performance parity. Run it explicitly with Prism Java 21:

`powershell -NoProfile -ExecutionPolicy Bypass -File tools\m65-conformance.ps1 -ComplementaryPath <pack-or-relative-name> -BslPath <pack-or-relative-name> -ComplementaryVersion r5.8.1 -BslVersion v10.1.3`

M6.5 capture and performance files stay outside the committed source tree. A runtime-installed program must be proven by the Minecraft log and RenderDoc evidence; static eligibility alone is not an installation claim. Use identical scene state and camera for Chimera and Sodium + Iris references, and report performance as three fixed-window medians with RenderDoc structural counts.

M7.0 keeps the same exact Complementary Reimagined r5.8.1 and BSL v10.1.3 pack fingerprints and adds a capability matrix. Static eligibility, runtime installation, RenderDoc execution, and visual parity are separate states. Run the strict baseline check with the saved evidence paths:

`powershell -NoProfile -ExecutionPolicy Bypass -File tools\m70-parity-baseline.ps1 -ComplementaryPath <pack-or-relative-name> -BslPath <pack-or-relative-name> -ComplementaryVersion r5.8.1 -BslVersion v10.1.3 -ComplementaryLogPath <log-path> -BslLogPath <log-path> -ComplementaryCapturePath <capture-path> -BslCapturePath <capture-path>`

The M7.0 check requires one installed program and one fallback program for each pack, verifies the M6.5 source and report baseline, and checks ZIP and directory loading. It records RenderDoc execution as review-required until the capture structure is confirmed. It does not claim visual parity or performance parity. Use `-EmitBaseline` only when intentionally replacing `testpacks\baselines\m7_0.json` after reviewing the generated evidence.

M7.1 adds one load-time resolution plan. It selects a dimension source, applies defaults from `shaders.properties` and authored shader options, records profiles and feature flags, evaluates program enable expressions, and records standard-family fallback aliases. Alias records are not routed through an incompatible adapter. Run the checked-in fixture with Prism Java 21:

`powershell -NoProfile -ExecutionPolicy Bypass -File tools\m71-conformance.ps1 -ComplementaryPath <pack-or-relative-name> -BslPath <pack-or-relative-name> -ComplementaryVersion <version> -BslVersion <version>`

The M7.1 wrapper requires explicit real-pack paths and versions when strict external checks are requested. The ordinary `m71ConformanceTest` task runs the local resolution fixture and does not require external packs. Resolution fingerprints are evidence of deterministic pack interpretation; they do not claim that an aliased family is installed or that the pack has visual parity.

M7.2 extends the existing load-time token translator. Run `m72ConformanceTest` for legacy declaration, nested texture, shadow lookup, output, interface, and fail-closed fallback checks. It remains source-agnostic and does not add modern GLSL, new renderer families, or resource semantics outside the existing bridge.

## Runtime pack switching

M6.6 adds client-only commands. The `-Dchimera.pack` JVM property remains the startup default. Runtime commands do not need a Minecraft restart.

Use `/chimera pack list` to list direct shaderpacks-directory children. Use `/chimera pack load <name-or-path>` to queue a directory or ZIP. Names resolve in the instance `minecraft/shaderpacks` directory; explicit local paths may be absolute or relative to the game directory. Use `/chimera pack reload` to rebuild the active selection, `/chimera pack off` to select Chimera identity rendering, and `/chimera pack status` to inspect the active and pending selections.

Pack changes apply at the next safe command-buffer boundary. Chimera waits for Vulkan idle before replacing pack resources, so a short frame pause is expected. F8 remains the master Chimera enable state. A failed replacement restores the previous pack when possible; the host renderer is used only if restoration also fails.

## License

[MIT](LICENSE). Links against VulkanMod (LGPL-3.0) as an unmodified library.

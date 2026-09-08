# Chimera

Shaderpack pipeline for VulkanMod - the goal is to run Iris/OptiFine-class community shaderpacks natively on Vulkan, and eventually on Minecraft's own Vulkan renderer.

**Status: M6.6 complete. M7.0 real-pack parity baseline complete. M7.1 resolution slice complete. M7.2 shared translation core complete. M7.3 uniform and world-state semantics complete. M7.4 target and depth graph implementation complete. M7.5 sampled resource bridge implementation complete. M7.6 core family adapter implementation complete. M7.7 temporal frame schedule complete. M7.8 qualification deferred pending Sodium + Iris reference evidence and follow-up compatibility work. M8.0 measured modern post translation implemented. M8.1 modern terrain and water material bridge implemented; real-pack runtime qualification pending.**

M4 is confirmed against the checked-in `testpacks/simplex` fixture. The loader discovers pack programs from disk, converts legacy fragment GLSL, compiles the converted stages through runtime shaderc, and drives `gbuffers_terrain`, `composite`, and `final` without source edits.

M4 was a narrow loader wedge, not general Iris compatibility. M5.6 added a bounded four-target post chain with real target routing and ping-pong resources. M5.7 added directory and ZIP loading for real packs, standard dimension variants, root-relative includes, a narrow GLSL 130 post path, explicit common RGB format approximations, deterministic fingerprints, lifecycle evidence, and performance measurements. M6.1 shares one prepared-source, program-plan, interface, and token-translation path between conformance and runtime pipeline construction. M6.2 adds one canonical live uniform catalog and one reusable frame snapshot for pack UBOs, including camera history, matrices, weather, player, lighting, and shadow values. M6.3 adds an isolated legacy gbuffers_entities adapter with an append-only entity format, basic entity IDs, and guarded host-state draw dispatch. M6.4 extends post targets beyond the original four-target bridge and validates MRT against device limits. M7.4 replaces global post-bank assumptions with an immutable target graph, per-target validity and side ownership, relative or absolute sizes, clear and persistent policies, flip directives, device-aware MRT, and converted depthtex0/1/2 snapshots. M7.5 adds session-owned sampled pack resources and truthful standard aliases. M7.6 adds table-driven block, hand, and particle family adapters while keeping sky, cloud, weather, glowing, and other unsupported lanes on explicit host fallback. Unsupported layouts remain explicit fallback. It is still not a general Iris transformer.

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
| M7.3 | Complete the canonical live uniform catalog, Iris world-state semantics, smoothing, and bounded scalar pack values |
| M7.4 | Authoritative render-target and depth graph: sizes, formats, clear and flip policies, per-target ownership, device-aware MRT, and depth snapshots |
| M7.5 | Pack texture and resource bridge: sampled pack PNGs, standard aliases, safe ownership, and per-program resource fallback |
| M7.6 | Core family adapters: gbuffers_block, gbuffers_hand, gbuffers_particles, shared host-state seams, and explicit unsupported-family fallback |
| M7.7 | Complete frame schedule and temporal state: early deferred, world depth seams, late composite, final-before-GUI, and bounded previous-target validity |
| M7.8 | Qualify core visual parity against exact Complementary and BSL packs using separate static, runtime, RenderDoc, visual, and performance evidence |
| M8.0 | Bounded modern post translation for measured Complementary and BSL GLSL syntax |
| M8.1 | Modern terrain and water material bridge with pack-session append-only vertex formats and explicit fallback |

M5 is a sequence of pack-visible vertical slices. Each slice must carry a real Iris/OptiFine-format fixture or pack from source loading through runtime output, preserve the Simplex regression control, and document unsupported features explicitly. Complementary is a reference pack for breadth, not a source of hardcoded special cases.

The normal `conformanceTest` checks the checked-in M5.1 through M5.7 fixtures. `m62ConformanceTest` checks the local M6.2 uniform catalog fixture and frame-state invariants. `m63ConformanceTest` checks the M6.3 entity geometry format, mapping, translation, and fallback fixture. `m64ConformanceTest` checks device-aware target allocation and MRT limits. `m75ConformanceTest` checks sampled resources. `m76ConformanceTest` checks the shared core family registry, block and hand adapters, the particle adapter, and unsupported-family fallback. M6.1 uses its strict task for the checked-in program-plan fixture plus explicit real-pack paths. M5.7 uses the strict external-pack task and requires explicit paths and versions:

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

M7.3 extends the same one-frame snapshot used by pack UBO suppliers. Run `m73ConformanceTest` for Iris-style time, camera, matrix, dimension, weather, fog, fluid, lighting, smoothing, and bounded scalar-value checks. Values without an authoritative source remain explicit defaults or fallback. `centerDepthSmooth` and previous depth or render-target history remain deferred to the temporal-resource work.

M7.4 runs `m74ConformanceTest` for the authoritative target and depth graph. The fixture checks target formats, relative sizes, clear and persistent state, output routes, flips, logical validity, device-aware MRT limits, reversed-Z depth conversion, and failure fallback. Runtime resources are recreated only at existing GPU-idle boundaries. Run it with Prism Java 21 before staging:

`$env:JAVA_HOME = '<Prism Java 21 directory>'; .\gradlew.bat m74ConformanceTest --no-daemon`

M7.4 supports logical `colortex0` through `colortex7` and uses the device color-attachment limit up to the isolated eight-target bridge. `depthtex0`, `depthtex1`, and `depthtex2` use distinct pack bindings 6, 12, and 13. M7.7 adds the schedule that decides when those depth snapshots are captured and when post stages execute.

M7.5 runs `m75ConformanceTest` for deterministic sampled-resource plans. The fixture checks stage-specific and global texture precedence, safe PNG loading, noise and custom textures, standard target/depth/shadow aliases, stable selector slots 14 through 21, and per-program fallback for unavailable resources. Pack-owned sampled resources are loaded once per pack session and released at the existing GPU-idle cleanup boundary. Writable images, storage buffers, compute resources, 3D or array textures, and unsupported material maps remain explicit fallback.

M7.6 runs `m76ConformanceTest` for the table-driven family adapter registry and local legacy fixtures. `gbuffers_entities`, `gbuffers_block`, and `gbuffers_hand` use the append-only extended entity format and guarded host-state draw seam. `gbuffers_particles` uses the host particle format and a separate guarded draw window. Sky, cloud, weather, glowing, outlines, and other unimplemented lanes remain explicit host fallback. The slice does not change terrain, water, shadow, post targets, frame timing, or lifecycle ownership.

M7.7 runs `m77ConformanceTest` for the immutable frame schedule and temporal validity state. Deferred stages run at the early post seam, composite stages run after world rendering, depthtex1 is captured before translucent terrain, depthtex2 is captured before hand submission, and final runs after hand and before GUI. `PackPostTargets` and `PackDepthTargets` remain the resource owners; the schedule only orders their existing seams. Unavailable targets and failed stages remain identity fallback, and previous-target validity is reset at pack and level boundaries.

M7.8 is a qualification gate, not a renderer expansion. It checks exact Complementary Reimagined r5.8.1 and BSL v10.1.3 packs through static plan eligibility, runtime installation, RenderDoc execution, and optional Sodium + Iris reference evidence. The qualification is currently deferred because the Sodium + Iris instance does not yet provide a compatible reference capture path. Run the strict check with `tools\m78-qualification.ps1`, providing the two pack paths, versions, Chimera logs, and Chimera captures. Add `-RequireReference` only when matching Sodium + Iris captures are available. The harness never claims visual parity from static eligibility alone, and performance comparison remains deferred to `TASK-172`.

M8.0 extends the shared load-time translator for the measured modern post syntax used by Complementary Reimagined r5.8.1 and BSL v10.1.3. Run `m80ConformanceTest` for the original modern post fixture and earlier baselines. GLSL 330 and 400 support is bounded to the existing post adapter; unsupported families, resources, and syntax remain explicit identity fallback.

M8.1 extends the same shared plan to a bounded modern `gbuffers_terrain` and `gbuffers_water` material contract. It keeps the legacy 24-byte terrain path unchanged and selects a pack-session 36-byte or 40-byte append-only format for modern material inputs. The bridge supplies block identity, midpoint UV, midpoint block data, lightmap data, and a truthful normal/tangent fallback; `separateAo` is carried explicitly while host color ownership remains visible as a deviation. MRT, modern shadow stages, writable resources, and unsupported material declarations remain identity fallback. Run `m81ConformanceTest` before staging. Runtime qualification must prove the terrain and water pipeline, vertex stride, descriptor bindings, and safe fallback in Minecraft and RenderDoc.

For headless runtime verification, run `powershell -NoProfile -ExecutionPolicy Bypass -File tools\\launch-chimera-world.ps1`. The script uses Prism Launcher world Quick Play, waits for `Chimera Dev` to be active, and prints the Minecraft PID for RenderDoc injection. It does not edit the instance configuration.

## Runtime pack switching

M6.6 adds client-only commands. The `-Dchimera.pack` JVM property remains the startup default. Runtime commands do not need a Minecraft restart.

Use `/chimera pack list` to list direct shaderpacks-directory children. Use `/chimera pack load <name-or-path>` to queue a directory or ZIP. Names resolve in the instance `minecraft/shaderpacks` directory; explicit local paths may be absolute or relative to the game directory. Use `/chimera pack reload` to rebuild the active selection, `/chimera pack off` to select Chimera identity rendering, and `/chimera pack status` to inspect the active and pending selections.

Pack changes apply at the next safe command-buffer boundary. Chimera waits for Vulkan idle before replacing pack resources, so a short frame pause is expected. F8 remains the master Chimera enable state. A failed replacement restores the previous pack when possible; the host renderer is used only if restoration also fails.

## License

[MIT](LICENSE). Links against VulkanMod (LGPL-3.0) as an unmodified library.

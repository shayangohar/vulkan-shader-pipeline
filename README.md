# Chimera

Shaderpack pipeline for VulkanMod - the goal is to run Iris/OptiFine-class community shaderpacks natively on Vulkan, and eventually on Minecraft's own Vulkan renderer.

**Status: M6.4 complete and runtime-verified.**

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

M5 is a sequence of pack-visible vertical slices. Each slice must carry a real Iris/OptiFine-format fixture or pack from source loading through runtime output, preserve the Simplex regression control, and document unsupported features explicitly. Complementary is a reference pack for breadth, not a source of hardcoded special cases.

The normal `conformanceTest` checks the checked-in M5.1 through M5.7 fixtures. `m62ConformanceTest` checks the local M6.2 uniform catalog fixture and frame-state invariants. `m63ConformanceTest` checks the M6.3 entity geometry format, mapping, translation, and fallback fixture. M6.1 uses its strict task for the checked-in program-plan fixture plus explicit real-pack paths. M5.7 uses the strict external-pack task and requires explicit paths and versions:

`powershell -NoProfile -ExecutionPolicy Bypass -File tools\m57-conformance.ps1 -ComplementaryPath <pack-or-relative-name> -IndependentPath <pack-or-relative-name> -ComplementaryVersion <version> -IndependentVersion <version>`

## License

[MIT](LICENSE). Links against VulkanMod (LGPL-3.0) as an unmodified library.

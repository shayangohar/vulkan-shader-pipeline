# Chimera

Shaderpack pipeline for VulkanMod - the goal is to run Iris/OptiFine-class community shaderpacks natively on Vulkan, and eventually on Minecraft's own Vulkan renderer.

**Status: M4 complete.**

M4 is confirmed against the checked-in `testpacks/simplex` fixture. The loader discovers pack programs from disk, converts legacy fragment GLSL, compiles the converted stages through runtime shaderc, and drives `gbuffers_terrain`, `composite`, and `final` without source edits.

M4 is a narrow loader wedge, not general Iris compatibility yet. Its current boundary is fragment-only pack programs, a fixed Chimera vertex and UBO contract, a limited sampler registry, one-color output, and logging-only support for several pack settings. Unsupported stages fall back to the identity pipeline.

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
| M5.7 | General conformance slice: Complementary plus a second Iris/OptiFine pack, lifecycle checks, performance, and deviations |

M5 is a sequence of pack-visible vertical slices. Each slice must carry a real Iris/OptiFine-format fixture or pack from source loading through runtime output, preserve the Simplex regression control, and document unsupported features explicitly. Complementary is a reference pack for breadth, not a source of hardcoded special cases.

## License

[MIT](LICENSE). Links against VulkanMod (LGPL-3.0) as an unmodified library.

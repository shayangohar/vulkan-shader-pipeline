# Chimera

Shaderpack pipeline for [VulkanMod](https://modrinth.com/mod/vulkanmod) — the goal of running
Iris/OptiFine-class community shaderpacks natively on Vulkan, and eventually on Minecraft's own
Vulkan renderer.

**Status: M1 (skeleton).** Chimera takes over VulkanMod's main render pass through a forwarding
wrapper. Press **F8** in-game (no screen open) to toggle between chimera's pass and the host's.

## Requirements

- Minecraft 1.21.11 (Fabric)
- [VulkanMod](https://modrinth.com/mod/vulkanmod) 0.6.8+1.21.11 or newer
- Fabric API

## Building

Requires Java 21. Builds are staged directly into `C:\Users\shaya\chimera-builds\` (jar + `LATEST-SHA256.txt`); the workspace `build/libs/` output is not used.

## Roadmap

| Milestone | Scope |
|---|---|
| M1 | Project bootstrap + main-pass takeover |
| M2 | Terrain through chimera-compiled SPIR-V pipelines |
| M3 | Shadow pass + HDR frame + composite/final machinery |
| M4 | Shaderpack loader core (declarations to pipelines) |
| M5 | Complementary conformance run |

## License

[MIT](LICENSE). Links against VulkanMod (LGPL-3.0) as an unmodified library.

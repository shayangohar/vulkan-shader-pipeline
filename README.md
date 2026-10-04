# Chimera

**Iris and OptiFine shader packs for [VulkanMod](https://modrinth.com/mod/vulkanmod).**

VulkanMod replaces Minecraft's OpenGL renderer with Vulkan, but it can't run shader packs. Chimera adds that support. It reads ordinary Iris/OptiFine shader packs, translates their GLSL for Vulkan when the pack loads, and runs them inside VulkanMod's renderer. You don't need to edit or convert anything.

> **Status: early development.** Chimera runs some popular packs today, but it doesn't run every pack, and some effects still draw vanilla. Expect rough edges.

## Requirements

- Minecraft **1.21.11** with [Fabric Loader](https://fabricmc.net/) 0.18 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [VulkanMod](https://modrinth.com/mod/vulkanmod) **0.6.8** or newer
- Java 21

## Installation

1. Install Fabric, Fabric API and VulkanMod.
2. Drop the Chimera jar into your `mods` folder.
3. Put shader packs (`.zip` files or folders) into `.minecraft/shaderpacks`, the same place Iris uses.
4. Launch the game and press **O** to open the shader pack menu.

## Using shader packs

Press **O**, or click **Shader Packs...** in the video settings, to open the pack menu. It works like Iris's: pick a pack, turn shaders on or off, then click **Apply**. Chimera remembers your choice between launches. You can switch packs without restarting the game; the screen pauses briefly while the new pack loads.

If you prefer commands:

| Command | What it does |
|---|---|
| `/chimera pack list` | List the packs in your shaderpacks folder |
| `/chimera pack load <name>` | Load a pack |
| `/chimera pack reload` | Reload the current pack |
| `/chimera pack off` | Turn shaders off |
| `/chimera pack status` | Show the active pack |

### Pack options

Chimera doesn't have an in-game options screen yet, so packs use their default settings. You can override any pack option with a JVM argument:

```
-Dchimera.option.<OPTION_NAME>=<value>
```

For example, `-Dchimera.option.COLORED_LIGHTING=128` turns on Complementary's colored lighting.

## Compatibility

| Pack | Status |
|---|---|
| Complementary Reimagined | Works. Some effects are missing; see the list below. |
| BSL | Works. Some effects are missing; see the list below. |
| Bliss | Loads, but many of its programs still fall back to vanilla. |
| MakeUp UltraFast | Not working yet; fixes are in progress. |
| Solas, Photon | Load, but render vanilla. |

Not supported yet:

- Some programs still draw vanilla: the held hand, the sky, rain and snow.
- A pack that leaves out an optional program doesn't fall back to a related program the way it does in Iris.
- Distant Horizons programs.
- Compute shaders other than `shadowcomp`.
- Geometry and tessellation shaders.
- An in-game screen for pack settings.

When Chimera can't run part of a pack, it draws that part the vanilla way instead of crashing or showing garbage. If a whole pack can't run safely, Chimera falls back to the vanilla renderer.

## Building from source

You need JDK 21.

```bash
./gradlew build
```

The mod jar is written to `build/libs/`.

Bug reports are welcome. Please include your `latest.log`, the pack name and version, and a screenshot. A [RenderDoc](https://renderdoc.org/) capture helps a lot with rendering bugs.

## License

[MIT](LICENSE). Chimera uses VulkanMod (LGPL-3.0) as an unmodified dependency.

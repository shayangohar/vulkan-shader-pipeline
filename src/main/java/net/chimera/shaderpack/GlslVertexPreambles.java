package net.chimera.shaderpack;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import java.util.List;

/**
 * The GLSL Chimera prepends to converted vertex stages: each family's host inputs, transform
 * blocks and the helpers that stand in for the legacy built-ins it reads.
 */
final class GlslVertexPreambles {
    private GlslVertexPreambles() {}

    static final String FULLSCREEN_VERTEX_INPUTS = """
            const vec2 CHIMERA_QUAD[6] = vec2[](vec2(0.0, 0.0), vec2(1.0, 1.0), vec2(1.0, 0.0),
                                                vec2(0.0, 0.0), vec2(0.0, 1.0), vec2(1.0, 1.0));
            vec2 chimeraFullscreenUv() {
                return CHIMERA_QUAD[gl_VertexIndex];
            }
            mat4 chimeraFullscreenProjection() {
                return mat4(2.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0,
                            0.0, 0.0, 1.0, 0.0, -1.0, -1.0, 0.0, 1.0);
            }
            vec4 chimeraFullscreenPosition() {
                return chimeraFullscreenProjection() * vec4(chimeraFullscreenUv(), 0.0, 1.0);
            }
            """;

    /**
     * Live uniforms for the entity vertex path. The converted body keeps
     * its authored references; this emits the same fragment uniform block
     * so the shared pack buffer supplies live values at identical offsets.
     * Emitted whenever the body references any served member (cameraPosition
     * is the common case, not the only one). Fails when a referenced name
     * has no live uniform behind it instead of falling back to a placeholder.
     */
    static String entityCameraUniformBlock(
            String converted,
            List<UniformRegistry.UniformDeclaration> fragmentUniforms
    ) {
        if (fragmentUniforms == null) {
            fragmentUniforms = List.of();
        }
        boolean referenced = false;
        for (UniformRegistry.UniformDeclaration uniform : fragmentUniforms) {
            if (GlslTokenRewriter.containsIdentifier(converted, uniform.name())) {
                referenced = true;
                break;
            }
        }
        if (!referenced) {
            return "";
        }
        return FragmentConverter.entityUniformBlock(fragmentUniforms);
    }

    static final String TERRAIN_VERTEX_PREAMBLE = """
            layout(binding = 0) uniform ViewUBO {
                mat4 MVP;
                mat4 LightMVP;
            };

            layout(binding = 2) uniform SectionData {
                ivec4 SectionOffsets[128];
                vec4 SectionFadeFactors[128];
            };

            layout(push_constant) uniform Push {
                vec3 ModelOffset;
            };

            layout(location = 0) in ivec4 inPositionLight;
            layout(location = 1) in uvec2 inUV;
            layout(location = 2) in uint inPackedColor;
            layout(location = 3) in int inMaterialId;
            layout(location = 4) in int inRenderType;

            const float CHIMERA_UV_SCALE = 1.0 / 32768.0;
            const vec3 CHIMERA_POSITION_SCALE = vec3(1.0 / 2048.0);
            const vec3 CHIMERA_POSITION_BIAS = vec3(4.0);

            vec3 chimeraSectionOffset(int encoded) {
                return vec3(
                    float(bitfieldExtract(encoded, 0, 8)),
                    float(bitfieldExtract(encoded, 16, 8)),
                    float(bitfieldExtract(encoded, 8, 8)));
            }

            vec3 chimeraWorldPosition() {
                int encoded = SectionOffsets[gl_InstanceIndex >> 2][gl_InstanceIndex & 3];
                return fma(vec3(inPositionLight.xyz), CHIMERA_POSITION_SCALE,
                        ModelOffset + chimeraSectionOffset(encoded));
            }

            vec4 chimeraVertexValue() {
                return vec4(chimeraWorldPosition(), 1.0);
            }

            vec4 chimeraColorValue() {
                return unpackUnorm4x8(inPackedColor);
            }

            vec4 chimeraTexCoord0Value() {
                return vec4(vec2(inUV) * CHIMERA_UV_SCALE, 0.0, 1.0);
            }

            // Raw UV2 light coordinates (level * 16, 0..240), as Iris supplies
            // gl_MultiTexCoord1; gl_TextureMatrix[1] normalizes them.
            vec4 chimeraTexCoord1Value() {
                return vec4(
                        vec2(float((uint(inPositionLight.w) >> 4u) & 0xFu),
                             float((uint(inPositionLight.w) >> 12u) & 0xFu)) * 16.0,
                        0.0, 1.0);
            }

            vec2 chimeraMcEntityValue() {
                return vec2(float(inMaterialId), float(inRenderType));
            }

            vec4 chimeraFtransform() {
                return MVP * chimeraVertexValue();
            }

            """;

    /** The legacy prefix plus the M8.1 append-only material attributes. */
    static final String MODERN_TERRAIN_VERTEX_PREAMBLE = TERRAIN_VERTEX_PREAMBLE + """
            layout(location = 5) in uvec2 inMidTexCoord;
            layout(location = 6) in int inMidBlock;
            layout(location = 7) in vec4 inTangent;

            vec2 chimeraMidTexCoordValue() {
                return vec2(inMidTexCoord) / 32768.0;
            }

            uvec2 chimeraMidTexCoordRawValue() {
                return inMidTexCoord;
            }

            ivec4 chimeraMidBlockRawValue() {
                return ivec4(
                        bitfieldExtract(inMidBlock, 0, 8),
                        bitfieldExtract(inMidBlock, 8, 8),
                        bitfieldExtract(inMidBlock, 16, 8),
                        bitfieldExtract(inMidBlock, 24, 8));
            }

            // Iris's at_midBlock: four unnormalized signed bytes, the offset from the
            // vertex to its block's centre in 1/64 blocks, then the block's light
            // emission. Packs divide by 64 themselves.
            vec4 chimeraMidBlockValue() {
                return vec4(chimeraMidBlockRawValue());
            }

            // Iris at_tangent from the quad's UVs, stored as a turn around the
            // normal plus handedness in the normal word's spare byte
            // (TerrainTangentCodec); 0 means the plain reference tangent.
            vec4 chimeraTangentValue() {
                vec3 normal = normalize(inTangent.xyz);
                vec3 axis = abs(normal.y) < 0.999
                        ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                vec3 reference = normalize(cross(axis, normal));
                int code = int(round(inTangent.w * 127.0));
                if (code == 0) return vec4(reference, 1.0);
                float angle = 6.28318530718 * float(abs(code) - 1) / 64.0;
                vec3 tangent = cos(angle) * reference + sin(angle) * cross(normal, reference);
                return vec4(tangent, code < 0 ? -1.0 : 1.0);
            }

            vec3 chimeraNormalValue() {
                return normalize(inTangent.xyz);
            }

            """;

    /** Modern material inputs with the shadow pass's single MVP matrix. */
    static final String MODERN_SHADOW_VERTEX_PREAMBLE =
            MODERN_TERRAIN_VERTEX_PREAMBLE.replace("mat4 LightMVP;\n", "");

    /** The shadow config exposes only the single host MVP matrix at binding 0. */
    static final String SHADOW_VERTEX_PREAMBLE = TERRAIN_VERTEX_PREAMBLE
            .replace("mat4 LightMVP;\n", "");

    /** Host entity transform blocks and the append-only EXTENDED_ENTITY inputs. */
    static final String ENTITY_VERTEX_PREAMBLE = """
            layout(binding = 0) uniform DynamicTransforms {
                mat4 ModelViewMat;
                vec4 ColorModulator;
                vec3 ModelOffset;
                mat4 TextureMat;
            };

            layout(binding = 1) uniform Projection {
                mat4 ProjMat;
            };

            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec4 Color;
            layout(location = 2) in vec2 UV0;
            layout(location = 3) in ivec2 UV1;
            layout(location = 4) in ivec2 UV2;
            layout(location = 5) in vec3 Normal;
            layout(location = 6) in uvec4 EntityIds;
            layout(location = 7) in vec2 MidTexCoord;
            layout(location = 8) in vec4 ChimeraPackedTangent;

            vec4 chimeraEntityVertexValue() {
                return vec4(Position, 1.0);
            }

            vec4 chimeraEntityFtransform() {
                return ProjMat * ModelViewMat * chimeraEntityVertexValue();
            }

            mat4 chimeraEntityInverseModelView() {
                return inverse(ModelViewMat);
            }

            vec3 chimeraEntityNormalValue() {
                float lengthSquared = dot(Normal, Normal);
                return lengthSquared > 1.0e-8
                        ? Normal * inversesqrt(lengthSquared) : vec3(0.0, 1.0, 0.0);
            }

            vec4 chimeraEntityTangentValue() {
                vec3 normal = chimeraEntityNormalValue();
                vec3 candidate = ChimeraPackedTangent.xyz
                        - normal * dot(ChimeraPackedTangent.xyz, normal);
                float lengthSquared = dot(candidate, candidate);
                if (!(lengthSquared > 1.0e-8)) {
                    vec3 absoluteNormal = abs(normal);
                    vec3 axis = absoluteNormal.x <= absoluteNormal.y
                            && absoluteNormal.x <= absoluteNormal.z
                            ? vec3(1.0, 0.0, 0.0)
                            : (absoluteNormal.y <= absoluteNormal.z
                            ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0));
                    candidate = cross(axis, normal);
                    lengthSquared = dot(candidate, candidate);
                }
                float handedness = ChimeraPackedTangent.w < 0.0 ? -1.0 : 1.0;
                return vec4(candidate * inversesqrt(lengthSquared), handedness);
            }

            """;

    /**
     * The shadow bridge's input helpers over EXTENDED_ENTITY and the host transform blocks
     * (Iris SHADOW_ENTITIES_CUTOUT on IrisVertexFormats.ENTITY). The entity layout has no
     * mc_Entity or at_midBlock, so both read GL's unbound-attribute default (0, 0, 0, 1),
     * as under Iris.
     */
    static final String ENTITY_SHADOW_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE + """
            vec4 chimeraVertexValue() {
                return chimeraEntityVertexValue();
            }

            vec4 chimeraColorValue() {
                return Color;
            }

            vec4 chimeraTexCoord0Value() {
                return vec4(UV0, 0.0, 1.0);
            }

            // Raw UV2 light coordinates (level * 16, 0..240), as for terrain.
            vec4 chimeraTexCoord1Value() {
                return vec4(vec2(UV2), 0.0, 1.0);
            }

            vec2 chimeraMcEntityValue() {
                return vec2(0.0);
            }

            vec2 chimeraMidTexCoordValue() {
                return MidTexCoord;
            }

            uvec2 chimeraMidTexCoordRawValue() {
                return uvec2(round(MidTexCoord * 32768.0));
            }

            ivec4 chimeraMidBlockRawValue() {
                return ivec4(0, 0, 0, 1);
            }

            vec4 chimeraMidBlockValue() {
                return vec4(0.0, 0.0, 0.0, 1.0);
            }

            vec3 chimeraNormalValue() {
                return chimeraEntityNormalValue();
            }

            vec4 chimeraTangentValue() {
                return chimeraEntityTangentValue();
            }

            vec4 chimeraFtransform() {
                return chimeraEntityFtransform();
            }

            """;

    /** Block entities use the host block shader's camera-relative offset. */
    static final String BLOCK_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("return vec4(Position, 1.0);", "return vec4(Position + ModelOffset, 1.0);");

    static final String CRUMBLING_VERTEX_PREAMBLE = BLOCK_VERTEX_PREAMBLE
            .replace("layout(location = 3) in ivec2 UV1;\n", "const ivec2 UV1 = ivec2(0);\n")
            .replace("layout(location = 4) in ivec2 UV2;", "layout(location = 3) in ivec2 UV2;")
            .replace("layout(location = 5) in vec3 Normal;", "layout(location = 4) in vec3 Normal;")
            .replace("layout(location = 6) in uvec4 EntityIds;", "const uvec4 EntityIds = uvec4(0);")
            .replace("layout(location = 7) in vec2 MidTexCoord;", "#define MidTexCoord UV0")
            .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                    "const vec4 ChimeraPackedTangent = vec4(0.0, 0.0, 0.0, 1.0);");

    static final String HAND_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("vec4 chimeraEntityVertexValue()", """
                    mat4 chimeraHandProjection() {
                        mat4 legacy = ProjMat;
                        legacy[0].z = 2.0 * ProjMat[0].z - ProjMat[0].w;
                        legacy[1].z = 2.0 * ProjMat[1].z - ProjMat[1].w;
                        legacy[2].z = 2.0 * ProjMat[2].z - ProjMat[2].w;
                        legacy[3].z = 2.0 * ProjMat[3].z - ProjMat[3].w;
                        return legacy;
                    }
                    vec4 chimeraEntityVertexValue()""")
            .replace("return ProjMat * ModelViewMat", "return chimeraHandProjection() * ModelViewMat");

    /** Iris VanillaTransformer supplies +Z for a host format without a normal. */
    static final String PARTICLE_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("layout(location = 1) in vec4 Color;", "layout(location = 2) in vec4 Color;")
            .replace("layout(location = 2) in vec2 UV0;", "layout(location = 1) in vec2 UV0;")
            .replace("layout(location = 3) in ivec2 UV1;", "const ivec2 UV1 = ivec2(0);")
            .replace("layout(location = 4) in ivec2 UV2;", "layout(location = 3) in ivec2 UV2;")
            .replace("layout(location = 5) in vec3 Normal;", "const vec3 Normal = vec3(0.0, 0.0, 1.0);")
            .replace("layout(location = 6) in uvec4 EntityIds;", "const uvec4 EntityIds = uvec4(0);")
            .replace("layout(location = 7) in vec2 MidTexCoord;", "#define MidTexCoord UV0")
            .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                    "const vec4 ChimeraPackedTangent = vec4(0.0, 0.0, 0.0, 1.0);");

    /**
     * DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, drawn as quads. Iris supplies the
     * missing inputs as for any host format: UV0 (0.5, 0.5) and full light (240, 240). The
     * line offset is zero except while {@link #prepareLineVertex} widens a fallback program.
     */
    static final String LINE_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("layout(location = 2) in vec2 UV0;", "const vec2 UV0 = vec2(0.5);")
            .replace("layout(location = 3) in ivec2 UV1;", "const ivec2 UV1 = ivec2(0);")
            .replace("layout(location = 4) in ivec2 UV2;", "const ivec2 UV2 = ivec2(240);")
            .replace("layout(location = 5) in vec3 Normal;", "layout(location = 2) in vec3 Normal;\n"
                    + "layout(location = 3) in float LineWidth;\n"
                    + "#define chimeraLineWidth LineWidth\n"
                    + "#define chimeraLineDirection Normal\n"
                    + "#define chimeraLineVertexIndex gl_VertexIndex")
            .replace("layout(location = 6) in uvec4 EntityIds;", "const uvec4 EntityIds = uvec4(0);")
            .replace("layout(location = 7) in vec2 MidTexCoord;", "#define MidTexCoord UV0")
            .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                    "const vec4 ChimeraPackedTangent = vec4(0.0, 0.0, 0.0, 1.0);");

    /** Iris VanillaTransformer VIEW_SHRINK, applied to the model-view matrix of widened lines. */
    static final String LINE_WIDEN_GLOBALS = """
            vec3 chimeraLineOffset = vec3(0.0);
            const mat4 chimeraLineViewScale = mat4(
                    1.0 - (1.0 / 256.0), 0.0, 0.0, 0.0,
                    0.0, 1.0 - (1.0 / 256.0), 0.0, 0.0,
                    0.0, 0.0, 1.0 - (1.0 / 256.0), 0.0,
                    0.0, 0.0, 0.0, 1.0);
            """;

    /**
     * Iris VanillaTransformer iris_widen_lines: the program runs once at the far end of the
     * segment and once at the near end, and the near end moves across the screen-space line.
     */
    static final String LINE_WIDEN_MAIN = """

            void main() {
                chimeraLineOffset = chimeraLineDirection;
                chimeraLineMain();
                vec4 linePosEnd = gl_Position;
                gl_Position = vec4(0.0);
                chimeraLineOffset = vec3(0.0);
                chimeraLineMain();
                vec4 linePosStart = gl_Position;
                vec2 chimeraScreenSize = vec2(viewWidth, viewHeight);
                vec3 ndc1 = linePosStart.xyz / linePosStart.w;
                vec3 ndc2 = linePosEnd.xyz / linePosEnd.w;
                vec2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * chimeraScreenSize);
                vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x)
                        * chimeraLineWidth / chimeraScreenSize;
                if (lineOffset.x < 0.0) {
                    lineOffset *= -1.0;
                }
                if (chimeraLineVertexIndex % 2 == 0) {
                    gl_Position = vec4((ndc1 + vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
                } else {
                    gl_Position = vec4((ndc1 - vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
                }
            }
            """;

    /**
     * DefaultVertexFormat.POSITION_TEX glint layers. Iris supplies the missing inputs: +Z
     * normal and full light; gl_Color is the host ColorModulator with the glint strength in
     * its alpha ({@link #prepareGlintVertex}).
     */
    static final String GLINT_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("layout(location = 1) in vec4 Color;", "const vec4 Color = vec4(1.0);")
            .replace("layout(location = 2) in vec2 UV0;", "layout(location = 1) in vec2 UV0;")
            .replace("layout(location = 3) in ivec2 UV1;", "const ivec2 UV1 = ivec2(0);")
            .replace("layout(location = 4) in ivec2 UV2;", "const ivec2 UV2 = ivec2(240);")
            .replace("layout(location = 5) in vec3 Normal;", "const vec3 Normal = vec3(0.0, 0.0, 1.0);")
            .replace("layout(location = 6) in uvec4 EntityIds;", "const uvec4 EntityIds = uvec4(0);")
            .replace("layout(location = 7) in vec2 MidTexCoord;", "#define MidTexCoord UV0")
            .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                    "const vec4 ChimeraPackedTangent = vec4(0.0, 0.0, 0.0, 1.0);");

    /** The glint layout under the hand's legacy projection, as {@link #HAND_VERTEX_PREAMBLE}. */
    static final String GLINT_HAND_VERTEX_PREAMBLE = GLINT_VERTEX_PREAMBLE
            .replace("vec4 chimeraEntityVertexValue()", """
                    mat4 chimeraHandProjection() {
                        mat4 legacy = ProjMat;
                        legacy[0].z = 2.0 * ProjMat[0].z - ProjMat[0].w;
                        legacy[1].z = 2.0 * ProjMat[1].z - ProjMat[1].w;
                        legacy[2].z = 2.0 * ProjMat[2].z - ProjMat[2].w;
                        legacy[3].z = 2.0 * ProjMat[3].z - ProjMat[3].w;
                        return legacy;
                    }
                    vec4 chimeraEntityVertexValue()""")
            .replace("return ProjMat * ModelViewMat", "return chimeraHandProjection() * ModelViewMat");

    /**
     * DefaultVertexFormat.POSITION_COLOR lightning and dragon rays. Iris supplies the missing
     * inputs as for any host format: UV0 (0.5, 0.5), full light (240, 240) and +Z.
     */
    static final String LIGHTNING_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("layout(location = 2) in vec2 UV0;", "const vec2 UV0 = vec2(0.5);")
            .replace("layout(location = 3) in ivec2 UV1;", "const ivec2 UV1 = ivec2(0);")
            .replace("layout(location = 4) in ivec2 UV2;", "const ivec2 UV2 = ivec2(240);")
            .replace("layout(location = 5) in vec3 Normal;", "const vec3 Normal = vec3(0.0, 0.0, 1.0);")
            .replace("layout(location = 6) in uvec4 EntityIds;", "const uvec4 EntityIds = uvec4(0);")
            .replace("layout(location = 7) in vec2 MidTexCoord;", "#define MidTexCoord UV0")
            .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                    "const vec4 ChimeraPackedTangent = vec4(0.0, 0.0, 0.0, 1.0);");

    /**
     * DefaultVertexFormat.BLOCK beacon beams: position, colour, texture, light and normal, at
     * the crumbling layout's locations, with the host colour modulator as for any native layout.
     */
    static final String BEACON_VERTEX_PREAMBLE = "// DefaultVertexFormat.BLOCK beacon beam\n"
            + CRUMBLING_VERTEX_PREAMBLE;

    /**
     * DefaultVertexFormat.POSITION_COLOR_LIGHTMAP leads. Iris supplies UV0 (0.5, 0.5) and +Z;
     * the light coordinates are the lead's own.
     */
    static final String LEASH_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("layout(location = 2) in vec2 UV0;", "const vec2 UV0 = vec2(0.5);")
            .replace("layout(location = 3) in ivec2 UV1;", "const ivec2 UV1 = ivec2(0);")
            .replace("layout(location = 4) in ivec2 UV2;", "layout(location = 2) in ivec2 UV2;")
            .replace("layout(location = 5) in vec3 Normal;", "const vec3 Normal = vec3(0.0, 0.0, 1.0);")
            .replace("layout(location = 6) in uvec4 EntityIds;", "const uvec4 EntityIds = uvec4(0);")
            .replace("layout(location = 7) in vec2 MidTexCoord;", "#define MidTexCoord UV0")
            .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                    "const vec4 ChimeraPackedTangent = vec4(0.0, 0.0, 0.0, 1.0);");

    /**
     * DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP glyph quads in the shadow pass. Iris draws
     * them on its GLYPH layout, whose normal, entity ids, mid-coordinate and tangent it computes
     * per quad; the host mesh has none of them, so they read +Z (VanillaTransformer's normal for
     * a layout without one), 0, 0 and (0, 0, 0, 1).
     */
    static final String TEXT_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("layout(location = 3) in ivec2 UV1;", "const ivec2 UV1 = ivec2(0);")
            .replace("layout(location = 4) in ivec2 UV2;", "layout(location = 3) in ivec2 UV2;")
            .replace("layout(location = 5) in vec3 Normal;", "const vec3 Normal = vec3(0.0, 0.0, 1.0);")
            .replace("layout(location = 6) in uvec4 EntityIds;", "const uvec4 EntityIds = uvec4(0);")
            .replace("layout(location = 7) in vec2 MidTexCoord;", "const vec2 MidTexCoord = vec2(0.0);")
            .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                    "const vec4 ChimeraPackedTangent = vec4(0.0, 0.0, 0.0, 1.0);");

    /**
     * DefaultVertexFormat.POSITION boat water mask. Iris supplies the host colour modulator,
     * UV0 (0.5, 0.5), full light (240, 240) and +Z.
     */
    static final String WATER_MASK_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("layout(location = 1) in vec4 Color;", "const vec4 Color = vec4(1.0);")
            .replace("layout(location = 2) in vec2 UV0;", "const vec2 UV0 = vec2(0.5);")
            .replace("layout(location = 3) in ivec2 UV1;", "const ivec2 UV1 = ivec2(0);")
            .replace("layout(location = 4) in ivec2 UV2;", "const ivec2 UV2 = ivec2(240);")
            .replace("layout(location = 5) in vec3 Normal;", "const vec3 Normal = vec3(0.0, 0.0, 1.0);")
            .replace("layout(location = 6) in uvec4 EntityIds;", "const uvec4 EntityIds = uvec4(0);")
            .replace("layout(location = 7) in vec2 MidTexCoord;", "const vec2 MidTexCoord = vec2(0.0);")
            .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                    "const vec4 ChimeraPackedTangent = vec4(0.0, 0.0, 0.0, 1.0);");

    /**
     * The POSITION_TEX glint mesh under Iris's shadow program for it, SHADOW_ENTITIES_CUTOUT on
     * the ENTITY layout: the mesh supplies position and texture coordinates, and every other
     * ENTITY input reads GL's unbound-attribute default (0, 0, 0, 1).
     */
    static final String SHADOW_GLINT_VERTEX_PREAMBLE = ENTITY_VERTEX_PREAMBLE
            .replace("layout(location = 1) in vec4 Color;", "const vec4 Color = vec4(0.0, 0.0, 0.0, 1.0);")
            .replace("layout(location = 2) in vec2 UV0;", "layout(location = 1) in vec2 UV0;")
            .replace("layout(location = 3) in ivec2 UV1;", "const ivec2 UV1 = ivec2(0);")
            .replace("layout(location = 4) in ivec2 UV2;", "const ivec2 UV2 = ivec2(0);")
            .replace("layout(location = 5) in vec3 Normal;", "const vec3 Normal = vec3(0.0);")
            .replace("layout(location = 6) in uvec4 EntityIds;", "const uvec4 EntityIds = uvec4(0);")
            .replace("layout(location = 7) in vec2 MidTexCoord;", "const vec2 MidTexCoord = vec2(0.0);")
            .replace("layout(location = 8) in vec4 ChimeraPackedTangent;",
                    "const vec4 ChimeraPackedTangent = vec4(0.0, 0.0, 0.0, 1.0);");

    /**
     * The shadow bridge's input preamble for a host-layout shadow lane: the layout's inputs, then
     * the chimera*Value helpers the shadow converter reads. As under Iris's VanillaTransformer,
     * gl_Color is the vertex colour times the host colour modulator, mc_Entity, mc_midTexCoord,
     * at_tangent and at_midBlock read their unbound defaults, and a widened line sees +Z for
     * gl_Normal.
     */
    static String shadowLanePreamble(FamilyAdapterPlan.VertexContract contract) {
        String inputs = switch (contract) {
            case HOST_LINE -> LINE_VERTEX_PREAMBLE;
            case HOST_LIGHTNING -> LIGHTNING_VERTEX_PREAMBLE;
            case HOST_LEASH, HOST_TEXT_BACKGROUND -> LEASH_VERTEX_PREAMBLE;
            case HOST_GLINT -> SHADOW_GLINT_VERTEX_PREAMBLE;
            case HOST_TEXT -> TEXT_VERTEX_PREAMBLE;
            case HOST_WATER_MASK -> WATER_MASK_VERTEX_PREAMBLE;
            default -> throw new IllegalArgumentException("no shadow lane on contract: " + contract);
        };
        String normal = contract == FamilyAdapterPlan.VertexContract.HOST_LINE ? "vec3(0.0, 0.0, 1.0)" : "Normal";
        return inputs + """
                vec4 chimeraVertexValue() {
                    return chimeraEntityVertexValue();
                }

                vec4 chimeraColorValue() {
                    return Color * ColorModulator;
                }

                vec4 chimeraTexCoord0Value() {
                    return vec4(UV0, 0.0, 1.0);
                }

                // Raw UV2 light coordinates (level * 16, 0..240), as for terrain.
                vec4 chimeraTexCoord1Value() {
                    return vec4(vec2(UV2), 0.0, 1.0);
                }

                vec2 chimeraMcEntityValue() {
                    return vec2(0.0);
                }

                vec2 chimeraMidTexCoordValue() {
                    return vec2(0.0);
                }

                uvec2 chimeraMidTexCoordRawValue() {
                    return uvec2(0u);
                }

                ivec4 chimeraMidBlockRawValue() {
                    return ivec4(0, 0, 0, 1);
                }

                vec4 chimeraMidBlockValue() {
                    return vec4(0.0, 0.0, 0.0, 1.0);
                }

                vec3 chimeraNormalValue() {
                    return %s;
                }

                vec4 chimeraTangentValue() {
                    return vec4(0.0, 0.0, 0.0, 1.0);
                }

                vec4 chimeraFtransform() {
                    return chimeraEntityFtransform();
                }

                """.formatted(normal);
    }

    /**
     * Lanes drawn on the host's own vertex layout (particles, weather, sky and the host-layout
     * lanes): as in Iris's VanillaTransformer, gl_Color carries the host ColorModulator and
     * gl_NormalMatrix is the model-view normal matrix.
     */
    static boolean nativeLayoutPreamble(String preamble) {
        return preamble == PARTICLE_VERTEX_PREAMBLE || preamble == LINE_VERTEX_PREAMBLE
                || preamble == GLINT_VERTEX_PREAMBLE || preamble == LIGHTNING_VERTEX_PREAMBLE
                || preamble == BEACON_VERTEX_PREAMBLE || preamble == LEASH_VERTEX_PREAMBLE
                || isSkyPreamble(preamble);
    }

    static boolean isSkyPreamble(String preamble) {
        return preamble.contains("mat4 chimeraSkyProjection()");
    }

    static String skyPreamble(FamilyAdapterPlan.VertexContract contract) {
        var host = switch (contract) {
            case SKY_POSITION -> DefaultVertexFormat.POSITION;
            case SKY_POSITION_COLOR -> DefaultVertexFormat.POSITION_COLOR;
            case SKY_POSITION_UV -> DefaultVertexFormat.POSITION_TEX;
            case SKY_POSITION_COLOR_UV -> DefaultVertexFormat.POSITION_TEX_COLOR;
            default -> throw new IllegalArgumentException("unsupported sky vertex contract");
        };
        // Reuse the native transform and legacy clip-depth machinery. Sky has
        // no material attributes; Iris defaults missing colour/UV/light/normal.
        String preamble = HAND_VERTEX_PREAMBLE.replace("chimeraHandProjection", "chimeraSkyProjection");
        String[] types = {"vec4", "vec2", "ivec2", "ivec2", "vec3", "uvec4", "vec2", "vec4"};
        String[] names = {"Color", "UV0", "UV1", "UV2", "Normal", "EntityIds", "MidTexCoord", "ChimeraPackedTangent"};
        String[] defaults = {"vec4(1.0)", "vec2(0.0)", "ivec2(0)", "ivec2(0)",
                "vec3(0.0, 0.0, 1.0)", "uvec4(0)", "vec2(0.0)", "vec4(0.0, 0.0, 0.0, 1.0)"};
        for (int i = 0; i < names.length; i++) {
            int location = host.getElementAttributeNames().indexOf(names[i]);
            String declaration = location < 0 ? "const " + types[i] + " " + names[i] + " = " + defaults[i] + ";"
                    : "layout(location = " + location + ") in " + types[i] + " " + names[i] + ";";
            preamble = preamble.replace("layout(location = " + (i + 1) + ") in " + types[i] + " " + names[i] + ";", declaration);
        }
        return preamble;
    }

    /** Host VulkanMod clouds are ordinary position/color geometry. */
    static final String CLOUD_VERTEX_PREAMBLE = """
            layout(binding = 0) uniform DynamicTransforms {
                mat4 ModelViewMat;
                vec4 ColorModulator;
                vec3 ModelOffset;
                mat4 TextureMat;
            };
            layout(binding = 1) uniform Projection { mat4 ProjMat; };
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec4 Color;
            vec4 chimeraVertexValue() { return vec4(Position, 1.0); }
            vec4 chimeraColorValue() { return Color; }
            vec4 chimeraTexCoord0Value() { return vec4(0.0); }
            vec4 chimeraTexCoord1Value() { return vec4(0.0); }
            vec4 chimeraFtransform() { return ProjMat * ModelViewMat * chimeraVertexValue(); }
            """;
}

package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GL matches an interface block across stages by its block name and its members by name.
 * RenderPearl, Reverie and Shrimple pass their varyings in blocks, so every program that used one
 * fell back with PROGRAM_VARYING_TYPE_UNSUPPORTED, entities and block among them.
 */
final class InterfaceBlockFlattenerTest {
    @Test
    void aNamedBlockMatchesAcrossStagesWhateverTheInstanceIsCalled() {
        InterfaceBlockFlattener.Result vertex = InterfaceBlockFlattener.flatten("""
                #version 330
                out Data {
                    vec2 texcoord;
                    flat float Id;
                } DataOut;
                void main() { DataOut.texcoord = vec2(0.0); DataOut.Id = 1.0; gl_Position = vec4(0.0); }
                """);
        InterfaceBlockFlattener.Result fragment = InterfaceBlockFlattener.flatten("""
                #version 330
                in Data {
                    vec2 texcoord;
                    flat float Id;
                } DataIn;
                out vec4 color;
                void main() { color = vec4(DataIn.texcoord.x, DataIn.Id, 0.0, 1.0); }
                """);
        assertEquals(List.of("Data"), vertex.blocks());
        assertTrue(vertex.source().contains("out vec2 Data_texcoord;\nflat out float Data_Id;"), vertex.source());
        assertTrue(vertex.source().contains("Data_texcoord = vec2(0.0); Data_Id = 1.0;"), vertex.source());
        assertTrue(fragment.source().contains("in vec2 Data_texcoord;\nflat in float Data_Id;"), fragment.source());
        assertTrue(fragment.source().contains("vec4(Data_texcoord.x, Data_Id, 0.0, 1.0)"), fragment.source());
    }

    /** Member layouts go with the block; Chimera locates every varying by name. */
    @Test
    void anUnnamedBlockRenamesItsMembersAndDropsTheirLayouts() {
        InterfaceBlockFlattener.Result flattened = InterfaceBlockFlattener.flatten("""
                #version 430
                layout(location = 2) out TBN {
                    layout(location = 0, component = 0) flat uint normal;
                    layout(location = 0, component = 1) flat uint tangent;
                };
                uint pack(uint normal) { return normal; }
                void main() { normal = 1u; tangent = pack(normal); gl_Position = vec4(0.0); }
                """);
        assertTrue(flattened.source().contains("flat out uint TBN_normal;\nflat out uint TBN_tangent;"),
                flattened.source());
        assertTrue(flattened.source().contains("uint pack(uint normal) { return normal; }"),
                "a parameter of the same name is not the member: " + flattened.source());
        assertTrue(flattened.source().contains("TBN_normal = 1u; TBN_tangent = pack(TBN_normal);"),
                flattened.source());
        assertFalse(flattened.source().contains("layout"), flattened.source());
    }

    @Test
    void aRedeclaredPerVertexIsDroppedAndAnArrayedBlockIsKept() {
        InterfaceBlockFlattener.Result perVertex = InterfaceBlockFlattener.flatten("""
                #version 430
                out gl_PerVertex { vec4 gl_Position; };
                void main() { gl_Position = vec4(0.0); }
                """);
        assertEquals(List.of("gl_PerVertex"), perVertex.blocks());
        assertFalse(perVertex.source().contains("gl_PerVertex"), perVertex.source());
        assertTrue(perVertex.source().contains("gl_Position = vec4(0.0);"), perVertex.source());

        String geometryInput = """
                #version 430
                in VertexData { vec2 uv; } v[];
                void main() {}
                """;
        assertEquals(geometryInput, InterfaceBlockFlattener.flatten(geometryInput).source());
    }

    /** RenderPearl's shape: an entity program whose stages pass a flat uint in a named block. */
    @Test
    void anEntityProgramWithBlocksRuns(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_entities.vsh"), """
                #version 430 compatibility
                out gl_PerVertex { vec4 gl_Position; };
                out VertexData { layout(location = 0, component = 0) flat uint tint; vec2 uv; } v;
                void main() {
                    v.tint = 3u;
                    v.uv = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_entities.fsh"), """
                #version 430 compatibility
                uniform sampler2D gtexture;
                in VertexData { layout(location = 0, component = 0) flat uint tint; vec2 uv; } v;
                /* RENDERTARGETS: 0 */
                layout(location = 0) out vec4 color;
                void main() { color = texture(gtexture, v.uv) * float(v.tint); }
                """);
        PackProgramPlan entities = PackProbe.analyze(root).plan().program("gbuffers_entities");
        assertTrue(entities.executable() && entities.deviations().contains("INTERFACE_BLOCK_FLATTENED:VertexData"),
                entities.deviations().toString());
        assertTrue(entities.deviations().stream().noneMatch(value -> value.startsWith("PROGRAM_VARYING_TYPE")),
                entities.deviations().toString());
    }
}

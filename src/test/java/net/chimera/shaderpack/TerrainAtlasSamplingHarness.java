package net.chimera.shaderpack;

import java.util.List;
import java.util.Set;
import org.lwjgl.util.shaderc.Shaderc;

public final class TerrainAtlasSamplingHarness {
    public static void main(String[] args) {
        String header = "#version 460\nlayout(binding=0) uniform sampler2D tex;\nlayout(location=0) out vec4 color;\n";
        String source = header + "void main(){ color=texture(tex,vec2(0.4)); }";
        String rewritten = rewrite(source);
        require(!source.equals(rewritten), "eligible sample unchanged");
        compile(rewritten);
        for (String call : List.of("textureLod(tex,vec2(0),0.0)",
                "texelFetch(tex,ivec2(0),0)", "textureProj(tex,vec3(1))", "texture(tex,vec2(0),1.0)")) {
            String explicit = header + "void main(){color=" + call + ";}";
            require(explicit.equals(rewrite(explicit)), "explicit/unsupported operation changed: " + call);
        }
        String shadow = header + "vec4 sampleOther(sampler2D tex,vec2 uv){return texture(tex,uv);}\nvoid main(){color=sampleOther(tex,vec2(0));}";
        require(shadow.equals(rewrite(shadow)), "sampler parameter rewritten");
        String local = header + "void main(){vec2 tex=vec2(0); color=vec4(tex,0,1);}";
        require(local.equals(rewrite(local)), "local shadow rewritten");
        String untouched = header + "// texture(tex, uv)\n#define UNCALLED texture(tex, vec2(0))\nvoid main(){color=vec4(1);}";
        require(untouched.equals(rewrite(untouched)), "comments/directives changed");
        String collision = header + "float chimeraAtlasSample=0.0; void main(){color=texture(tex,vec2(chimeraAtlasSample));}";
        compile(rewrite(collision));
        String nested = header + "void main(){color=texture(tex,texture(tex,vec2(0)).xy);}";
        compile(rewrite(nested));
        for (String call : List.of("texture2D", "chimeraTexture")) {
            require(!source.replace("texture(",call+"(").equals(rewrite(source.replace("texture(",call+"("))), "call form missed: " + call);
        }
        for (var stage : UniformRegistry.Stage.values()) {
            var iface = new UniformRegistry.ProgramInterface(stage,List.of(),List.of(new UniformRegistry.SamplerBinding("tex",0)),List.of());
            boolean terrain = stage == UniformRegistry.Stage.GEOMETRY || stage == UniformRegistry.Stage.TRANSLUCENT;
            require(PackResourcePlan.terrainAtlasSamplers("gbuffers_terrain",iface,null).contains("tex") == terrain, "stage admission: " + stage);
            // Stitched companions share the atlas topology and take the same reconstruction.
            var material = new UniformRegistry.ProgramInterface(stage,List.of(),List.of(
                    new UniformRegistry.SamplerBinding("normals",SelectorNamespace.NORMALS_SLOT),
                    new UniformRegistry.SamplerBinding("specular",SelectorNamespace.SPECULAR_SLOT),
                    new UniformRegistry.SamplerBinding("noisetex",7)),List.of());
            var admitted = PackResourcePlan.terrainAtlasSamplers("gbuffers_terrain",material,null);
            require(admitted.equals(terrain ? Set.of("normals","specular") : Set.of()), "material admission: " + stage + " " + admitted);
        }
        var misplaced = new UniformRegistry.ProgramInterface(UniformRegistry.Stage.GEOMETRY,List.of(),
                List.of(new UniformRegistry.SamplerBinding("normals",SelectorNamespace.SPECULAR_SLOT)),List.of());
        require(PackResourcePlan.terrainAtlasSamplers("gbuffers_terrain",misplaced,null).isEmpty(), "unmapped companion slot admitted");
        verifyExplicitGradientAtlasSamples(header);
        System.out.println("Terrain atlas scoped rewrite and shaderc compile: PASS");
    }

    /** Explicit-gradient atlas contract: four-argument textureGrad on an
     * eligible sampler takes the gradient helper with authored expressions
     * preserved verbatim; everything else fails closed. */
    private static void verifyExplicitGradientAtlasSamples(String header) {
        String grad = header + "void main(){vec2 uv=vec2(0.4);vec2 du=vec2(0.01);vec2 dv=vec2(0.02);"
                + "color=textureGrad(tex,uv+du,du*2.0,dv);}";
        String gradRewritten = rewrite(grad);
        require(!grad.equals(gradRewritten), "eligible explicit-gradient sample unchanged");
        require(gradRewritten.contains("chimeraAtlasSampleGrad(tex,uv+du,du*2.0,dv)"),
                "authored gradient expressions not preserved verbatim");
        require(countOccurrences(gradRewritten, "vec4 chimeraAtlasSampleGrad(") == 1,
                "gradient helper family duplicated");
        require(gradRewritten.contains(
                        "return textureGrad(chimeraAtlasTex, (chimeraAtlasCenter + chimeraAtlasOffset) * chimeraAtlasPixel, chimeraAtlasDu, chimeraAtlasDv);"),
                "gradient helper lost the pixel-center correction");
        compile(gradRewritten);
        String other = header + "void main(){color=textureGrad(tex,vec2(0),vec2(1),vec2(1));}";
        require(other.equals(rewrite(other, Set.of("unrelated"))), "non-atlas sampler gradient rewritten");
        String paramShadow = header + "vec4 sampleOther(sampler2D tex,vec2 uv,vec2 du,vec2 dv){return textureGrad(tex,uv,du,dv);}\nvoid main(){color=sampleOther(tex,vec2(0),vec2(1),vec2(1));}";
        require(paramShadow.equals(rewrite(paramShadow)), "shadowed sampler gradient rewritten");
        String mixed = header + "void main(){vec2 uv=vec2(0.4);color=texture(tex,uv)+textureGrad(tex,uv,vec2(1),vec2(1));}";
        String mixedRewritten = rewrite(mixed);
        require(countOccurrences(mixedRewritten, "vec4 chimeraAtlasSampleGrad(") == 1
                        && countOccurrences(mixedRewritten, "vec4 chimeraAtlasSample(") == 1,
                "mixed call shapes did not produce one helper family");
        require(mixedRewritten.contains("chimeraAtlasSample(tex,uv)")
                        && mixedRewritten.contains("chimeraAtlasSampleGrad(tex,uv,vec2(1),vec2(1))"),
                "mixed call shapes misrewritten");
        require(mixedRewritten.contains(
                        "return chimeraAtlasSampleGrad(chimeraAtlasTex, chimeraAtlasUv, dFdx(chimeraAtlasUv), dFdy(chimeraAtlasUv));"),
                "plain helper does not delegate to the gradient helper");
        compile(mixedRewritten);
        String badArity = header + "void main(){color=textureGrad(tex,vec2(0),vec2(1));}";
        require(badArity.equals(rewrite(badArity)), "unsupported gradient overload rewritten");
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0, index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
    private static String rewrite(String source) { return GlslTokenRewriter.rewriteTerrainAtlasSamples(source,Set.of("tex")); }
    private static String rewrite(String source, Set<String> samplers) { return GlslTokenRewriter.rewriteTerrainAtlasSamples(source,samplers); }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    private static void compile(String source) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long result = Shaderc.shaderc_compile_into_spv(compiler,source,Shaderc.shaderc_glsl_fragment_shader,"atlas.fsh","main",0);
        try {
            require(result != 0 && Shaderc.shaderc_result_get_compilation_status(result) == Shaderc.shaderc_compilation_status_success,
                    result == 0 ? "no shader result" : Shaderc.shaderc_result_get_error_message(result));
        } finally { if(result != 0) Shaderc.shaderc_result_release(result); Shaderc.shaderc_compiler_release(compiler); }
    }
}

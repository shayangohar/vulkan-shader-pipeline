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
        for (String call : List.of("textureGrad(tex,vec2(0),vec2(1),vec2(1))", "textureLod(tex,vec2(0),0.0)",
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
        }
        System.out.println("Terrain atlas scoped rewrite and shaderc compile: PASS");
    }
    private static String rewrite(String source) { return GlslTokenRewriter.rewriteTerrainAtlasSamples(source,Set.of("tex")); }
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

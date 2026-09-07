#version 120
uniform sampler2D colortex0;
uniform sampler2D colortex1;
varying vec2 texcoord;

void main() {
    vec4 scene = texture2D(colortex0, texcoord);
    vec4 marker = texture2D(colortex1, texcoord);
    gl_FragColor = vec4(scene.rgb + marker.rgb * 0.01, scene.a);
}

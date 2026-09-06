#version 120
uniform sampler2D marker;
uniform sampler2D colortex0;
varying vec2 texcoord;

void main() {
    vec4 scene = texture2D(colortex0, texcoord);
    vec4 markerColor = texture2D(marker, texcoord);
    gl_FragColor = vec4(scene.rgb + markerColor.rgb * 0.01, scene.a);
}

#version 120

uniform sampler2D texture;
uniform sampler2D lightmap;
varying vec4 entityColor;
varying vec2 entityUv;
varying float entityMarker;

void main() {
    vec4 albedo = texture2D(texture, entityUv);
    vec4 light = texture2D(lightmap, vec2(0.5));
    vec3 marker = entityMarker > 0.5 ? vec3(0.2, 0.03, 0.0) : vec3(0.0);
    gl_FragColor = vec4(albedo.rgb * entityColor.rgb * light.rgb + marker,
            albedo.a * entityColor.a);
}

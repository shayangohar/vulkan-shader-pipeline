#version 120e
varying vec2 texcoord;
uniform sampler2D colortex0;
uniform sampler2D shadowtex0;
void main() {
    vec4 color = texture2D(colortex0, texcoord);
    // Constant-bias shadow read: proof that slot 5 (shadow map) is bound in
    // the post pipeline. The shadow map lives in LIGHT space — sampling it
    // with screen-space quad UVs paints the light-POV render over the display
    // (frozen dark grass silhouettes + translucent black patches that sweep
    // across the screen as the camera pans). A constant sample keeps the
    // machinery proof without the overlay.
    float lit = 0.6 + 0.4 * texture2D(shadowtex0, vec2(0.5, 0.5)).r;
    gl_FragColor = vec4(color.rgb * vec3(1.0, 0.3, 0.3) * lit, color.a);
}
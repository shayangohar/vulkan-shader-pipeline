vec3 m62CatalogMarker(vec3 color, float wet, float time) {
    float signal = 0.02 + fract(abs(color.x + color.z) * 0.000001
            + wet * 0.01 + time * 0.000001) * 0.04;
    return vec3(signal, signal * 0.1, signal);
}

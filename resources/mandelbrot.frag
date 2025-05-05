#version 120

//This is an older fallback shader that only uses float values, but works on most graphic cards,
//including many integrated graphics cards.
//It lacks the customizability and palette options the newer double-precision shader,
//but still works for testing speed compared to CPU rendering
//Gets blurry fairly quickly due to being limited to float math instead of doubles

uniform vec2 u_resolution;
uniform vec2 u_center;
uniform float u_zoom;
uniform int u_maxIter;
uniform int colorMode; // 0 = Escape, 1 = Smooth, 2 = Orbit

void main() {
    // Map pixel to complex plane
    vec2 uv = (gl_FragCoord.xy / u_resolution - 0.5) * u_zoom;
    vec2 c = u_center + uv;
    vec2 z = vec2(0.0);

    int iter = u_maxIter;
    float trap = 1000.0; // orbit trap
    float mag = 0.0;


    for (int i = 0; i < u_maxIter; i++) {
        float x = z.x * z.x - z.y * z.y + c.x;
        float y = 2.0 * z.x * z.y + c.y;
        z = vec2(x, y);

        trap = min(trap, length(z));

        if (dot(z, z) > 4.0) {
            iter = i;
            mag = dot(z, z);
            break;
        }
    }


    vec3 color;
    /*color = vec3(gl_FragCoord.x / u_resolution.x, gl_FragCoord.y / u_resolution.y, 0.5);*/
    //commented out test line, creates a gradient full-screen rectangle
    if (colorMode == 0) {
        float t = float(iter) / float(u_maxIter);
        t = clamp(t, 0.0, 1.0);
        color = vec3(t); // grayscale
    } else if (colorMode == 1) {
        if (mag <= 0.0) mag = 1.0; // prevent log(0)
        float smooth = float(iter) + 1.0 - log(log(mag)) / log(2.0);
    float t = smooth / float(u_maxIter);
    color = vec3(t * 0.5, t * 0.8, 1.0 - t);
    } else if (colorMode == 2) {
        float v = exp(-trap * 2.0); // softened trap multiplier
        color = vec3(0.2, 0.7, 1.0) * v;
    }

    gl_FragColor = vec4(color, 1.0);
}

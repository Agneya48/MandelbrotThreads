#version 430 core
#extension GL_ARB_gpu_shader_fp64 : enable

//This version of the shader will be used if the computer's GPU supports double-precision calculations
//It allows for near-real time zooms, with equivalent detail at deep zooms to the CPU rendering mode
//Author: Josh Hampton, hamptojt@mail.uc.edu

uniform vec2 u_resolution;  // sent as float
uniform dvec2 u_center;
uniform double u_zoom;
uniform int u_maxIter;
uniform int colorMode; //0 Escape, 1 Smooth, 2 Orbit Trap
uniform int paletteMode; // 0 = grayscale, 1 = orange-black, 2 = soft pink/blue, 3 = blue-green

in vec2 a_position;
out vec4 fragColor;

vec3 applyPalette(float t) {
    //t = clamp(t, 0.0, 1.0);

    if (paletteMode == 0) {
        return vec3(t); // grayscale
    } else if (paletteMode == 1) {
        // Orange and black — current smooth style
        return vec3(t, t * 0.6, t * 0.2);
    } else if (paletteMode == 2) {
        // Soft pastel over cyan
        //return vec3(0.2 + t * 0.8, 0.1 + t * 0.4, 1.0 - t * 0.9);
        return vec3(0.2, 0.7, 1.0) * t;
    } else if (paletteMode == 3) {
        // Blue-green palette
        return vec3(0.1 + t * 0.2, t * 0.9, 0.7 + t * 0.3);
    } else if (paletteMode == 4) { // Fire, roughly matches CPU version
       float rFrac = min(1.0, t * 3.0);
       float gFrac = clamp((t - 1.0 / 3.0) * 3.0, 0.0, 1.0);
       float bFrac = clamp((t - 2.0 / 3.0) * 3.0, 0.0, 1.0);
       return vec3(rFrac, gFrac, bFrac);
    } else if (paletteMode == 5) { // HSV1
       float h = fract(t * 5.0); // More hue cycling
       float r = abs(h * 6.0 - 3.0) - 1.0;
       float g = 2.0 - abs(h * 6.0 - 2.0);
       float b = 2.0 - abs(h * 6.0 - 4.0);
       return clamp(vec3(r, g, b), 0.0, 1.0);
    } else if (paletteMode == 6) { // HSV2
       float h = fract(t * 3.0);
       float s = t;
       float v = 1.0;
       float c = v * s;
       float x = c * (1.0 - abs(mod(h * 6.0, 2.0) - 1.0));
       float m = v - c;
       vec3 rgb;

       if (h < 1.0/6.0) rgb = vec3(c, x, 0.0);
                                                                                                                                                                     else if (h < 2.0/6.0) rgb = vec3(x, c, 0.0);
       else if (h < 3.0/6.0) rgb = vec3(0.0, c, x);
       else if (h < 4.0/6.0) rgb = vec3(0.0, x, c);
       else if (h < 5.0/6.0) rgb = vec3(x, 0.0, c);
       else rgb = vec3(c, 0.0, x);

       return rgb + vec3(m);
    } else if (paletteMode == 7) { // HSV3
       float h = fract(t * 4.0);
       float v = pow(t, 0.7); // darker fade
       float s = 1.0;
       float c = v * s;
       float x = c * (1.0 - abs(mod(h * 6.0, 2.0) - 1.0));
       float m = v - c;
       vec3 rgb;

       if (h < 1.0/6.0) rgb = vec3(c, x, 0.0);
                                                                                                                                                                                                                                       else if (h < 2.0/6.0) rgb = vec3(x, c, 0.0);
       else if (h < 3.0/6.0) rgb = vec3(0.0, c, x);
       else if (h < 4.0/6.0) rgb = vec3(0.0, x, c);
       else if (h < 5.0/6.0) rgb = vec3(x, 0.0, c);
       else rgb = vec3(c, 0.0, x);

       return rgb + vec3(m);
    } else {
        return vec3(t); // fallback grayscale
    }
}

vec4 orbitTrapColor(dvec2 c) {
    dvec2 z = c;
    float minDist = 1e20;

    for (int i = 0; i < u_maxIter; ++i) {
        z = dvec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
        float dist = length(vec2(z)); // safely cast to float
        if (dist < minDist) minDist = dist;
        if (dist > 100.0) break;
    }

    // Steepen falloff to reduce glow (increase multiplier to tighten highlights)
    float t = exp(-minDist * 5.0);  // change from 2.0 to 5.0 or even 8.0
    t = pow(t, 1.5);                // apply gamma correction to push low values down

    vec3 color = applyPalette(t);
    return vec4(color, 1.0);
}

vec4 smoothColor(dvec2 c) {
    dvec2 z = c;
    int i = 0;
    for (; i < u_maxIter; ++i) {
        if (dot(z, z) > 4.0) break;
        z = dvec2(z.x*z.x - z.y*z.y, 2.0*z.x*z.y) + c;
    }

    float t;
    if (i == u_maxIter) {
        t = 0.0;
    } else {
        float mag = length(vec2(z));
        float mu = float(i) + 1.0 - log(log(mag)) / log(2.0);
        t = mod(mu * 5.0, 256.0) / 256.0;  // Wrap value like CPU version
    }

    vec3 color = applyPalette(t);
    return vec4(color, 1.0);
}

vec4 escapeColor(dvec2 c) {
    dvec2 z = c;
    int i;
    for (i = 0; i < u_maxIter; ++i) {
        z = dvec2(z.x*z.x - z.y*z.y, 2.0*z.x*z.y) + c;
        if (dot(z,z) > 4.0) break;
    }

    float t;
    if (i == u_maxIter) {
        t = 0.0;  // Interior of the set = black
    } else {
        t = float(i % 256) / 255.0;  // Wrapping!
    }

    vec3 color = applyPalette(t);
    return vec4(color, 1.0);
}

void main() {
    dvec2 fragCoord = dvec2(gl_FragCoord.xy);
    dvec2 resolution = dvec2(u_resolution);
    dvec2 uv = (fragCoord - 0.5 * resolution) / resolution.y; //flips vertically relative to CPU rendering, but ok for now
    dvec2 c = u_center + u_zoom * uv;

    if (colorMode == 0) {
        fragColor = escapeColor(c);
    } else if (colorMode == 1) {
        fragColor = smoothColor(c);
    } else if (colorMode == 2) {
        fragColor = orbitTrapColor(c);
    } else {
        fragColor = vec4(0.0); // default to black if mode is unknown
    }
}
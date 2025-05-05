#version 120

attribute vec2 a_position;

void main() {
    // Pass vertex position directly to clip space
    gl_Position = vec4(a_position, 0.0, 1.0);
}

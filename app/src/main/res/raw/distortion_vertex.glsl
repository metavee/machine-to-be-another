// Vertex shader for the lens-distortion pass. The distortion mesh is already in
// normalized device coordinates (x, y in [-1, 1] over the eye viewport); we just pass
// position and texture coordinate straight through. The barrel pre-distortion is baked
// into the mesh's per-vertex texture coordinates (see DistortionRenderer).

attribute vec2 a_Position;
attribute vec2 a_TexCoordinate;

varying vec2 v_TexCoordinate;

void main() {
    gl_Position = vec4(a_Position, 0.0, 1.0);
    v_TexCoordinate = a_TexCoordinate;
}

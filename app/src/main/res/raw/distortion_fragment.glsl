// Fragment shader for the lens-distortion pass: samples the off-screen render of one
// eye (an ordinary 2D texture) through the pre-distorted mesh coordinates.

precision mediump float;

uniform sampler2D u_Texture;

varying vec2 v_TexCoordinate;

void main() {
    gl_FragColor = texture2D(u_Texture, v_TexCoordinate);
}

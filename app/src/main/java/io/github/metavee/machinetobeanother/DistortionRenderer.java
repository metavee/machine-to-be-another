package io.github.metavee.machinetobeanother;

import android.content.Context;
import android.opengl.GLES20;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Lens barrel-distortion post-process, following the Google Cardboard SDK model.
 *
 * <p>Each eye is first rendered into an off-screen framebuffer (FBO) at the eye's <em>distorted</em>
 * (wider) field of view — see {@link CardboardProfile#eyeParams}. This class then draws that FBO
 * onto the eye's half of the screen through a distortion mesh so the headset lens's (pincushion)
 * distortion cancels out and straight lines look straight, with the perceived field of view equal
 * to the physical screen (no zoom).
 *
 * <p>The mesh is generated once per configuration. It is a uniform grid in <em>texture</em>
 * (rendered-FOV) space; each vertex's screen position is the inverse radial distortion of its
 * texture tan-angle, mapped onto the physical screen extent:
 * <pre>
 *   p_texture = uniform grid over the rendered FOV tangents
 *   p_screen  = DistortInverse(p_texture)                        // radial
 *   ndc       = 2 * (p_screen + screenEyeOffset) / screenSpan - 1
 * </pre>
 * With zero distortion coefficients this reduces to an identity blit.
 */
public final class DistortionRenderer {

    private static final String TAG = "DistortionRenderer";

    // Distortion mesh resolution (cells per side); (GRID+1)^2 vertices per eye.
    private static final int GRID = 40;

    private final Context context;

    private int program;
    private int positionParam;
    private int texCoordParam;
    private int textureUniform;

    // Shared off-screen buffer, sized to a single eye viewport and reused for both eyes.
    private int fbo;
    private int fboColorTex;
    private int fboDepthRb;
    private int eyeWidth;
    private int eyeHeight;

    // Per-eye mesh: screen positions and texture coordinates. The triangle indices (grid
    // topology) are shared.
    private final FloatBuffer[] positionBuffers = new FloatBuffer[2];
    private final FloatBuffer[] texCoordBuffers = new FloatBuffer[2];
    private ShortBuffer indexBuffer;
    private int indexCount;

    // Distortion polynomial coefficients (k1, k2) for the inverse used in the mesh.
    private float k1;
    private float k2;

    private boolean ready;

    public DistortionRenderer(Context context) {
        this.context = context;
    }

    public boolean isReady() {
        return ready;
    }

    /** Compiles the shader program. Must be called on the GL thread (onSurfaceCreated). */
    public void init() {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, R.raw.distortion_vertex);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, R.raw.distortion_fragment);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        positionParam = GLES20.glGetAttribLocation(program, "a_Position");
        texCoordParam = GLES20.glGetAttribLocation(program, "a_TexCoordinate");
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture");
    }

    /** Marks the distortion pass as unavailable (caller should render straight to screen). */
    public void disable() {
        ready = false;
    }

    /**
     * (Re)creates the off-screen buffer and rebuilds the per-eye distortion meshes.
     *
     * @param eyeWidthPx       width of one eye viewport in pixels (half the surface width).
     * @param eyeHeightPx      height of the eye viewport in pixels (the surface height).
     * @param eyes             per-eye parameters (index 0 left, 1 right); must both be non-null.
     * @param distortionCoeffs radial polynomial coefficients (k1, k2, ...); may be null/empty.
     */
    public void configure(int eyeWidthPx, int eyeHeightPx, CardboardProfile.EyeParams[] eyes,
                          float[] distortionCoeffs) {
        ready = false;
        if (eyeWidthPx <= 0 || eyeHeightPx <= 0
                || eyes == null || eyes[0] == null || eyes[1] == null) {
            return;
        }

        if (eyeWidthPx != eyeWidth || eyeHeightPx != eyeHeight || fbo == 0) {
            eyeWidth = eyeWidthPx;
            eyeHeight = eyeHeightPx;
            createFbo(eyeWidth, eyeHeight);
        }

        k1 = (distortionCoeffs != null && distortionCoeffs.length > 0) ? distortionCoeffs[0] : 0f;
        k2 = (distortionCoeffs != null && distortionCoeffs.length > 1) ? distortionCoeffs[1] : 0f;

        buildIndices();
        for (int eye = 0; eye < 2; eye++) {
            buildMesh(eye, eyes[eye]);
        }
        ready = true;
    }

    /** Binds the off-screen buffer so the next scene draw is captured for distortion. */
    public void bindEyeBuffer() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, eyeWidth, eyeHeight);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
    }

    /**
     * Draws the captured eye render onto its half of the default framebuffer through the
     * distortion mesh.
     *
     * @param eye     0 left, 1 right.
     * @param screenX x offset of the eye viewport in the default framebuffer.
     */
    public void renderEye(int eye, int screenX) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(screenX, 0, eyeWidth, eyeHeight);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboColorTex);
        GLES20.glUniform1i(textureUniform, 0);

        GLES20.glEnableVertexAttribArray(positionParam);
        GLES20.glVertexAttribPointer(positionParam, 2, GLES20.GL_FLOAT, false, 0, positionBuffers[eye]);

        GLES20.glEnableVertexAttribArray(texCoordParam);
        GLES20.glVertexAttribPointer(texCoordParam, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffers[eye]);

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer);

        GLES20.glDisableVertexAttribArray(positionParam);
        GLES20.glDisableVertexAttribArray(texCoordParam);
    }

    // --- mesh construction ------------------------------------------------------------

    private void buildIndices() {
        if (indexBuffer != null) {
            return; // grid topology is fixed; build once.
        }
        short[] indices = new short[GRID * GRID * 6];
        int n = 0;
        int stride = GRID + 1;
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                short tl = (short) (j * stride + i);
                short tr = (short) (tl + 1);
                short bl = (short) (tl + stride);
                short br = (short) (bl + 1);
                indices[n++] = tl; indices[n++] = bl; indices[n++] = tr;
                indices[n++] = tr; indices[n++] = bl; indices[n++] = br;
            }
        }
        indexCount = indices.length;
        ByteBuffer ib = ByteBuffer.allocateDirect(indices.length * 2);
        ib.order(ByteOrder.nativeOrder());
        indexBuffer = ib.asShortBuffer();
        indexBuffer.put(indices);
        indexBuffer.position(0);
    }

    private void buildMesh(int eye, CardboardProfile.EyeParams ep) {
        int verts = (GRID + 1) * (GRID + 1);
        FloatBuffer pos = allocFloats(verts * 2);
        FloatBuffer tex = allocFloats(verts * 2);

        float texWidth = ep.txLeft + ep.txRight;
        float texHeight = ep.txBottom + ep.txTop;
        float screenWidth = ep.sxLeft + ep.sxRight;
        float screenHeight = ep.sxBottom + ep.sxTop;

        for (int j = 0; j <= GRID; j++) {
            float v = (float) j / GRID;
            for (int i = 0; i <= GRID; i++) {
                float u = (float) i / GRID;

                // Uniform grid over the rendered (distorted) FOV, in tan-angle relative to the
                // lens axis.
                float pxTexture = u * texWidth - ep.txLeft;
                float pyTexture = v * texHeight - ep.txBottom;

                // Inverse-distort to the physical screen tan-angle position (radial).
                float[] pScreen = distortInverse(pxTexture, pyTexture, k1, k2);

                float uScreen = (pScreen[0] + ep.sxLeft) / screenWidth;
                float vScreen = (pScreen[1] + ep.sxBottom) / screenHeight;

                pos.put(2f * uScreen - 1f).put(2f * vScreen - 1f);
                tex.put(u).put(v);
            }
        }
        pos.position(0);
        tex.position(0);
        positionBuffers[eye] = pos;
        texCoordBuffers[eye] = tex;
    }

    /**
     * Inverse of the radial distortion {@code r -> r * (1 + k1 r^2 + k2 r^4)}: given a point in
     * distorted (rendered) tan-angle space, returns the corresponding undistorted (screen)
     * point. Uses the secant method, matching the Cardboard SDK.
     */
    private static float[] distortInverse(float x, float y, float k1, float k2) {
        float radius = (float) Math.sqrt(x * x + y * y);
        if (radius < 1e-9f) {
            return new float[] {0f, 0f};
        }
        float r0 = radius / 2f;
        float r1 = radius / 3f;
        float dr0 = radius - distortRadius(r0, k1, k2);
        int iter = 0;
        while (Math.abs(r1 - r0) > 1e-4f && iter++ < 20) {
            float dr1 = radius - distortRadius(r1, k1, k2);
            float denom = dr1 - dr0;
            if (Math.abs(denom) < 1e-9f) {
                break;
            }
            float r2 = r1 - dr1 * ((r1 - r0) / denom);
            r0 = r1;
            r1 = r2;
            dr0 = dr1;
        }
        float scale = r1 / radius;
        return new float[] {scale * x, scale * y};
    }

    private static float distortRadius(float r, float k1, float k2) {
        float r2 = r * r;
        return r * (1f + k1 * r2 + k2 * r2 * r2);
    }

    // --- GL helpers -------------------------------------------------------------------

    private void createFbo(int width, int height) {
        deleteFbo();

        int[] ids = new int[1];

        GLES20.glGenTextures(1, ids, 0);
        fboColorTex = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboColorTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glGenRenderbuffers(1, ids, 0);
        fboDepthRb = ids[0];
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, fboDepthRb);
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, width, height);

        GLES20.glGenFramebuffers(1, ids, 0);
        fbo = ids[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboColorTex, 0);
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                GLES20.GL_RENDERBUFFER, fboDepthRb);

        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer incomplete: " + status);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void deleteFbo() {
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, new int[] {fbo}, 0);
            fbo = 0;
        }
        if (fboColorTex != 0) {
            GLES20.glDeleteTextures(1, new int[] {fboColorTex}, 0);
            fboColorTex = 0;
        }
        if (fboDepthRb != 0) {
            GLES20.glDeleteRenderbuffers(1, new int[] {fboDepthRb}, 0);
            fboDepthRb = 0;
        }
    }

    private static FloatBuffer allocFloats(int count) {
        ByteBuffer bb = ByteBuffer.allocateDirect(count * 4);
        bb.order(ByteOrder.nativeOrder());
        return bb.asFloatBuffer();
    }

    private int loadShader(int type, int resId) {
        String code = readRawTextFile(resId);
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "Error compiling distortion shader: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Error compiling distortion shader");
        }
        return shader;
    }

    private String readRawTextFile(int resId) {
        InputStream inputStream = context.getResources().openRawResource(resId);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader " + resId, e);
        }
    }
}

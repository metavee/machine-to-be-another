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
 * Lens barrel-distortion post-process.
 *
 * <p>Each eye is first rendered into an off-screen framebuffer (FBO) at the eye's field of
 * view. This class then draws that FBO onto the eye's half of the screen through a
 * pre-distorted mesh, so that the headset lens's (pincushion) distortion cancels out and
 * straight lines look straight.
 *
 * <p>The mesh is generated once per configuration. For each screen grid point we convert to a
 * tan-angle on the display, invert the Cardboard radial distortion polynomial
 * ({@code r * (1 + k1*r^2 + k2*r^4)}) to find the matching direction in the undistorted
 * render, and use that as the texture coordinate. Inverting the (expanding) distortion means
 * every screen pixel samples a point strictly inside the rendered FOV, so the display is fully
 * covered with no black edges and the FBO needs no extra margin.
 *
 * <p>With zero distortion coefficients the mesh reduces to an identity blit.
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

    // Shared geometry: screen-space vertex positions and triangle indices are identical for
    // both eyes; only the (distorted) texture coordinates differ.
    private FloatBuffer positionBuffer;
    private ShortBuffer indexBuffer;
    private int indexCount;
    private final FloatBuffer[] texCoordBuffers = new FloatBuffer[2];

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

    /**
     * (Re)creates the off-screen buffer and rebuilds the distortion meshes.
     *
     * @param eyeWidthPx        width of one eye viewport in pixels (half the surface width).
     * @param eyeHeightPx       height of the eye viewport in pixels (the surface height).
     * @param eyeFrustumExtents per-eye near-plane frustum {l, r, b, t}; index 0 left, 1 right.
     * @param near              near-plane distance used to build those extents.
     * @param distortionCoeffs  radial polynomial coefficients (k1, k2, ...); may be null/empty.
     */
    public void configure(int eyeWidthPx, int eyeHeightPx, float[][] eyeFrustumExtents,
                          float near, float[] distortionCoeffs) {
        ready = false;
        if (eyeWidthPx <= 0 || eyeHeightPx <= 0
                || eyeFrustumExtents == null
                || eyeFrustumExtents[0] == null || eyeFrustumExtents[1] == null) {
            return;
        }

        if (eyeWidthPx != eyeWidth || eyeHeightPx != eyeHeight || fbo == 0) {
            eyeWidth = eyeWidthPx;
            eyeHeight = eyeHeightPx;
            createFbo(eyeWidth, eyeHeight);
        }

        float k1 = (distortionCoeffs != null && distortionCoeffs.length > 0) ? distortionCoeffs[0] : 0f;
        float k2 = (distortionCoeffs != null && distortionCoeffs.length > 1) ? distortionCoeffs[1] : 0f;

        buildSharedGeometry();
        for (int eye = 0; eye < 2; eye++) {
            texCoordBuffers[eye] = buildTexCoords(eyeFrustumExtents[eye], near, k1, k2);
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
        GLES20.glVertexAttribPointer(positionParam, 2, GLES20.GL_FLOAT, false, 0, positionBuffer);

        GLES20.glEnableVertexAttribArray(texCoordParam);
        GLES20.glVertexAttribPointer(texCoordParam, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffers[eye]);

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer);

        GLES20.glDisableVertexAttribArray(positionParam);
        GLES20.glDisableVertexAttribArray(texCoordParam);
    }

    // --- mesh construction ------------------------------------------------------------

    private void buildSharedGeometry() {
        if (positionBuffer != null) {
            return; // grid topology is fixed; build once.
        }
        int verts = (GRID + 1) * (GRID + 1);
        positionBuffer = allocFloats(verts * 2);
        for (int j = 0; j <= GRID; j++) {
            float sy = 2f * j / GRID - 1f;
            for (int i = 0; i <= GRID; i++) {
                float sx = 2f * i / GRID - 1f;
                positionBuffer.put(sx).put(sy);
            }
        }
        positionBuffer.position(0);

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

    private FloatBuffer buildTexCoords(float[] frustum, float near, float k1, float k2) {
        float tanL = frustum[0] / near;
        float tanR = frustum[1] / near;
        float tanB = frustum[2] / near;
        float tanT = frustum[3] / near;

        int verts = (GRID + 1) * (GRID + 1);
        FloatBuffer buf = allocFloats(verts * 2);
        for (int j = 0; j <= GRID; j++) {
            float fy = (float) j / GRID;
            float sigmaY = tanB + fy * (tanT - tanB);
            for (int i = 0; i <= GRID; i++) {
                float fx = (float) i / GRID;
                float sigmaX = tanL + fx * (tanR - tanL);

                // Screen tan-angle radius -> undistorted (rendered) tan-angle radius.
                float sigma = (float) Math.sqrt(sigmaX * sigmaX + sigmaY * sigmaY);
                float scale = 1f;
                if (sigma > 1e-6f) {
                    scale = distortInverse(sigma, k1, k2) / sigma;
                }
                float thetaX = sigmaX * scale;
                float thetaY = sigmaY * scale;

                float u = (thetaX - tanL) / (tanR - tanL);
                float v = (thetaY - tanB) / (tanT - tanB);
                buf.put(u).put(v);
            }
        }
        buf.position(0);
        return buf;
    }

    /**
     * Inverts {@code distort(t) = t * (1 + k1*t^2 + k2*t^4)}: given a distorted (screen)
     * radius, returns the undistorted radius, via Newton's method.
     */
    private static float distortInverse(float radius, float k1, float k2) {
        if (radius <= 0f) {
            return 0f;
        }
        float t = radius; // good initial guess since the distortion is mild
        for (int iter = 0; iter < 10; iter++) {
            float t2 = t * t;
            float t4 = t2 * t2;
            float f = t * (1f + k1 * t2 + k2 * t4) - radius;
            float df = 1f + 3f * k1 * t2 + 5f * k2 * t4;
            t -= f / df;
        }
        return t;
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

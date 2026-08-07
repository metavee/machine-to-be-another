/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.metavee.machinetobeanother;

import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A modified Google VR sample application (based on TreasureHunt)
 *
 * Created by Robin Neufeld on 2017-08-22.
 * with lots of assistance from:
 *  http://www.learnopengles.com/android-lesson-four-introducing-basic-texturing/
 *  https://developers.google.com/vr/android/samples/treasure-hunt
 *  https://github.com/chauthai/glcam
 *
 * Migrated off the deprecated Google VR (GVR) SDK to a small custom stereo renderer
 * built on a plain GLSurfaceView. Stereo split, per-eye projection and (later) lens
 * distortion are driven by the scanned Cardboard viewer profile instead of GVR's
 * device-wide, unconfigurable calibration. See CardboardProfile.
 */
public class TextureTestActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

    private MediaRecorder MR;

    private MediaPlayer MP;
    private String media_path;

    public static final int MODE_VIEW = 0;
    public static final int MODE_RECORD = 1;
    public static final int MODE_PLAYBACK = 2;

    private int mode;

    private boolean recording = false;

    private boolean LR_inversion = false;

    private Camera Webcam;
    private SurfaceTexture WebcamSurface;
    // forward-facing eye view. This renderer intentionally does not head-track: the
    // passthrough image is pinned in front of the viewer, matching the original app.
    private final float[] fixed_eye_view = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
    };
    float Webcam_AR;

    protected float[] modelRect;
    protected float[] modelPosition;

    private static final String TAG = "TextureTest";

    private static final float Z_NEAR = 0.1f;
    private static final float Z_FAR = 100.0f;

    private static final float CAMERA_Z = 0.01f;

    // Default per-eye vertical field of view (degrees), used until a scanned viewer
    // profile supplies real FOV angles (Phase 2). Roughly matches a Cardboard v2 view.
    private static final float DEFAULT_FOV_Y = 80.0f;

    private static final int COORDS_PER_VERTEX = 3;

    private static final float MAX_MODEL_DISTANCE = 7.0f;

    private GLSurfaceView glView;

    // Current surface size, set in onSurfaceChanged and used to split the viewport
    // into a left and right half.
    private int surfaceWidth;
    private int surfaceHeight;

    // Per-eye projection matrices: index 0 = left eye, 1 = right eye.
    private final float[][] eyePerspective = new float[][] {new float[16], new float[16]};

    // Scanned (or default) Cardboard viewer calibration driving the per-eye projections.
    private CardboardProfile profile;

    // Lens barrel-distortion post-process, and the per-eye rendering/distortion parameters
    // (rendered vs. physical-screen FOV tangents) it needs to build its distortion mesh.
    private DistortionRenderer distortionRenderer;
    private final CardboardProfile.EyeParams[] eyeParamsArr = new CardboardProfile.EyeParams[2];

    private FloatBuffer rectVertices;

    private FloatBuffer rectTextureCoordinates;

    private int rectProgram;
    private int textureDataHandle;

    private int rectPositionParam;
    private int rectModelViewProjectionParam;

    private int textureUniformParam;
    private int textureCoordinateParam;
    private final int textureCoordinateDataSize = 2;

    private float[] camera;
    private float[] view;
    private float[] modelViewProjection;
    private float[] modelView;

    public void startCamera(int texture) {
        if (Webcam != null) {
            // Already running (e.g. onSurfaceCreated started it before the queued
            // resume runnable fired). Nothing to do.
            return;
        }

        WebcamSurface = new SurfaceTexture(texture);

        Webcam = Camera.open();

        configureCamera(Webcam);

        try {
            Webcam.setPreviewTexture(WebcamSurface);
            Webcam.startPreview();
        } catch (IOException ioe) {
            Log.w("TextureTestActivity", "startCamera");
        }

        Camera.Size dims = Webcam.getParameters().getPreviewSize();
        float h = dims.height;
        float w = dims.width;
        Webcam_AR = h / w;

        float[] RECT_TEXTURE_COORDS = WorldLayoutData.getRectTextureCoords(Webcam_AR, this.LR_inversion);

        rectTextureCoordinates.put(RECT_TEXTURE_COORDS);
        rectTextureCoordinates.position(0);

    }

    /**
    * Tunes the camera so the preview matches what the stock camera app shows: a preview size that
    * uses the sensor's full field of view (so it doesn't look zoomed in), and continuous
    * autofocus (so it re-focuses on its own instead of drifting out of focus).
    */
    private void configureCamera(Camera camera) {
        Camera.Parameters params = camera.getParameters();

        Camera.Size previewSize = chooseWidestPreviewSize(params);
        if (previewSize != null) {
            params.setPreviewSize(previewSize.width, previewSize.height);
        }

        List<String> focusModes = params.getSupportedFocusModes();
        if (focusModes != null) {
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
        }

        camera.setParameters(params);
    }

    /**
    * Picks the preview size that preserves the most field of view. The renderer center-crops each
    * frame to a square (see WorldLayoutData.getRectTextureCoords), so the widest result comes from
    * matching the sensor's native aspect ratio — approximated here by the largest supported picture
    * size, which always uses the full sensor. Among preview sizes with that aspect ratio we take the
    * largest; failing that, the largest preview size overall.
    */
    private Camera.Size chooseWidestPreviewSize(Camera.Parameters params) {
        List<Camera.Size> previewSizes = params.getSupportedPreviewSizes();
        if (previewSizes == null || previewSizes.isEmpty()) {
            return null;
        }

        float targetAspect = -1f;
        List<Camera.Size> pictureSizes = params.getSupportedPictureSizes();
        if (pictureSizes != null && !pictureSizes.isEmpty()) {
            Camera.Size largestPicture = null;
            for (Camera.Size s : pictureSizes) {
                if (largestPicture == null || (long) s.width * s.height > (long) largestPicture.width * largestPicture.height) {
                    largestPicture = s;
                }
            }
            targetAspect = (float) largestPicture.width / largestPicture.height;
        }

        Camera.Size best = null;
        for (Camera.Size s : previewSizes) {
            if (targetAspect > 0f) {
                float aspect = (float) s.width / s.height;
                if (Math.abs(aspect - targetAspect) > 0.05f) {
                    continue;
                }
            }
            if (best == null || (long) s.width * s.height > (long) best.width * best.height) {
                best = s;
            }
        }

        if (best == null) {
            // No preview size matched the sensor aspect ratio; fall back to the largest available.
            for (Camera.Size s : previewSizes) {
                if (best == null || (long) s.width * s.height > (long) best.width * best.height) {
                    best = s;
                }
            }
        }

        return best;
    }

    /**
    * Converts a raw text file, saved as a resource, into an OpenGL ES shader.
    *
    * @param type The type of shader we will be creating.
    * @param resId The resource ID of the raw text file about to be turned into a shader.
    * @return The shader object handler.
    */
    private int loadGLShader(int type, int resId) {
        String code = readRawTextFile(resId);
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);

        // Get the compilation status.
        final int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);

        // If the compilation failed, delete the shader.
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }

        if (shader == 0) {
            throw new RuntimeException("Error creating shader.");
        }

        return shader;
    }

    static private int createTexture()
    {
        int[] texture = new int[1];

        GLES20.glGenTextures(1,texture, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture[0]);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_MIN_FILTER,GL10.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);

        return texture[0];
    }

    /**
    * Checks if we've had an error inside of OpenGL ES, and if so what that error is.
    *
    * @param label Label to report in case of error.
    */
    private static void checkGLError(String label) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, label + ": glError " + error);
            throw new RuntimeException(label + ": glError " + error);
        }
    }

    /**
    * Sets up the GLSurfaceView and initializes the transformation matrices we will use
    * to render our scene.
    */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        modelRect = new float[16];
        camera = new float[16];
        view = new float[16];
        modelViewProjection = new float[16];
        modelView = new float[16];
        // Model first appears directly in front of user.
        modelPosition = new float[] {0.0f, 0.0f, -MAX_MODEL_DISTANCE / 2.0f};

        // get mode
        Intent intent = getIntent();
        this.mode = intent.getIntExtra("mode", MODE_VIEW);

        if (mode == MODE_PLAYBACK) {
            media_path = intent.getStringExtra("filename");
        }

        // Load the scanned viewer calibration (or the built-in default) that drives the
        // per-eye stereo geometry.
        profile = CardboardProfile.load(this);

        initializeGlView();
    }

    public void initializeGlView() {
        setContentView(R.layout.common_ui);

        // Keep the screen awake while the stereo view is active (the removed GvrView used to
        // do this for us). Cleared automatically when the activity is no longer visible.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        glView = (GLSurfaceView) findViewById(R.id.gl_view);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 8);
        // Keep the GL context (and its textures) across pause/resume where supported,
        // so we don't have to rebuild everything each time the app is resumed.
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(this);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        // A tap anywhere is the trigger, replacing the Cardboard magnet/button. Modern
        // Cardboard viewers press a conductive lever onto the screen, which the system
        // already reports as a touch, so this also handles physical viewer buttons.
        final GestureDetector gestureDetector =
                new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        onTriggerTap();
                        return true;
                    }
                });
        glView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mode == MODE_RECORD && recording) {
            this.stopRecording();
        }

        if (glView != null) {
            glView.onPause();
        }

        if (mode != MODE_PLAYBACK) {
            if (Webcam != null) {
                Webcam.release();
                Webcam = null;
            }
        } else {
            if (MP != null) {
                if (MP.isPlaying()) {
                    MP.stop();
                }
                MP.release();
                MP = null;
            }
        }

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glView != null) {
            glView.onResume();
            // Re-acquire the camera / media player on the GL thread once the surface
            // exists. If the GL context was lost, onSurfaceCreated has already done this
            // and the guards below make the queued call a no-op.
            glView.queueEvent(() -> {
                if (textureDataHandle == 0) {
                    return;
                }
                if (mode == MODE_PLAYBACK) {
                    if (MP == null) {
                        startPlayback(textureDataHandle);
                    }
                } else {
                    if (Webcam == null) {
                        startCamera(textureDataHandle);
                    }
                }
            });
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        Log.i(TAG, "onSurfaceChanged");
        surfaceWidth = width;
        surfaceHeight = height;
        updateEyeProjections();
    }

    /**
    * Recomputes the per-eye projection matrices for the current surface size.
    *
    * <p>The geometry comes from the scanned viewer {@link CardboardProfile}: an asymmetric
    * frustum per eye derived from the lens/screen layout, so each eye's image is centered
    * under its lens and scaled to the headset. If the profile geometry is unusable (e.g. the
    * device reports no physical DPI), we fall back to a symmetric default perspective.
    */
    private void updateEyeProjections() {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            return;
        }
        float eyeAspect = (surfaceWidth / 2.0f) / surfaceHeight;

        // Physical screen size in meters, needed to map the profile's metric distances onto
        // this display. DisplayMetrics DPI is an approximation of what GVR derived from its
        // per-device database, but is good enough here and can be off on a few devices.
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float xdpi = dm.xdpi > 0 ? dm.xdpi : dm.densityDpi;
        float ydpi = dm.ydpi > 0 ? dm.ydpi : dm.densityDpi;
        float screenWidthMeters = dm.widthPixels / xdpi * 0.0254f;
        float screenHeightMeters = dm.heightPixels / ydpi * 0.0254f;

        for (int eye = 0; eye < 2; eye++) {
            CardboardProfile.EyeParams ep = (profile != null)
                    ? profile.eyeParams(eye, screenWidthMeters, screenHeightMeters)
                    : null;
            eyeParamsArr[eye] = ep;
            if (ep != null) {
                // Render at the distorted (wider) FOV; the distortion mesh brings it back to
                // the physical screen FOV.
                Matrix.frustumM(eyePerspective[eye], 0,
                        -ep.txLeft * Z_NEAR, ep.txRight * Z_NEAR,
                        -ep.txBottom * Z_NEAR, ep.txTop * Z_NEAR, Z_NEAR, Z_FAR);
            } else {
                // Symmetric default frustum when the profile geometry is unusable.
                float t = (float) Math.tan(Math.toRadians(DEFAULT_FOV_Y / 2.0)) * Z_NEAR;
                float r = t * eyeAspect;
                Matrix.frustumM(eyePerspective[eye], 0, -r, r, -t, t, Z_NEAR, Z_FAR);
            }
        }

        if (distortionRenderer != null) {
            if (eyeParamsArr[0] != null && eyeParamsArr[1] != null) {
                distortionRenderer.configure(surfaceWidth / 2, surfaceHeight, eyeParamsArr,
                        profile != null ? profile.distortionCoeffs : null);
            } else {
                distortionRenderer.disable();
            }
        }
    }

    /**
    * Creates the buffers we use to store information about the 3D world.
    *
    * <p>OpenGL doesn't use Java arrays, but rather needs data in a format it can understand.
    * Hence we use ByteBuffers.
    *
    * @param config The EGL configuration used when creating the surface.
    */
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Log.i(TAG, "onSurfaceCreated");
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 0.5f); // Dark background so text shows up well.

        ByteBuffer bbVertices = ByteBuffer.allocateDirect(WorldLayoutData.RECT_COORDS.length * 4);
        bbVertices.order(ByteOrder.nativeOrder());
        rectVertices = bbVertices.asFloatBuffer();
        rectVertices.put(WorldLayoutData.RECT_COORDS);
        rectVertices.position(0);

        float[] RECT_TEXTURE_COORDS = WorldLayoutData.getRectTextureCoords(Webcam_AR, this.LR_inversion);

        ByteBuffer bbTexture = ByteBuffer.allocateDirect(RECT_TEXTURE_COORDS.length * 4);
        bbTexture.order(ByteOrder.nativeOrder());
        rectTextureCoordinates = bbTexture.asFloatBuffer();
//        rectTextureCoordinates.put(RECT_TEXTURE_COORDS);
//        rectTextureCoordinates.position(0);

        int vertexShader = loadGLShader(GLES20.GL_VERTEX_SHADER, R.raw.rect_vertex);
        int passthroughShader = loadGLShader(GLES20.GL_FRAGMENT_SHADER, R.raw.rect_fragment);

        rectProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(rectProgram, vertexShader);
        GLES20.glAttachShader(rectProgram, passthroughShader);
        GLES20.glLinkProgram(rectProgram);
        GLES20.glUseProgram(rectProgram);

        checkGLError("Rect program");

        rectPositionParam = GLES20.glGetAttribLocation(rectProgram, "a_Position");
        textureCoordinateParam = GLES20.glGetAttribLocation(rectProgram, "a_TexCoordinate");

        rectModelViewProjectionParam = GLES20.glGetUniformLocation(rectProgram, "u_MVP");
        textureUniformParam = GLES20.glGetUniformLocation(rectProgram, "u_Texture");

        checkGLError("Rect program params");

        textureDataHandle = createTexture();

        checkGLError("Texture loading");

        // Set up the lens-distortion post-process. The off-screen buffer and distortion
        // meshes are (re)built later in onSurfaceChanged, once the surface size is known.
        distortionRenderer = new DistortionRenderer(this);
        distortionRenderer.init();

        checkGLError("Distortion program");

        updateModelPosition();

        checkGLError("onSurfaceCreated");

        if (mode == MODE_PLAYBACK) {
            this.startPlayback(textureDataHandle);
        } else {
            this.startCamera(textureDataHandle);
        }

    }

    /**
    * Updates the rect model position.
    */
    protected void updateModelPosition() {
        Matrix.setIdentityM(modelRect, 0);
        Matrix.translateM(modelRect, 0, modelPosition[0], modelPosition[1], modelPosition[2]);

        checkGLError("updateRectPosition");
    }

    /**
    * Converts a raw text file into a string.
    *
    * @param resId The resource ID of the raw text file about to be turned into a shader.
    * @return The context of the text file, or null in case of error.
    */
    private String readRawTextFile(int resId) {
        InputStream inputStream = getResources().openRawResource(resId);
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
            e.printStackTrace();
        }
        return null;
    }

    /**
    * Draws a frame: updates the camera texture, then draws the scene once per eye into
    * its half of the surface.
    */
    @Override
    public void onDrawFrame(GL10 gl) {
        // update webcam stream
        if (WebcamSurface != null) {
            WebcamSurface.updateTexImage();
        }

        // Build the camera matrix (shared by both eyes; this renderer does not head-track).
        Matrix.setLookAtM(camera, 0, 0.0f, 0.0f, CAMERA_Z, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

        int halfWidth = surfaceWidth / 2;
        boolean distort = distortionRenderer != null && distortionRenderer.isReady();

        if (distort) {
            // Clear the on-screen buffer once; each eye is rendered off-screen and then
            // drawn back through the distortion mesh.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            checkGLError("onDrawFrame");

            for (int eye = 0; eye < 2; eye++) {
                distortionRenderer.bindEyeBuffer();
                buildEyeMvp(eye);
                drawRect();
                distortionRenderer.renderEye(eye, eye == 0 ? 0 : halfWidth);
            }
        } else {
            // Fallback (distortion not ready): draw each eye straight to its half.
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            checkGLError("onDrawFrame");

            for (int eye = 0; eye < 2; eye++) {
                GLES20.glViewport(eye == 0 ? 0 : halfWidth, 0, halfWidth, surfaceHeight);
                buildEyeMvp(eye);
                drawRect();
            }
        }
    }

    /**
    * Builds the ModelViewProjection matrix for one eye (forward-facing, no head tracking).
    *
    * @param eye 0 for the left eye, 1 for the right eye.
    */
    private void buildEyeMvp(int eye) {
        Matrix.multiplyMM(view, 0, fixed_eye_view, 0, camera, 0);
        Matrix.multiplyMM(modelView, 0, view, 0, modelRect, 0);
        Matrix.multiplyMM(modelViewProjection, 0, eyePerspective[eye], 0, modelView, 0);
    }

    /**
    * Draw the rect.
    *
    * <p>We've set all of our transformation matrices. Now we simply pass them into the shader.
    */
    public void drawRect() {
        GLES20.glUseProgram(rectProgram);

        // Set the position of the rect
        GLES20.glVertexAttribPointer(
                rectPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, rectVertices);

        // Set the texture coordinates
        GLES20.glVertexAttribPointer(
                textureCoordinateParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, rectTextureCoordinates);

        // Set the ModelViewProjection matrix in the shader.
        GLES20.glUniformMatrix4fv(rectModelViewProjectionParam, 1, false, modelViewProjection, 0);

        // pass in texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureDataHandle);
        GLES20.glUniform1i(textureUniformParam, 0);

        rectTextureCoordinates.position(0);
        GLES20.glVertexAttribPointer(textureCoordinateParam, textureCoordinateDataSize, GLES20.GL_FLOAT, false, 0, rectTextureCoordinates);

        // Enable vertex arrays
        GLES20.glEnableVertexAttribArray(rectPositionParam);
        GLES20.glEnableVertexAttribArray(textureCoordinateParam);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);

        // Disable vertex arrays
        GLES20.glDisableVertexAttribArray(rectPositionParam);
        GLES20.glDisableVertexAttribArray(textureCoordinateParam);

        checkGLError("Drawing rect");
    }

    /**
    * Called when the viewer trigger (a screen tap) fires.
    */
    private void onTriggerTap() {
        Log.i(TAG, "onTriggerTap");
        switch (mode) {
            case MODE_VIEW:
                // toggleView mutates the GL texture-coordinate buffer, so run it on the
                // GL thread.
                if (glView != null) {
                    glView.queueEvent(this::toggleView);
                }
                break;
            case MODE_RECORD:
                // Camera / MediaRecorder work stays off the GL thread.
                this.toggleRecord();
                break;
        }
    }

    private void toggleView() {
        this.LR_inversion = !this.LR_inversion;

        float[] RECT_TEXTURE_COORDS = WorldLayoutData.getRectTextureCoords(Webcam_AR, this.LR_inversion);

        rectTextureCoordinates.put(RECT_TEXTURE_COORDS);
        rectTextureCoordinates.position(0);
    }

    private void toggleRecord() {
        if (this.recording) {
            this.stopRecording();
        } else {
            this.startRecording();
        }
    }

    private void startPlayback(int texture) {
        if (MP != null) {
            return;
        }

        MP = new MediaPlayer();
        try {
            MP.setDataSource(media_path);
        } catch (IOException e) {
            e.printStackTrace();
        }

        WebcamSurface = new SurfaceTexture(texture);

        Surface surf = new Surface(WebcamSurface);
        MP.setSurface(surf);
        surf.release();

        try {
            MP.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }

        float h = MP.getVideoHeight();
        float w = MP.getVideoWidth();
        Webcam_AR = h / w;

        float[] RECT_TEXTURE_COORDS = WorldLayoutData.getRectTextureCoords(Webcam_AR, false);

        rectTextureCoordinates.put(RECT_TEXTURE_COORDS);
        rectTextureCoordinates.position(0);

        MP.start();
    }

    private void startRecording() {
        MR = new MediaRecorder();
        Webcam.unlock();
        MR.setCamera(Webcam);

        MR.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        MR.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        MR.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        MR.setVideoSize(1280, 720);
        MR.setVideoFrameRate(30);
        MR.setVideoEncodingBitRate(3000000);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        Date now = new Date();

        File outdir = getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        assert outdir.mkdirs();
        String fn = new File(outdir, sdf.format(now) + ".mp4").toString();
        MR.setOutputFile(fn);

        // set preview output

        try {
            MR.prepare();
            MR.start();
            this.recording = true;
        } catch (IOException ioe) {
            this.stopRecording();
        }
    }

    private void stopRecording() {
        MR.stop();
        MR.reset();
        MR.release();
        Webcam.lock();

        this.recording = false;
    }

}

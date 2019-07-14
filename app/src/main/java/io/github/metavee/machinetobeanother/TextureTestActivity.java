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
import android.media.CamcorderProfile;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import com.google.vr.sdk.base.AndroidCompat;
import com.google.vr.sdk.base.Eye;
import com.google.vr.sdk.base.GvrActivity;
import com.google.vr.sdk.base.GvrView;
import com.google.vr.sdk.base.HeadTransform;
import com.google.vr.sdk.base.Viewport;
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
 */
public class TextureTestActivity extends GvrActivity implements GvrView.StereoRenderer {

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
    // forward-facing eye view
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
    private static final float TIME_DELTA = 0.3f;

    private static final int COORDS_PER_VERTEX = 3;

    private static final float MAX_MODEL_DISTANCE = 7.0f;

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
    private float[] headView;
    private float[] modelViewProjection;
    private float[] modelView;

    private float[] headRotation;

    public void startCamera(int texture) {
        WebcamSurface = new SurfaceTexture(texture);

        Webcam = Camera.open();

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
    * Sets the view to our GvrView and initializes the transformation matrices we will use
    * to render our scene.
    */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initializeGvrView();

        modelRect = new float[16];
        camera = new float[16];
        view = new float[16];
        modelViewProjection = new float[16];
        modelView = new float[16];
        // Model first appears directly in front of user.
        modelPosition = new float[] {0.0f, 0.0f, -MAX_MODEL_DISTANCE / 2.0f};
        headRotation = new float[4];
        headView = new float[16];

        // get mode
        Intent intent = getIntent();
        this.mode = intent.getIntExtra("mode", MODE_VIEW);

        if (mode == MODE_PLAYBACK) {
            media_path = intent.getStringExtra("filename");
        }
    }

    public void initializeGvrView() {
        setContentView(R.layout.common_ui);

        GvrView gvrView = (GvrView) findViewById(R.id.gvr_view);
        gvrView.setEGLConfigChooser(8, 8, 8, 8, 16, 8);

        gvrView.setRenderer(this);
        gvrView.setTransitionViewEnabled(true);

        // Enable Cardboard-trigger feedback with Daydream headsets. This is a simple way of supporting
        // Daydream controller input for basic interactions using the existing Cardboard trigger API.
        gvrView.enableCardboardTriggerEmulation();

        if (gvrView.setAsyncReprojectionEnabled(true)) {
            // Async reprojection decouples the app framerate from the display framerate,
            // allowing immersive interaction even at the throttled clockrates set by
            // sustained performance mode.
            AndroidCompat.setSustainedPerformanceMode(this, true);
        }

        setGvrView(gvrView);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mode == MODE_RECORD && recording) {
            this.stopRecording();
        }

        if (mode != MODE_PLAYBACK) {
            Webcam.release();
        } else {
            if (MP != null) {
                if (MP.isPlaying()) {
                    MP.stop();
                }
                MP.release();
            }
        }

    }

    @Override
    public void onResume() {
        super.onResume();
        if (mode != MODE_PLAYBACK && textureDataHandle != 0) {
            this.startCamera(textureDataHandle);
        }

        if (mode == MODE_PLAYBACK) {
            if (MP != null) {
                MP.start();
            }
        }
    }

    @Override
    public void onRendererShutdown() {
    Log.i(TAG, "onRendererShutdown");
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
    Log.i(TAG, "onSurfaceChanged");
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
    public void onSurfaceCreated(EGLConfig config) {
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
    * Prepares OpenGL ES before we draw a frame.
    *
    * @param headTransform The head transformation in the new frame.
    */
    @Override
    public void onNewFrame(HeadTransform headTransform) {
        // update webcam stream
        WebcamSurface.updateTexImage();

        // Build the camera matrix and apply it to the ModelView.
        Matrix.setLookAtM(camera, 0, 0.0f, 0.0f, CAMERA_Z, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

        headTransform.getHeadView(headView, 0);

        headTransform.getQuaternion(headRotation, 0);

        checkGLError("onReadyToDraw");
    }

    /**
    * Draws a frame for an eye.
    *
    * @param eye The eye to render. Includes all required transformations.
    */
    @Override
    public void onDrawEye(Eye eye) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        checkGLError("colorParam");

        // Hold eye view facing forward, rather than using eye.getEyeView()
        Matrix.multiplyMM(view, 0, fixed_eye_view, 0, camera, 0);

        // Build the ModelView and ModelViewProjection matrices
        // for calculating rect position and light.
        float[] perspective = eye.getPerspective(Z_NEAR, Z_FAR);
        Matrix.multiplyMM(modelView, 0, view, 0, modelRect, 0);
        Matrix.multiplyMM(modelViewProjection, 0, perspective, 0, modelView, 0);
        drawRect();
    }

    @Override
    public void onFinishFrame(Viewport viewport) {}

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
    * Called when the Cardboard trigger is pulled.
    */
    @Override
    public void onCardboardTrigger() {
        Log.i(TAG, "onCardboardTrigger");
        switch (mode) {
            case MODE_VIEW:
                this.toggleView();
                break;
            case MODE_RECORD:
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

/*
 * Copyright 2017 Google Inc. All Rights Reserved.

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

/**
 * Contains vertex, normal and color data.
 */
public final class WorldLayoutData {

    // S, T (or X, Y)
    // Texture coordinate data.
    // Because images have a Y axis pointing downward (values increase as you move down the image) while
    // OpenGL has a Y axis pointing upward, we adjust for that here by flipping the Y axis.
    // What's more is that the texture coordinates are the same for every face.

    public static float[] getRectTextureCoords(float aspect_ratio, boolean LR_invert) {
        float left = (1f - aspect_ratio) / 2;
        float right = 1f - left;

        if (LR_invert) {
            return new float[] {
                    right, 0f,
                    right, 1f,
                    left, 0f,
                    right, 1f,
                    left, 1f,
                    left, 0f
            };
        } else {
            return new float[] {
                    left, 0f,
                    left, 1f,
                    right, 0f,
                    left, 1f,
                    right, 1f,
                    right, 0f
            };
        }
    }

    // Z (in model space) of the passthrough quad. Combined with the model translation and the
    // camera position it fixes the quad's distance from the eye, which the life-size passthrough
    // uses to size the quad to the camera's field of view.
    public static final float RECT_Z = 1.0f;

    public static final float[] RECT_COORDS = getRectCoords(1.75f, 1.75f);

    /** Builds the quad vertices (two triangles) with the given half-width and half-height. */
    public static float[] getRectCoords(float halfX, float halfY) {
        return new float[] {
            -halfX,  halfY, RECT_Z,
            -halfX, -halfY, RECT_Z,
             halfX,  halfY, RECT_Z,
            -halfX, -halfY, RECT_Z,
             halfX, -halfY, RECT_Z,
             halfX,  halfY, RECT_Z,
        };
    }

    /**
     * Texture coordinates that show the full camera frame (no crop), matching the vertex winding
     * of {@link #getRectCoords}. Used by the life-size passthrough, where the quad itself is
     * sized to the camera's field of view instead of cropping the image to a square.
     */
    public static float[] getFullTextureCoords(boolean LR_invert) {
        if (LR_invert) {
            return new float[] {
                    1f, 0f,
                    1f, 1f,
                    0f, 0f,
                    1f, 1f,
                    0f, 1f,
                    0f, 0f
            };
        } else {
            return new float[] {
                    0f, 0f,
                    0f, 1f,
                    1f, 0f,
                    0f, 1f,
                    1f, 1f,
                    1f, 0f
            };
        }
    }

}

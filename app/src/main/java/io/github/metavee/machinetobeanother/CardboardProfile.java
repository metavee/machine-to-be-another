package io.github.metavee.machinetobeanother;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import java.util.ArrayList;

/**
 * A Google Cardboard viewer profile: the lens/screen geometry that determines how the
 * stereo image must be positioned, scaled and (later) distorted for a particular headset.
 *
 * <p>This replaces the calibration that the (removed) Google VR SDK used to own. The same
 * per-viewer profile the official Cardboard app uses is encoded in the QR code printed on
 * a viewer: a URL of the form {@code https://google.com/cardboard/cfg?p=<base64>} whose
 * {@code p} parameter is a Base64 (URL-safe) {@code DeviceParams} protocol buffer. We parse
 * the handful of fields we need with a tiny hand-rolled protobuf reader (no protobuf runtime
 * dependency) and persist the raw bytes so the calibration survives restarts.
 *
 * <p>Field numbers follow {@code cardboard_device.proto}:
 * <pre>
 *   3  screen_to_lens_distance        float   (meters)
 *   4  inter_lens_distance            float   (meters)
 *   5  left_eye_field_of_view_angles  float[4] packed, degrees: [outer, inner, bottom, top]
 *   6  tray_to_lens_distance          float   (meters)
 *   7  distortion_coefficients        float[] packed (radial polynomial k1, k2, ...)
 *   11 vertical_alignment             enum    (0 BOTTOM, 1 CENTER, 2 TOP)
 * </pre>
 */
public final class CardboardProfile {

    private static final String TAG = "CardboardProfile";

    private static final String PREFS = "cardboard_profile";
    private static final String KEY_DEVICE_PARAMS = "device_params";

    public static final int VERTICAL_ALIGNMENT_BOTTOM = 0;
    public static final int VERTICAL_ALIGNMENT_CENTER = 1;
    public static final int VERTICAL_ALIGNMENT_TOP = 2;

    /** Distance from the screen surface to the lens, in meters. */
    public float screenToLensDistance;
    /** Distance between the two lens centers, in meters. */
    public float interLensDistance;
    /** Distance from the bottom edge (tray) to the lens center, in meters. */
    public float trayToLensDistance;
    /** How the lenses are aligned vertically relative to the screen. */
    public int verticalAlignment;
    /** Left-eye lens field-of-view half-angles in degrees: [outer, inner, bottom, top]. */
    public float[] fovAngles;
    /** Radial distortion polynomial coefficients (used by the distortion pass). */
    public float[] distortionCoeffs;

    private CardboardProfile() {}

    /**
     * The built-in Google Cardboard Viewer v2 (2015) profile, used when nothing has been
     * scanned yet so the app still works out of the box.
     */
    public static CardboardProfile getDefault() {
        CardboardProfile p = new CardboardProfile();
        p.screenToLensDistance = 0.039f;
        p.interLensDistance = 0.064f;
        p.trayToLensDistance = 0.035f;
        p.verticalAlignment = VERTICAL_ALIGNMENT_BOTTOM;
        p.fovAngles = new float[] {50f, 50f, 50f, 50f};
        p.distortionCoeffs = new float[] {0.34f, 0.55f};
        return p;
    }

    /** Loads the saved profile, or the default if none has been scanned. */
    public static CardboardProfile load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encoded = prefs.getString(KEY_DEVICE_PARAMS, null);
        if (encoded != null) {
            try {
                byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                CardboardProfile parsed = parse(bytes);
                if (parsed != null) {
                    return parsed;
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to load saved profile; using default", e);
            }
        }
        return getDefault();
    }

    /** Persists raw {@code DeviceParams} bytes as the current calibration. */
    public static void save(Context context, byte[] deviceParams) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_DEVICE_PARAMS, Base64.encodeToString(deviceParams, Base64.DEFAULT))
                .apply();
    }

    /**
     * Extracts the {@code DeviceParams} bytes from a scanned Cardboard QR URL (the {@code p}
     * query parameter). Returns null if the text is not a recognizable Cardboard profile URL.
     */
    public static byte[] deviceParamsFromUri(String qrText) {
        if (qrText == null) {
            return null;
        }
        try {
            Uri uri = Uri.parse(qrText.trim());
            String p = uri.getQueryParameter("p");
            if (p == null) {
                return null;
            }
            // Cardboard uses URL-safe Base64 without padding.
            return Base64.decode(p, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (RuntimeException e) {
            Log.w(TAG, "Not a Cardboard profile URI: " + qrText, e);
            return null;
        }
    }

    /**
     * Parses a {@code DeviceParams} protobuf message. Returns null on malformed input.
     */
    public static CardboardProfile parse(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        // Start from the v2 defaults so any field the QR omits keeps a sane value.
        CardboardProfile p = getDefault();
        ArrayList<Float> fov = new ArrayList<>();
        ArrayList<Float> distortion = new ArrayList<>();

        try {
            int pos = 0;
            while (pos < data.length) {
                long tag = readVarint(data, pos);
                pos = varintEnd(data, pos);
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 0x7);

                switch (wire) {
                    case 0: { // varint
                        long value = readVarint(data, pos);
                        pos = varintEnd(data, pos);
                        if (field == 11) {
                            p.verticalAlignment = (int) value;
                        }
                        break;
                    }
                    case 5: { // 32-bit (float, or a single element of an unpacked repeated float)
                        float value = readFloatLE(data, pos);
                        pos += 4;
                        switch (field) {
                            case 3: p.screenToLensDistance = value; break;
                            case 4: p.interLensDistance = value; break;
                            case 6: p.trayToLensDistance = value; break;
                            case 5: fov.add(value); break;
                            case 7: distortion.add(value); break;
                            default: break;
                        }
                        break;
                    }
                    case 2: { // length-delimited (strings, or packed repeated floats)
                        int len = (int) readVarint(data, pos);
                        pos = varintEnd(data, pos);
                        int end = pos + len;
                        if (field == 5 || field == 7) {
                            ArrayList<Float> target = (field == 5) ? fov : distortion;
                            for (int i = pos; i + 4 <= end; i += 4) {
                                target.add(readFloatLE(data, i));
                            }
                        }
                        pos = end; // skip strings / anything else
                        break;
                    }
                    case 1: // 64-bit
                        pos += 8;
                        break;
                    default:
                        Log.w(TAG, "Unknown wire type " + wire + "; aborting parse");
                        return null;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Malformed DeviceParams", e);
            return null;
        }

        if (fov.size() == 4) {
            p.fovAngles = toArray(fov);
        }
        if (!distortion.isEmpty()) {
            p.distortionCoeffs = toArray(distortion);
        }
        return p;
    }

    /** Per-eye tan-angle extents for both the rendered texture and the physical screen. */
    public static final class EyeParams {
        // Rendered (off-screen texture) FOV tangents — the distorted, wider FOV. Positive
        // magnitudes measured from the lens axis toward each edge.
        public float txLeft, txRight, txBottom, txTop;
        // Physical screen FOV tangents (undistorted) — where each rendered direction lands on
        // the display. Positive magnitudes from the lens axis.
        public float sxLeft, sxRight, sxBottom, sxTop;
    }

    /**
     * Computes the per-eye rendering + distortion parameters from the viewer geometry,
     * following the Cardboard SDK model:
     *
     * <ul>
     *   <li>The physical screen FOV tangent to each edge of the eye's half of the display is
     *       {@code edgeDistance / screenToLensDistance}. Each lens sits {@code
     *       interLensDistance / 2} from the screen center horizontally and {@code lensY} from
     *       the bottom (per {@code verticalAlignment}).</li>
     *   <li>The eye is rendered at a wider "distorted" FOV, {@code Distort(screenTangent)},
     *       clamped by the lens's maximum FOV; the off-screen texture spans this FOV.</li>
     *   <li>{@link DistortionRenderer} then maps that texture back onto the physical screen
     *       via the inverse distortion, so the lens's pincushion cancels out and the perceived
     *       FOV equals the physical screen FOV (i.e. no zoom, straight lines stay straight).</li>
     * </ul>
     *
     * @param eye 0 = left, 1 = right.
     * @param screenWidthMeters  physical width of the screen (long/landscape dimension).
     * @param screenHeightMeters physical height of the screen (short/landscape dimension).
     * @return the eye parameters, or null if the geometry is unusable (caller should fall back
     *         to a plain symmetric perspective with no distortion).
     */
    public EyeParams eyeParams(int eye, float screenWidthMeters, float screenHeightMeters) {
        if (screenToLensDistance <= 0f || screenWidthMeters <= 0f || screenHeightMeters <= 0f) {
            return null;
        }
        float outerDist = screenWidthMeters / 2f - interLensDistance / 2f;
        float innerDist = interLensDistance / 2f;
        if (outerDist <= 0f) {
            return null;
        }

        float lensY;
        switch (verticalAlignment) {
            case VERTICAL_ALIGNMENT_TOP:
                lensY = screenHeightMeters - trayToLensDistance;
                break;
            case VERTICAL_ALIGNMENT_CENTER:
                lensY = screenHeightMeters / 2f;
                break;
            case VERTICAL_ALIGNMENT_BOTTOM:
            default:
                lensY = trayToLensDistance;
                break;
        }
        float bottomDist = lensY;
        float topDist = screenHeightMeters - lensY;
        if (bottomDist <= 0f || topDist <= 0f) {
            return null;
        }

        // Physical screen FOV tangents (undistorted).
        float sOuter = outerDist / screenToLensDistance;
        float sInner = innerDist / screenToLensDistance;
        float sBottom = bottomDist / screenToLensDistance;
        float sTop = topDist / screenToLensDistance;

        // Distorted (rendered) FOV tangents, clamped by the lens's maximum FOV.
        float tOuter = distortedTangent(sOuter, fovAngle(0));
        float tInner = distortedTangent(sInner, fovAngle(1));
        float tBottom = distortedTangent(sBottom, fovAngle(2));
        float tTop = distortedTangent(sTop, fovAngle(3));

        EyeParams p = new EyeParams();
        p.sxBottom = sBottom;
        p.sxTop = sTop;
        p.txBottom = tBottom;
        p.txTop = tTop;
        if (eye == 0) { // left eye: outer edge on the left
            p.sxLeft = sOuter;  p.sxRight = sInner;
            p.txLeft = tOuter;  p.txRight = tInner;
        } else {        // right eye: mirror horizontally
            p.sxLeft = sInner;  p.sxRight = sOuter;
            p.txLeft = tInner;  p.txRight = tOuter;
        }
        return p;
    }

    private float fovAngle(int index) {
        return (fovAngles != null && fovAngles.length == 4) ? fovAngles[index] : 90f;
    }

    /** Radial distortion along one axis, {@code s * (1 + k1 s^2 + k2 s^4 + ...)}, clamped by a
     *  maximum FOV angle (degrees). */
    private float distortedTangent(float screenTangent, float maxFovDegrees) {
        float r2 = screenTangent * screenTangent;
        float factor = 1f;
        if (distortionCoeffs != null) {
            float rPow = 1f;
            for (float k : distortionCoeffs) {
                rPow *= r2;
                factor += k * rPow;
            }
        }
        float distorted = screenTangent * factor;
        float maxTan = (float) Math.tan(Math.toRadians(maxFovDegrees));
        return Math.min(distorted, maxTan);
    }

    // --- tiny protobuf wire helpers ---------------------------------------------------

    private static long readVarint(byte[] data, int pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            byte b = data[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return result;
    }

    /** Returns the index just past the varint that starts at {@code pos}. */
    private static int varintEnd(byte[] data, int pos) {
        while ((data[pos] & 0x80) != 0) {
            pos++;
        }
        return pos + 1;
    }

    private static float readFloatLE(byte[] data, int pos) {
        int bits = (data[pos] & 0xFF)
                | (data[pos + 1] & 0xFF) << 8
                | (data[pos + 2] & 0xFF) << 16
                | (data[pos + 3] & 0xFF) << 24;
        return Float.intBitsToFloat(bits);
    }

    private static float[] toArray(ArrayList<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}

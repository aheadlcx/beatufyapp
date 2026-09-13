package com.example.beautycamera;

/**
 * Blends the signals that decide preview orientation into a single rotation:
 * the face's own roll angle (primary, works even when the phone lies flat),
 * the gravity-derived device orientation (fallback when no face is visible),
 * and a manual 180 flip for poses no sensor can disambiguate.
 */
public class OrientationBlender {

    private static final int SWITCH_THRESHOLD = 60; // deg away from current quadrant

    private volatile int gravityDeg = 0;
    private volatile int faceDeg = 0;
    private volatile boolean flip = false;
    private volatile boolean lastFaceSeen = false;

    /** From the gravity sensor: portrait / upside-down / landscape. */
    public void onGravity(int deg) {
        gravityDeg = deg;
    }

    /** Whether a face is currently tracked (switches the primary signal). */
    public void onFacePresence(boolean present) {
        lastFaceSeen = present;
    }

    /** From face tracking: roll-compensate so the head stays upright. */
    public void onFaceRoll(float eulerX, boolean hasFace) {
        if (!hasFace) return;
        float displayRoll = -eulerX; // front camera preview is mirrored
        if (Math.abs(delta(displayRoll, faceDeg)) > SWITCH_THRESHOLD) {
            int q = (int) Math.round(displayRoll / 90f);
            faceDeg = (((q % 4) + 4) % 4) * 90;
        }
    }

    public void toggleFlip() {
        flip = !flip;
    }

    public boolean isFlipped() {
        return flip;
    }

    /** Total rotation to apply to the preview (0/90/180/270). */
    public int compute() {
        int base = lastFaceSeen ? faceDeg : gravityDeg;
        return (base + (flip ? 180 : 0)) % 360;
    }

    private static int delta(float target, int current) {
        int d = (((int) target - current) % 360 + 360) % 360;
        return d > 180 ? d - 360 : d;
    }
}

package io.github.zerohomers0.onesteplite;

/**
 * Window types the host inspects.
 *
 * <p>The public ones mirror {@code WindowManager.LayoutParams}; the rest are
 * SystemUI-private layers that only exist as raw integers in the platform
 * source, so they are named here instead of being spelled out at each call
 * site.
 */
final class WindowTypes {
    /** {@code LayoutParams.TYPE_STATUS_BAR}. */
    static final int STATUS_BAR = 2000;
    /** {@code LayoutParams.TYPE_SYSTEM_DIALOG}. */
    static final int SYSTEM_DIALOG = 2008;
    /** {@code LayoutParams.TYPE_APPLICATION_OVERLAY}. */
    static final int APPLICATION_OVERLAY = 2038;
    /** Native screenshot animation and preview. */
    static final int SCREENSHOT = 2036;

    /** SystemUI's own dialog layer, above the status bar. */
    static final int SYSTEM_UI_DIALOG = 2017;
    /** SystemUI's notification shade. */
    static final int NOTIFICATION_SHADE = 2040;
    /** SystemUI's gesture-navigation layer. */
    static final int GESTURE_NAVIGATION = 2019;
    /** SystemUI's volume and power dialogs. */
    static final int SYSTEM_UI_PANEL = 2020;
    /** SystemUI's magnification overlay. */
    static final int MAGNIFICATION_OVERLAY = 2024;
    /** SystemUI's bubble and pin-icon layer. */
    static final int BUBBLE_LAYER = 2027;
    /** SystemUI's brightness and always-on layers. */
    static final int BRIGHTNESS_LAYER = 2039;
    /** SystemUI's keyguard scrim. */
    static final int KEYGUARD_SCRIM = 2014;

    private WindowTypes() {}
}

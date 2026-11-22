package io.github.zerohomers0.onesteplite;

/** Package names the host inspects or addresses. */
final class Packages {
    /** The SystemUI process this hook runs inside. */
    static final String SYSTEM_UI = "com.android.systemui";
    /** The system_server process that owns window management. */
    static final String SYSTEM_SERVER = "android";
    /** The MIUI launcher that owns the home task. */
    static final String LAUNCHER = "com.miui.home";
    /** The settings app whose resources the settings page borrows. */
    static final String SETTINGS = "com.android.settings";
    /** MIUI's native edge sidebar. */
    static final String SECURITY_CENTER = "com.miui.securitycenter";

    private Packages() {}
}

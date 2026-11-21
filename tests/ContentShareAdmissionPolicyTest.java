package io.github.zerohomers0.onesteplite;

public final class ContentShareAdmissionPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(ContentShareAdmissionPolicy.evaluate(true, true, true, false, false, false, false,
                false, false, false, false, false, false, false, false, false)
                == ContentShareAdmissionPolicy.ADMIT, "settled workspace admits a drop");
        check(ContentShareAdmissionPolicy.admits(ContentShareAdmissionPolicy.ADMIT),
                "admit is admitted");

        // Hard conditions: the workspace cannot receive anything at all.
        check(reject(false, true, true, false, false, false, false), "missing host rejects");
        check(reject(true, false, true, false, false, false, false), "inactive scene rejects");
        check(reject(true, true, false, false, false, false, false), "screen off rejects");
        check(reject(true, true, true, true, false, false, false), "keyguard rejects");
        check(reject(true, true, true, false, true, false, false), "pending suspend rejects");
        check(reject(true, true, true, false, false, true, false), "entering workspace rejects");
        check(reject(true, true, true, false, false, false, true), "blocked live swap rejects");
        check(!ContentShareAdmissionPolicy.admits(ContentShareAdmissionPolicy.REJECT),
                "reject is not admitted");

        // Transient animation states defer instead of refusing mid-gesture.
        check(defer(true, true, true, false, false, false, false, true, false, false, false,
                false, false, false, false, false), "covered cells defer");
        check(defer(true, true, true, false, false, false, false, false, true, false, false,
                false, false, false, false, false), "promotion defers");
        check(defer(true, true, true, false, false, false, false, false, false, true, false,
                false, false, false, false, false), "transfer defers");
        check(defer(true, true, true, false, false, false, false, false, false, false, true,
                false, false, false, false, false), "cell motion defers");
        check(defer(true, true, true, false, false, false, false, false, false, false, false,
                true, false, false, false, false), "side dismiss defers");
        check(defer(true, true, true, false, false, false, false, false, false, false, false,
                false, true, false, false, false), "side motion defers");
        check(defer(true, true, true, false, false, false, false, false, false, false, false,
                false, false, true, false, false), "main side animation defers");
        check(defer(true, true, true, false, false, false, false, false, false, false, false,
                false, false, false, true, false), "captured side defers");
        check(defer(true, true, true, false, false, false, false, false, false, false, false,
                false, false, false, false, true), "live flip zoom defers");
        check(ContentShareAdmissionPolicy.admits(ContentShareAdmissionPolicy.DEFER),
                "deferred drop is still admitted");

        // A hard condition outranks a transient one.
        check(ContentShareAdmissionPolicy.evaluate(true, true, true, true, false, false, false,
                true, true, true, true, true, true, true, true, true)
                == ContentShareAdmissionPolicy.REJECT, "hard condition outranks transient states");

        System.out.println("ContentShareAdmissionPolicy: " + checks + " checks passed");
    }

    private static boolean reject(boolean hostReady, boolean sceneActive, boolean screenOn,
            boolean keyguardLocked, boolean suspendRequested, boolean workspaceEntering,
            boolean liveSwapInputBlocked) {
        return ContentShareAdmissionPolicy.evaluate(hostReady, sceneActive, screenOn,
                keyguardLocked, suspendRequested, workspaceEntering, liveSwapInputBlocked,
                false, false, false, false, false, false, false, false, false)
                == ContentShareAdmissionPolicy.REJECT;
    }

    private static boolean defer(boolean hostReady, boolean sceneActive, boolean screenOn,
            boolean keyguardLocked, boolean suspendRequested, boolean workspaceEntering,
            boolean liveSwapInputBlocked, boolean taskCellsCovered, boolean promoting,
            boolean transferBusy, boolean cellMotion, boolean sideDismiss,
            boolean sideMotionRunning, boolean mainSideAnimating, boolean sideCaptured,
            boolean liveFlipZoom) {
        return ContentShareAdmissionPolicy.evaluate(hostReady, sceneActive, screenOn,
                keyguardLocked, suspendRequested, workspaceEntering, liveSwapInputBlocked,
                taskCellsCovered, promoting, transferBusy, cellMotion, sideDismiss,
                sideMotionRunning, mainSideAnimating, sideCaptured, liveFlipZoom)
                == ContentShareAdmissionPolicy.DEFER;
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
}

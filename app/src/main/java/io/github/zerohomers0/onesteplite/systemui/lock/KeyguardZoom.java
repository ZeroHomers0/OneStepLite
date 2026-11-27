package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Native lock-entry barrier; clearing it never restores or authenticates a workspace. */
final class KeyguardZoom {
    interface Reset { void run() throws Throwable; }
    private volatile boolean blocked,sleeping;
    private boolean sleepHooksReady;
    boolean sleeping(){return sleeping;}
    boolean blocked() { return blocked||sleeping; }
    boolean onAuthenticatedReveal(boolean authenticated) {
        if (!authenticated||sleeping) return false;
        blocked = false;
        return true;
    }
    void onShown(int displayId, boolean keyguard, boolean aod, Reset reset) throws Throwable {
        if (displayId != 0) return;
        blocked = keyguard || aod;
        if (blocked) reset.run();
    }
    void install(ClassLoader loader, Reset reset) {
        XposedHelpers.findAndHookMethod(
                "com.android.server.wm.KeyguardController", loader, "setKeyguardShown",
                int.class, boolean.class, boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try { onShown((Integer) p.args[0], (Boolean) p.args[1],
                                (Boolean) p.args[2], reset); }
                        catch (Throwable error) {
                            XposedBridge.log("OneStepLiteProbe: native keyguard zoom reset failed: " + error);
                        }
                    }
                });
    }
    void onSleep(boolean sleep,Runnable reset){
        if(sleeping==sleep)return;
        sleeping=sleep;
        XposedBridge.log("OneStepLiteProbe: native default-display sleep barrier="+sleep);
        if(sleep)reset.run();
    }
    void installSleep(ClassLoader loader,Runnable reset){
        try{
            Class<?> policy=XposedHelpers.findClass("com.android.server.policy.PhoneWindowManager",loader);
            java.util.Set<XC_MethodHook.Unhook> wake=XposedBridge.hookAllMethods(policy,"startedWakingUp",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){if(sleepHooksReady&&defaultGroup(p))onSleep(false,reset);}
            });
            java.util.Set<XC_MethodHook.Unhook> sleep=XposedBridge.hookAllMethods(policy,"startedGoingToSleep",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){if(sleepHooksReady&&defaultGroup(p))onSleep(true,reset);}
            });
            sleepHooksReady=!wake.isEmpty()&&!sleep.isEmpty();
            if(!sleepHooksReady)XposedBridge.log("OneStepLiteProbe: early sleep/wake pair absent; using native keyguard barrier");
        }catch(Throwable unavailable){sleepHooksReady=false;XposedBridge.log("OneStepLiteProbe: early sleep barrier unavailable: "+unavailable);}
    }
    private static boolean defaultGroup(XC_MethodHook.MethodHookParam p){
        // Legacy API has only a reason (or no wake argument). Current API has
        // displayGroupId then reason. Never act on another display group.
        return p.args.length<2||Integer.valueOf(0).equals(p.args[0]);
    }

}

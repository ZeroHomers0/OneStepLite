package io.github.zerohomers0.onesteplite;

/** A paste request cannot outlive its editor, focused window or short request lifetime. */
final class EditorPastePolicy {
    private EditorPastePolicy(){}
    static boolean sameTarget(int requestedUid,int currentUid,int requestedField,int currentField,boolean window,boolean connection) {
        return requestedUid==currentUid && requestedField==currentField && window && connection;
    }
    static boolean allowed(boolean same,int length,long age) {
        return same && length>0 && length<=ContentSharePolicy.MAX_TEXT && age>=0 && age<=1500;
    }
}

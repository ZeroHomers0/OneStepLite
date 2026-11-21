package io.github.zerohomers0.onesteplite;

/**
 * A picker launched by the main app must come back to the caller, not replace it. The workspace
 * keeps the caller task alive for these actions so the result has somewhere to return to.
 */
final class CrossAppRoutingPolicy {
    private static final int FLAG_ACTIVITY_FORWARD_RESULT = 0x02000000;
    private static final String[] PRESERVE_CALLER = {
            "android.intent.action.GET_CONTENT",
            "android.intent.action.OPEN_DOCUMENT",
            "android.intent.action.CREATE_DOCUMENT",
            "android.intent.action.OPEN_DOCUMENT_TREE",
            "android.intent.action.PICK",
            "android.intent.action.PICK_ACTIVITY",
            "android.provider.action.PICK_IMAGES",
            "android.provider.action.PICK_IMAGES_MAX",
            "android.media.action.IMAGE_CAPTURE",
            "android.media.action.IMAGE_CAPTURE_SECURE",
            "android.media.action.VIDEO_CAPTURE",
            "android.provider.MediaStore.RECORD_SOUND",
            "android.intent.action.RINGTONE_PICKER",
            "android.speech.action.RECOGNIZE_SPEECH",
    };

    private CrossAppRoutingPolicy() {}

    static boolean shouldPreserveCallerTask(String action, int flags) {
        if ((flags & FLAG_ACTIVITY_FORWARD_RESULT) != 0) return true;
        if (action == null) return false;
        for (String candidate : PRESERVE_CALLER) if (candidate.equals(action)) return true;
        return false;
    }
}

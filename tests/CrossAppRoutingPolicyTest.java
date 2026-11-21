package io.github.zerohomers0.onesteplite;

public final class CrossAppRoutingPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.intent.action.GET_CONTENT", 0), "get content keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.intent.action.OPEN_DOCUMENT", 0), "open document keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.intent.action.CREATE_DOCUMENT", 0), "create document keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.intent.action.OPEN_DOCUMENT_TREE", 0), "open tree keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.intent.action.PICK", 0), "pick keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.intent.action.PICK_ACTIVITY", 0), "pick activity keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.provider.action.PICK_IMAGES", 0), "pick images keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.provider.action.PICK_IMAGES_MAX", 0), "pick images max keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.media.action.IMAGE_CAPTURE", 0), "image capture keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.media.action.IMAGE_CAPTURE_SECURE", 0), "secure capture keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.media.action.VIDEO_CAPTURE", 0), "video capture keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.provider.MediaStore.RECORD_SOUND", 0), "record sound keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.intent.action.RINGTONE_PICKER", 0), "ringtone picker keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.speech.action.RECOGNIZE_SPEECH", 0), "speech keeps the caller");

        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.intent.action.VIEW", 0x02000000), "forward result keeps the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.intent.action.VIEW", 0x02000000 | 0x10000000),
                "forward result survives other flags");

        check(!CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.intent.action.VIEW", 0), "plain view replaces the caller");
        check(!CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.intent.action.MAIN", 0), "main replaces the caller");
        check(!CrossAppRoutingPolicy.shouldPreserveCallerTask(null, 0), "missing action replaces");
        check(!CrossAppRoutingPolicy.shouldPreserveCallerTask("", 0), "empty action replaces");
        check(!CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.intent.action.VIEW", 0x10000000),
                "unrelated flags do not preserve the caller");
        check(CrossAppRoutingPolicy.shouldPreserveCallerTask(
                "android.intent.action.GET_CONTENT", 0x10000000),
                "whitelisted action preserves regardless of other flags");

        System.out.println("CrossAppRoutingPolicy: " + checks + " checks passed");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
}

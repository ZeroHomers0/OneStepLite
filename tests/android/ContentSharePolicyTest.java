package io.github.zerohomers0.onesteplite;

public final class ContentSharePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        for (String mime : new String[]{"text/plain", "text/html", "image/jpeg", "image/*",
                "application/pdf", "audio/mpeg", "video/mp4"})
            check(ContentSharePolicy.accepts(mime), "content type rejected: " + mime);
        for (String mime : new String[]{null, "", "text/vnd.android.intent",
                "application/vnd.android.activity", "application/vnd.android.shortcut",
                "application/vnd.android.task", "unknown/type"})
            check(!ContentSharePolicy.accepts(mime), "launch payload accepted: " + mime);
        check(ContentSharePolicy.validSize(1, 0), "file-only content accepted");
        check(ContentSharePolicy.validSize(32, 100000), "boundary accepted");
        check(!ContentSharePolicy.validSize(0, 1), "empty clip rejected");
        check(!ContentSharePolicy.validSize(33, 1), "too many files rejected");
        check(!ContentSharePolicy.validSize(1, 100001), "oversized text rejected");
        check(!ContentSharePolicy.validSize(1, -1), "invalid text length rejected");
        System.out.println("ContentSharePolicyTest checks=" + checks + " passed");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

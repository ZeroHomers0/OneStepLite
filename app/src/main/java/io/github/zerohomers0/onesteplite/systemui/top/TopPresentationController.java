package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;

/** Coordinates exclusive video admission, task controls and privacy release. */
final class TopPresentationController {
    interface Host {
        boolean mayCaptureVideo();
        boolean mayShowTaskControls();
        int width();
        int height();
        void videoChanged();
        void exitTask();
    }
    private final Host host;
    private final VideoPane video;
    private final TopOverlayControls controls;

    TopPresentationController(Context context,Handler handler,WindowManager windows,Host host) {
        this.host=host;
        controls=new TopOverlayControls(context,windows,host::exitTask);
        video=new VideoPane(context,handler,host::mayCaptureVideo,embedded->host.videoChanged());
    }

    VideoPane video() { return video; }
    View controlsView() { return controls.view(); }

    void refreshControls() {
        if(host.mayShowTaskControls())controls.show(host.width(),host.height());
        else controls.close();
    }

    void hideControls() { controls.close(); }
    void refreshVideo() { video.refresh(); }
    void releaseVideo() { video.releaseForPrivacy(); }
}

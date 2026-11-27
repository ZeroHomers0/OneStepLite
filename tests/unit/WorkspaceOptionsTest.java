package io.github.zerohomers0.onesteplite;

public final class WorkspaceOptionsTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static LiveSwapGeometry.Rect main(WorkspaceLayout layout) {
        return new LiveSwapGeometry.Rect(layout.mainOffsetX, layout.mainOffsetY,
                layout.width * layout.mainScale, layout.height * layout.mainScale);
    }
    private static LiveSwapGeometry.Rect side(WorkspaceLayout layout, int slot) {
        int[] b = layout.cellBounds(slot);
        return new LiveSwapGeometry.Rect(b[0], b[1], b[2] - b[0], b[3] - b[1]);
    }
    public static void main(String[] args) {
        check(!WorkspaceOptions.DEFAULT.parkMainOnLaunch,"main collection must be opt-in");
        check(WorkspaceOptions.DEFAULT.withParkMainOnLaunch(true).withSwapMode(1).withEntryGesture(1)
                .withKeepOnLock(true).withScrollSideTasks(true).withShowLunar(false)
                .withLandscapeVideo(WorkspaceOptions.VIDEO_FULLSCREEN).withHideSideRecents(true).parkMainOnLaunch,
                "other settings must preserve main collection preference");
        check(!WorkspaceOptions.DEFAULT.withParkMainOnLaunch(true).withParkMainOnLaunch(false).parkMainOnLaunch,
                "main collection can be disabled again");
        check(!WorkspaceOptions.DEFAULT.deleteSharedImages,"temporary image cleanup is opt-in");
        check(WorkspaceOptions.DEFAULT.withDeleteSharedImages(true).withSwapMode(1).withEntryGesture(1)
                .withKeepOnLock(true).withScrollSideTasks(true).withShowLunar(false).withParkMainOnLaunch(true)
                .withLandscapeVideo(WorkspaceOptions.VIDEO_FULLSCREEN).withHideSideRecents(true).deleteSharedImages,
                "all other settings preserve temporary image cleanup preference");
        check(!WorkspaceOptions.DEFAULT.withDeleteSharedImages(true).withDeleteSharedImages(false).deleteSharedImages,
                "temporary image cleanup can be disabled");
        WorkspaceOptions defaults = WorkspaceOptions.DEFAULT;
        check(defaults.showLunar, "lunar date visible by default");
        check(defaults.showTime && defaults.showDate, "time and date visible by default");
        check(!defaults.withShowTime(false).withShowDate(false).withSwapMode(1).withEntryGesture(1).showTime,
                "interaction changes preserve time visibility preference");
        check(!defaults.withShowTime(false).withShowDate(false).withSwapMode(1).withEntryGesture(1).showDate,
                "interaction changes preserve date visibility preference");
        check(!defaults.withShowLunar(false).withSwapMode(1).withEntryGesture(1).showLunar,
                "interaction changes preserve lunar preference");
        check(new WorkspaceOptions(0, -1).swapMode == WorkspaceOptions.GROW_IN_PLACE, "invalid mode changed existing behavior");
        for (boolean right : new boolean[]{false, true}) for (int mode : new int[]{0, 1}) {
            WorkspaceOptions options = defaults.withShowLunar(false).withSwapMode(mode);
            check(!options.showLunar, "mode reset date settings");
            check(options.mainOnRightAfterSwap(right) == (mode == WorkspaceOptions.FIXED_MAIN ? right : !right), "wrong target main side");
            for (int width : new int[]{720, 1080, 1200, 1440}) {
                int height = width * 22 / 10;
                WorkspaceLayout before = WorkspaceLayout.of(width, height, right, width / 393f);
                WorkspaceLayout after = WorkspaceLayout.of(width, height, options.mainOnRightAfterSwap(right), width / 393f);
                for (int slot = 0; slot < WorkspaceLayout.CELL_COUNT; slot++) {
                    LiveSwapGeometry.Task a = new LiveSwapGeometry.Task(1, "A", width, height, main(before), side(after, slot));
                    LiveSwapGeometry.Task b = new LiveSwapGeometry.Task(2, "B", width, height, side(before, slot), main(after));
                    LiveSwapGeometry.Task peer = new LiveSwapGeometry.Task(3, "C", width, height, side(before, (slot + 1) % 3), side(after, (slot + 1) % 3));
                    for (double fraction : new double[]{0, .25, .5, .75, 1}) {
                        LiveSwapGeometry.Frame[] frames = LiveSwapGeometry.frames(new LiveSwapGeometry.Task[]{a, b, peer},
                                LiveSwapGeometry.Affine.identity(), new LiveSwapGeometry.Affine[]{LiveSwapGeometry.Affine.identity(),
                                        LiveSwapGeometry.Affine.identity(), LiveSwapGeometry.Affine.identity()}, fraction);
                        for (LiveSwapGeometry.Frame frame : frames)
                            check(Double.isFinite(frame.physical.left) && frame.physical.width > 0, "real task path became invalid");
                        if (mode == WorkspaceOptions.FIXED_MAIN)
                            check(Math.abs(frames[2].physical.left - peer.start.left) < .00001, "fixed mode moved uninvolved peer");
                        if (fraction == 1) {
                            check(Math.abs(frames[1].physical.left - after.mainOffsetX) < .00001, "new main missed target");
                            check(Math.abs(frames[0].physical.left - a.end.left) < .00001, "old main missed side slot");
                        }
                    }
                }
            }
        }
        System.out.println("WorkspaceOptions checks=" + checks + " passed (both modes, both sides, real task geometry)");
    }
}

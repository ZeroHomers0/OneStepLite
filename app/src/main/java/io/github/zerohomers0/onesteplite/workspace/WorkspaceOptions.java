package io.github.zerohomers0.onesteplite;

/** User choices independent of the fixed workspace toolbar. */
final class WorkspaceOptions {
    static final int GROW_IN_PLACE=0,FIXED_MAIN=1;
    static final int STATUS_BAR_INWARD=0,BOTTOM_CENTER_OUTWARD=1;
    // Floating video is docked automatically and a
    // landscape app can be sent up by hand, so only these two remain. The
    // numbers keep their old values so stored choices still read correctly.
    static final int VIDEO_TURNED=1,VIDEO_FULLSCREEN=2;
    static final WorkspaceOptions DEFAULT=new WorkspaceOptions(0,0,0,false,false,true);
    static final int THEME_SYSTEM=0,THEME_LIGHT=1,THEME_DARK=2;
    final int swapMode,entryGesture,landscapeVideo,themeMode;
    final boolean keepOnLock,scrollSideTasks,showLunar,hideSideRecents,parkMainOnLaunch,deleteSharedImages,followTopBarSide,dragShareEnabled;
    // The first argument is retained for existing internal geometry callers;
    // legacy button visibility is intentionally ignored and is no longer persisted.
    WorkspaceOptions(int legacy,int swap){this(legacy,swap,0,false,false,true);}
    WorkspaceOptions(int legacy,int swap,int entry){this(legacy,swap,entry,false,false,true);}
    WorkspaceOptions(int legacy,int swap,int entry,boolean keep){this(legacy,swap,entry,keep,false,true);}
    WorkspaceOptions(int legacy,int swap,int entry,boolean keep,boolean scroll){this(legacy,swap,entry,keep,scroll,true);}
    WorkspaceOptions(int legacy,int swap,int entry,boolean keep,boolean scroll,boolean lunar){
        this(legacy,swap,entry,keep,scroll,lunar,VIDEO_TURNED);
    }
    WorkspaceOptions(int legacy,int swap,int entry,boolean keep,boolean scroll,boolean lunar,int video){
        this(legacy,swap,entry,keep,scroll,lunar,video,false);
    }
    WorkspaceOptions(int legacy,int swap,int entry,boolean keep,boolean scroll,boolean lunar,int video,boolean hide){
        this(legacy,swap,entry,keep,scroll,lunar,video,hide,false);
    }
    WorkspaceOptions(int legacy,int swap,int entry,boolean keep,boolean scroll,boolean lunar,int video,boolean hide,boolean park){
        this(legacy,swap,entry,keep,scroll,lunar,video,hide,park,false);
    }
    WorkspaceOptions(int legacy,int swap,int entry,boolean keep,boolean scroll,boolean lunar,int video,boolean hide,boolean park,boolean delete){
        this(legacy,swap,entry,keep,scroll,lunar,video,hide,park,delete,THEME_SYSTEM);
    }
    WorkspaceOptions(int legacy,int swap,int entry,boolean keep,boolean scroll,boolean lunar,int video,
            boolean hide,boolean park,boolean delete,int theme){
        this(legacy,swap,entry,keep,scroll,lunar,video,hide,park,delete,theme,true);
    }
    WorkspaceOptions(int legacy,int swap,int entry,boolean keep,boolean scroll,boolean lunar,int video,
            boolean hide,boolean park,boolean delete,int theme,boolean follow){
        this(legacy,swap,entry,keep,scroll,lunar,video,hide,park,delete,theme,follow,true);
    }
    WorkspaceOptions(int legacy,int swap,int entry,boolean keep,boolean scroll,boolean lunar,int video,
            boolean hide,boolean park,boolean delete,int theme,boolean follow,boolean dragShare){
        followTopBarSide=follow;
        dragShareEnabled=dragShare;
        themeMode=theme==THEME_LIGHT || theme==THEME_DARK?theme:THEME_SYSTEM;
        swapMode=swap==FIXED_MAIN?FIXED_MAIN:GROW_IN_PLACE;
        entryGesture=entry==BOTTOM_CENTER_OUTWARD?BOTTOM_CENTER_OUTWARD:STATUS_BAR_INWARD;
        hideSideRecents=hide;
        keepOnLock=keep;scrollSideTasks=scroll;showLunar=lunar;
        parkMainOnLaunch=park;deleteSharedImages=delete;
        landscapeVideo=video==VIDEO_FULLSCREEN?VIDEO_FULLSCREEN:VIDEO_TURNED;
    }
    WorkspaceOptions withSwapMode(int mode){return new WorkspaceOptions(0,mode,entryGesture,keepOnLock,scrollSideTasks,showLunar,landscapeVideo,hideSideRecents,parkMainOnLaunch,deleteSharedImages,themeMode,followTopBarSide,dragShareEnabled);}
    WorkspaceOptions withEntryGesture(int mode){return new WorkspaceOptions(0,swapMode,mode,keepOnLock,scrollSideTasks,showLunar,landscapeVideo,hideSideRecents,parkMainOnLaunch,deleteSharedImages,themeMode,followTopBarSide,dragShareEnabled);}
    WorkspaceOptions withKeepOnLock(boolean keep){return new WorkspaceOptions(0,swapMode,entryGesture,keep,scrollSideTasks,showLunar,landscapeVideo,hideSideRecents,parkMainOnLaunch,deleteSharedImages,themeMode,followTopBarSide,dragShareEnabled);}
    WorkspaceOptions withScrollSideTasks(boolean scroll){return new WorkspaceOptions(0,swapMode,entryGesture,keepOnLock,scroll,showLunar,landscapeVideo,hideSideRecents,parkMainOnLaunch,deleteSharedImages,themeMode,followTopBarSide,dragShareEnabled);}
    WorkspaceOptions withShowLunar(boolean lunar){return new WorkspaceOptions(0,swapMode,entryGesture,keepOnLock,scrollSideTasks,lunar,landscapeVideo,hideSideRecents,parkMainOnLaunch,deleteSharedImages,themeMode,followTopBarSide,dragShareEnabled);}
    WorkspaceOptions withLandscapeVideo(int video){return new WorkspaceOptions(0,swapMode,entryGesture,keepOnLock,scrollSideTasks,showLunar,video,hideSideRecents,parkMainOnLaunch,deleteSharedImages,themeMode,followTopBarSide,dragShareEnabled);}
    WorkspaceOptions withHideSideRecents(boolean hide){return new WorkspaceOptions(0,swapMode,entryGesture,keepOnLock,scrollSideTasks,showLunar,landscapeVideo,hide,parkMainOnLaunch,deleteSharedImages,themeMode,followTopBarSide,dragShareEnabled);}
    WorkspaceOptions withParkMainOnLaunch(boolean allow){return new WorkspaceOptions(0,swapMode,entryGesture,keepOnLock,scrollSideTasks,showLunar,landscapeVideo,hideSideRecents,allow,deleteSharedImages,themeMode,followTopBarSide,dragShareEnabled);}
    WorkspaceOptions withDeleteSharedImages(boolean delete){return new WorkspaceOptions(0,swapMode,entryGesture,keepOnLock,scrollSideTasks,showLunar,landscapeVideo,hideSideRecents,parkMainOnLaunch,delete,themeMode,followTopBarSide,dragShareEnabled);}
    WorkspaceOptions withThemeMode(int theme){return new WorkspaceOptions(0,swapMode,entryGesture,keepOnLock,
            scrollSideTasks,showLunar,landscapeVideo,hideSideRecents,parkMainOnLaunch,deleteSharedImages,theme,followTopBarSide,dragShareEnabled);}
    WorkspaceOptions withFollowTopBarSide(boolean follow){return new WorkspaceOptions(0,swapMode,entryGesture,keepOnLock,
            scrollSideTasks,showLunar,landscapeVideo,hideSideRecents,parkMainOnLaunch,deleteSharedImages,themeMode,follow,dragShareEnabled);}
    WorkspaceOptions withDragShareEnabled(boolean enabled){return new WorkspaceOptions(0,swapMode,entryGesture,keepOnLock,
            scrollSideTasks,showLunar,landscapeVideo,hideSideRecents,parkMainOnLaunch,deleteSharedImages,themeMode,followTopBarSide,enabled);}
    static String themeLabel(int mode){return mode==THEME_LIGHT?"浅色":mode==THEME_DARK?"深色":"跟随系统";}
    static String landscapeLabel(int video){return video==VIDEO_FULLSCREEN?"退出工作区，全屏显示":"在主窗中显示";}
    boolean mainOnRightAfterSwap(boolean currentRight){return swapMode==FIXED_MAIN?currentRight:!currentRight;}
    static String modeLabel(int mode){return mode==FIXED_MAIN?"保持主窗位置":"随应用切换位置";}
}

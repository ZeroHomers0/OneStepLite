package io.github.zerohomers0.onesteplite;

/** Shared import and one-use launch bounds keep untrusted share input finite. */
final class ContentInboxPolicy {
    static final long MAX_FILE=100L*1024*1024,MAX_TOTAL=200L*1024*1024;
    static final int MAX_RECORDS=128,MAX_CAPTURE_RESERVE=8;
    private ContentInboxPolicy(){}
    static boolean validId(String id){return id!=null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
    static boolean withinBudget(long copied,long budget){return copied>=0 && copied<=Math.min(MAX_FILE,budget);}
    static boolean withinInbox(int records,long bytes){return records<=MAX_RECORDS && bytes<=MAX_TOTAL;}
    static boolean canReserveCapture(boolean capture,int reserved){return capture && reserved<MAX_CAPTURE_RESERVE;}
    static int evictionPriority(boolean staged,boolean removed,boolean reserved){
        if(staged)return -1;
        if(removed)return 0;
        return reserved?1:2;
    }
    static boolean liveLaunch(long now,long created){return now>=created && now-created<=30_000L;}
}

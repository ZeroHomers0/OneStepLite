package io.github.zerohomers0.onesteplite;

public final class ContentWorkflowPolicyTest {
    private static int checks;
    public static void main(String[] args) {
        ContentSelection selection=new ContentSelection();
        for(int i=0;i<32;i++)check(selection.toggle(0,"image"+i),"bounded selection accepts item");
        check(!selection.toggle(0,"overflow"),"33rd selection rejected");
        check(selection.toggle(0,"image0")&&!selection.contains(0,"image0"),"deselect at capacity");
        check(selection.toggle(1,"file")&&selection.keys(0).size()==31,"page selection isolated");
        selection.position(0,80);selection.position(1,140);
        check(selection.position(0)==80&&selection.position(1)==140,"page scroll independent");
        selection.keys(0).clear();check(selection.keys(0).size()==31,"selection snapshots cannot mutate owner");
        check(selection.toggle(3,"shelf")&&selection.keys(0).size()==31,"shelf selection independent of media");
        selection.position(3,250);check(selection.position(3)==250,"shelf keeps scroll position");
        selection.clearSelections();
        check(selection.keys(0).isEmpty()&&selection.keys(1).isEmpty(),"panel close clears every selection");
        check(selection.position(0)==80&&selection.position(1)==140,"panel close preserves page positions");
        selection.toggle(0,"after-close");
        selection.reset();check(selection.keys(1).isEmpty()&&selection.position(1)==0,"user reset clears records");
        check(selection.keys(3).isEmpty()&&selection.position(3)==0,"user reset clears shelf position and selection");
        ShareEdgePolicy edge=new ShareEdgePolicy();
        check(edge.update(99,0,100,10,0)==0,"edge does not jump immediately");
        check(edge.update(99,0,100,10,149)==0,"dwell threshold");
        check(edge.update(99,0,100,10,150)==1,"stationary edge scroll starts");
        check(edge.update(1,0,100,10,151)==0,"opposite edge restarts dwell");
        check(edge.update(1,0,100,10,301)==-1,"left edge scroll");
        check(edge.update(-1,0,100,10,702)==0,"outside cancels");
        edge.reset();check(edge.update(99,0,100,10,900)==0,"cancel resets dwell");
        check(edge.step(99,0,100,10,1050)==2,"scroll begins gently after dwell");
        check(edge.step(99,0,100,10,1600)==7,"deep edge accelerates within bound");
        check(edge.step(91,0,100,10,1601)==3,"shallow edge remains slow");
        check(edge.step(50,0,100,10,1802)==0,"leaving edge stops immediately");
        check(edge.step(Float.NaN,0,100,10,1803)==0,"invalid pointer cannot scroll");
        check(edge.step(99,0,100,10,1804)==0,"reentry requires a fresh dwell");
        String id="12345678-1234-1234-1234-123456789abc";
        check(ContentInboxPolicy.validId(id),"UUID accepted");
        for(String invalid:new String[]{null,"../secret","------------------------------------",id+"/file"})
            check(!ContentInboxPolicy.validId(invalid),"path/invalid identity rejected");
        check(ContentInboxPolicy.withinBudget(ContentInboxPolicy.MAX_FILE,ContentInboxPolicy.MAX_TOTAL),"file limit inclusive");
        check(!ContentInboxPolicy.withinBudget(ContentInboxPolicy.MAX_FILE+1,ContentInboxPolicy.MAX_TOTAL),"file cap");
        check(!ContentInboxPolicy.withinBudget(10,9),"remaining total cap");
        check(!ContentInboxPolicy.withinBudget(0,-1),"full cache rejects even empty new file");
        check(ContentInboxPolicy.withinInbox(128,ContentInboxPolicy.MAX_TOTAL)
                && !ContentInboxPolicy.withinInbox(129,ContentInboxPolicy.MAX_TOTAL),"ordinary inbox retains its original limits");
        check(ContentInboxPolicy.canReserveCapture(true,7) && !ContentInboxPolicy.canReserveCapture(true,8)
                && !ContentInboxPolicy.canReserveCapture(false,0),"reserve is bounded and only available to captures");
        check(ContentInboxPolicy.evictionPriority(true,true,true)==-1,"staged shelf content is never evicted");
        check(ContentInboxPolicy.evictionPriority(false,true,false)==0,"removed shelf backing is reclaimed first");
        check(ContentInboxPolicy.evictionPriority(false,false,true)==1,"reserved captures are reclaimed before ordinary content");
        check(ContentInboxPolicy.evictionPriority(false,false,false)==2,"ordinary temporary content is the final fallback");
        check(ContentInboxPolicy.liveLaunch(30000,0)&&!ContentInboxPolicy.liveLaunch(30001,0),"token expiry exact");
        check(!ContentInboxPolicy.liveLaunch(0,1),"clock rollback rejects token");
        check(DropDelegationPolicy.allowed(1000,1000,0,10123,true,true,true),"same-user real drop allowed");
        check(!DropDelegationPolicy.allowed(10123,1000,0,10123,true,true,true),"foreign caller rejected");
        check(!DropDelegationPolicy.allowed(1000,1000,10,10123,true,true,true),"foreign source profile rejected");
        check(!DropDelegationPolicy.allowed(1000,1000,0,1010123,true,true,true),"cross-profile recipient rejected");
        check(!DropDelegationPolicy.allowed(1000,1000,0,10123,false,true,true),"write-only drop rejected");
        check(!DropDelegationPolicy.allowed(1000,1000,0,10123,true,false,true),"locked/inactive rejected");
        check(!DropDelegationPolicy.allowed(1000,1000,0,10123,true,true,false),"another window drop rejected");
        java.util.List<String> retired=new java.util.ArrayList<>();
        ShareLaunchRequests<String> requests=new ShareLaunchRequests<>(2,retired::add);
        requests.put("one","first",0);requests.put("two","second",1);
        check("first".equals(requests.consume("one",2)),"issued launch consumed once");
        check(requests.consume("one",3)==null,"replayed launch rejected");
        requests.put("three","third",4);requests.put("four","fourth",5);
        check(retired.contains("second"),"bounded eviction releases capability");
        check(requests.consume("three",30005)==null&&retired.contains("third"),"expired launch releases capability");
        check("fourth".equals(requests.consume("four",30005)),"expiry boundary remains consumable");
        requests.put("same","old",40000);requests.put("same","new",40001);
        check(retired.contains("old")&&"new".equals(requests.consume("same",40002)),"replaced token retires old capability");
        System.out.println("ContentWorkflowPolicyTest checks="+checks+" passed");
    }
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}

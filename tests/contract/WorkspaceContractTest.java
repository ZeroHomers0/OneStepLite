package io.github.zerohomers0.onesteplite;

/** Pure JVM checks for cross-process names and immutable signal validation. */
public final class WorkspaceContractTest {
    private static int checks;

    private static void check(boolean value,String name) {
        checks++;
        if(!value)throw new AssertionError(name);
    }

    public static void main(String[] args) {
        check(Actions.ZOOM.equals(WorkspaceContract.ZOOM),
                "zoom action stays wire compatible");
        check(Actions.TOP_TASK_STATE.equals(WorkspaceContract.TOP_TASK_STATE),
                "top task action stays wire compatible");
        check("fullscreen".equals(WorkspaceContract.EXTRA_FULLSCREEN),"fullscreen extra stays wire compatible");
        check("viewportOrientation".equals(WorkspaceContract.EXTRA_VIEWPORT_ORIENTATION),
                "viewport extra stays wire compatible");
        Object token=new Object();
        WorkspaceContract.TopTaskSignal valid=new WorkspaceContract.TopTaskSignal(
                7,10,token,true,false,true,6,true,"pkg/.Page",3,2,1234L);
        check(valid.hasTaskIdentity(),"complete TOP identity is accepted");
        check(!new WorkspaceContract.TopTaskSignal(-1,10,token,false,false,false,1,false,null,0,0,0)
                .hasTaskIdentity(),"negative task is rejected");
        check(!new WorkspaceContract.TopTaskSignal(7,-1,token,false,false,false,1,false,null,0,0,0)
                .hasTaskIdentity(),"negative user is rejected");
        check(!new WorkspaceContract.TopTaskSignal(7,10,null,false,false,false,1,false,null,0,0,0)
                .hasTaskIdentity(),"missing token is rejected");
        check(ProtocolVersion.compatible(ProtocolVersion.CURRENT),"current protocol is accepted");
        check(!ProtocolVersion.compatible(0)&&!ProtocolVersion.compatible(ProtocolVersion.CURRENT+1),
                "unknown protocol versions are rejected");
        ZoomProtocol.Request zoom=ZoomProtocol.decode(ZoomProtocol.encode("anim .7 600 0 300",1234L));
        check(zoom!=null&&zoom.issuedAt()==1234L&&zoom.command().startsWith("anim "),
                "zoom protocol round-trips issued time and command");
        check("0.7 600 0".equals(ZoomProtocol.steady("anim 0.7 600 0 300")),
                "animation steady key keeps the zoom tuple");
        WorkspaceControlRequest control=new WorkspaceControlRequest(WorkspaceControlRequest.TOGGLE,
                ProtocolVersion.CURRENT,42L,"test");
        check(control.supported(),"current toggle control request is accepted");
        check(!new WorkspaceControlRequest("unknown",ProtocolVersion.CURRENT,42L,"test").supported(),
                "unknown control command is rejected");
        WorkspaceControlResult result=new WorkspaceControlResult(42L,WorkspaceControlResult.Status.ACCEPTED,"ok");
        check(result.requestId()==42L&&result.status()==WorkspaceControlResult.Status.ACCEPTED,
                "control result preserves request identity and status");
        System.out.println("Workspace contract checks="+checks+" passed");
    }
}
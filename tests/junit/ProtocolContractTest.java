package io.github.zerohomers0.onesteplite;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ProtocolContractTest {
    @Test public void protocolVersionRejectsUnknownGenerations() {
        assertTrue(ProtocolVersion.compatible(ProtocolVersion.CURRENT));
        assertFalse(ProtocolVersion.compatible(0));
        assertFalse(ProtocolVersion.compatible(ProtocolVersion.CURRENT+1));
    }

    @Test public void zoomProtocolRoundTripsLeaseAndSteadyTuple() {
        ZoomProtocol.Request request=ZoomProtocol.decode(
                ZoomProtocol.encode("anim 0.7 600 0 300",1234L));
        assertNotNull(request);
        assertEquals(1234L,request.issuedAt());
        assertEquals("anim 0.7 600 0 300",request.command());
        assertEquals("0.7 600 0",ZoomProtocol.steady(request.command()));
    }

    @Test public void controlEnvelopeValidatesVersionAndCommand() {
        assertTrue(new WorkspaceControlRequest(WorkspaceControlRequest.TOGGLE,
                ProtocolVersion.CURRENT,7L,"test").supported());
        assertFalse(new WorkspaceControlRequest("unknown",
                ProtocolVersion.CURRENT,7L,"test").supported());
        WorkspaceControlResult result=new WorkspaceControlResult(7L,
                WorkspaceControlResult.Status.ACCEPTED,"ok");
        assertEquals(7L,result.requestId());
        assertEquals(WorkspaceControlResult.Status.ACCEPTED,result.status());
    }
}
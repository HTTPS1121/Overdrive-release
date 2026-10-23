package com.overdrive.app.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ParkedLinkContractTest {
    @Test
    public void onlyDefiniteNonDispatchCanDiscardOwnership() {
        for (int result : new int[]{
                ParkedLinkContract.RESULT_REJECTED,
                ParkedLinkContract.RESULT_BIND_FAILED,
                ParkedLinkContract.RESULT_NOT_DISPATCHED,
                ParkedLinkContract.RESULT_SERVICE_UNAVAILABLE,
                ParkedLinkContract.RESULT_BAD_REQUEST,
                ParkedLinkContract.RESULT_STALE_GENERATION,
                ParkedLinkContract.RESULT_WORKER_START_FAILED,
                ParkedLinkContract.RESULT_UNAUTHORIZED,
                ParkedLinkContract.RESULT_LIBRARY_UNAVAILABLE,
                ParkedLinkContract.RESULT_LIBRARY_INCOMPATIBLE}) {
            assertTrue("result=" + result, ParkedLinkContract.definitelyNotApplied(result));
        }
        for (int result : new int[]{
                ParkedLinkContract.RESULT_CONFIRMED,
                ParkedLinkContract.RESULT_DISPATCHED_UNCONFIRMED,
                ParkedLinkContract.RESULT_TRANSPORT_FAILED,
                ParkedLinkContract.RESULT_INTERNAL_FAILED,
                ParkedLinkContract.RESULT_BROADCAST_UNKNOWN,
                Integer.MIN_VALUE, -999}) {
            assertFalse("result=" + result, ParkedLinkContract.definitelyNotApplied(result));
        }
    }

    @Test
    public void commandsHaveDistinctAcquireReleaseAndDetachOperations() {
        assertEquals(ParkedLinkContract.OP_ASSERT,
                ParkedLinkContract.operationFor(ParkedLinkContract.COMMAND_START));
        assertEquals(ParkedLinkContract.OP_RELEASE,
                ParkedLinkContract.operationFor(ParkedLinkContract.COMMAND_STOP));
        assertEquals(ParkedLinkContract.OP_DETACH,
                ParkedLinkContract.operationFor(ParkedLinkContract.COMMAND_DETACH));
        assertEquals(0, ParkedLinkContract.operationFor(null));
        assertEquals(0, ParkedLinkContract.operationFor("unknown"));
    }
}

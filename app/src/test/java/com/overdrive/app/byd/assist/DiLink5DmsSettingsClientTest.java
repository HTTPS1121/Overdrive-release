package com.overdrive.app.byd.assist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.assist.DiLink5DmsSettingsClient.Delivery;
import com.overdrive.app.byd.assist.DiLink5DmsSettingsClient.Outcome;
import com.overdrive.app.byd.assist.DiLink5DmsSettingsClient.Result;
import com.overdrive.app.byd.assist.DiLink5DmsSettingsClient.Setting;

import java.util.EnumSet;

import org.junit.Test;

/**
 * Pure per-transaction delivery record of the DMS route: which of the three settings (monitoring
 * tx 2, fatigue tx 4, attention tx 5) the vendor settings service acknowledged across the attempts
 * of one cycle, and the final state the applier reports from it.
 */
public class DiLink5DmsSettingsClientTest {

    @Test
    public void settingsAreTheThreeTransactionsInSendOrder() {
        assertEquals(3, Setting.values().length);
        assertEquals(Setting.MONITORING, Setting.values()[0]);
        assertEquals(Setting.FATIGUE, Setting.values()[1]);
        assertEquals(Setting.ATTENTION, Setting.values()[2]);
        assertEquals("monitoring", Setting.MONITORING.label);
        assertEquals("fatigue", Setting.FATIGUE.label);
        assertEquals("attention", Setting.ATTENTION.label);
        assertEquals(2, Setting.MONITORING.transactionCode);
        assertEquals(4, Setting.FATIGUE.transactionCode);
        assertEquals(5, Setting.ATTENTION.transactionCode);
    }

    @Test
    public void nothingAcknowledgedIsFailed() {
        Delivery delivery = new Delivery();
        assertFalse(delivery.anyAcknowledged());
        assertFalse(delivery.allAcknowledged());
        assertEquals(EnumSet.allOf(Setting.class), delivery.pending());
        assertEquals("monitoring=failed, fatigue=failed, attention=failed", delivery.summary());
        Result result = delivery.finalResult("not sent after 3 attempt(s): TIMEOUT");
        assertEquals(Outcome.FAILED, result.outcome);
        assertEquals("not sent after 3 attempt(s): TIMEOUT", result.detail);

        // Explicit failures on every transaction are the same thing.
        for (Setting s : Setting.values()) delivery.record(s, false);
        assertFalse(delivery.anyAcknowledged());
        assertEquals(Outcome.FAILED, delivery.finalResult("x").outcome);
    }

    @Test
    public void partialDeliveryIsRequestSentWithThePerTransactionDetail() {
        Delivery delivery = new Delivery();
        delivery.record(Setting.MONITORING, true);
        delivery.record(Setting.FATIGUE, false);
        delivery.record(Setting.ATTENTION, true);
        assertTrue(delivery.anyAcknowledged());
        assertFalse(delivery.allAcknowledged());
        assertEquals(EnumSet.of(Setting.FATIGUE), delivery.pending());
        Result result = delivery.finalResult("ignored when something was acknowledged");
        assertEquals(Outcome.REQUEST_SENT, result.outcome);
        assertEquals("partial: monitoring=ok, fatigue=failed, attention=ok", result.detail);

        // A single acknowledged transaction is still a (partial) request_sent, never failed.
        Delivery one = new Delivery();
        one.record(Setting.FATIGUE, true);
        assertEquals(Outcome.REQUEST_SENT, one.finalResult("x").outcome);
        assertEquals("partial: monitoring=failed, fatigue=ok, attention=failed",
                one.finalResult("x").detail);
    }

    @Test
    public void attemptDetailSaysNoneAcknowledgedUntilSomethingIsAcknowledged() {
        // Per-attempt detail of a not-fully-acknowledged attempt (requestOff's FAILED result, the
        // applier's pending detail and, after the last attempt, its failed detail).
        Delivery delivery = new Delivery();
        assertEquals("none acknowledged: monitoring=failed, fatigue=failed, attention=failed",
                delivery.attemptDetail(null));
        for (Setting s : Setting.values()) delivery.record(s, false);
        assertEquals("none acknowledged: monitoring=failed, fatigue=failed, attention=failed"
                        + "; monitoring: RemoteException dead",
                delivery.attemptDetail("monitoring: RemoteException dead"));
        assertFalse(delivery.attemptDetail("x").startsWith("partial:"));
        // Once anything is acknowledged (cumulatively, even from an earlier attempt) the label is
        // "partial:"; an empty error adds nothing.
        delivery.record(Setting.ATTENTION, true);
        assertEquals("partial: monitoring=failed, fatigue=failed, attention=ok; fatigue: SecurityException",
                delivery.attemptDetail("fatigue: SecurityException"));
        assertEquals("partial: monitoring=failed, fatigue=failed, attention=ok",
                delivery.attemptDetail(""));
        // The final state is unchanged by the label: nothing acknowledged is still failed.
        assertEquals(Outcome.FAILED, new Delivery().finalResult("x").outcome);
    }

    @Test
    public void allAcknowledgedIsRequestSentAndNeverAnythingStronger() {
        Delivery delivery = new Delivery();
        for (Setting s : Setting.values()) delivery.record(s, true);
        assertTrue(delivery.allAcknowledged());
        assertTrue(delivery.pending().isEmpty());
        Result result = delivery.finalResult("x");
        assertEquals(Outcome.REQUEST_SENT, result.outcome);
        assertEquals("monitoring=ok, fatigue=ok, attention=ok", result.detail);
        // The DMS route has no readback: the outcome vocabulary has no verified/applied at all.
        for (Outcome o : Outcome.values()) {
            assertFalse(o.name(), o.name().toLowerCase().contains("verif"));
            assertFalse(o.name(), o.name().toLowerCase().contains("applied"));
        }
    }

    @Test
    public void bestOutcomePerTransactionIsKeptAcrossAttempts() {
        Delivery delivery = new Delivery();
        // Attempt 1: monitoring acknowledged, the other two failed.
        delivery.record(Setting.MONITORING, true);
        delivery.record(Setting.FATIGUE, false);
        delivery.record(Setting.ATTENTION, false);
        assertEquals(EnumSet.of(Setting.FATIGUE, Setting.ATTENTION), delivery.pending());
        // Attempt 2 retries only the pending ones (idempotent writeInt(0)); attention succeeds,
        // fatigue fails again. An acknowledged setting can never be demoted by a later failure.
        delivery.record(Setting.ATTENTION, true);
        delivery.record(Setting.FATIGUE, false);
        delivery.record(Setting.MONITORING, false);   // a stray failed retry must not demote it
        assertTrue(delivery.isAcknowledged(Setting.MONITORING));
        assertTrue(delivery.isAcknowledged(Setting.ATTENTION));
        assertFalse(delivery.isAcknowledged(Setting.FATIGUE));
        assertEquals(EnumSet.of(Setting.FATIGUE), delivery.pending());
        assertEquals("partial: monitoring=ok, fatigue=failed, attention=ok",
                delivery.finalResult("x").detail);
        // Attempt 3 completes the set.
        delivery.record(Setting.FATIGUE, true);
        assertTrue(delivery.allAcknowledged());
        assertEquals(Outcome.REQUEST_SENT, delivery.finalResult("x").outcome);
        assertEquals("monitoring=ok, fatigue=ok, attention=ok", delivery.finalResult("x").detail);
        // Null settings are ignored, not recorded.
        delivery.record(null, false);
        assertTrue(delivery.allAcknowledged());
    }

    @Test
    public void resultDetailIsCappedAndStringified() {
        StringBuilder longDetail = new StringBuilder();
        for (int i = 0; i < 200; i++) longDetail.append('x');
        Result capped = new Result(Outcome.FAILED, longDetail.toString());
        assertEquals(120, capped.detail.length());
        assertEquals("FAILED: " + capped.detail, capped.toString());
        assertEquals("", new Result(Outcome.TIMEOUT, null).detail);
        assertEquals("TIMEOUT", new Result(Outcome.TIMEOUT, null).toString());
    }
}

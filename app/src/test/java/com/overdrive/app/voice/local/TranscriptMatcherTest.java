package com.overdrive.app.voice.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Pins what the English transcript matcher got right before the clause-aware rewrite (so it cannot
 * regress) plus the rewrite's acceptance cases: seat defaults, rear-seat refusals, spoken order,
 * clause-scoped negation and corrections.
 */
public class TranscriptMatcherTest {

    private static final TranscriptMatcher MATCHER = new TranscriptMatcher();

    // ── Seats: driver is the default, "passenger" is the only way to seat 2 ──────────────

    @Test
    public void passengerSeatHeatingOnIsPassengerHigh() {
        assertSeat(first("turn on the passenger seat heating"), "seat.heat", "passenger", 2);
    }

    @Test
    public void mySeatHeatingIsDriver() {
        assertSeat(first("turn on my seat heating"), "seat.heat", "driver", 2);
        assertSeat(first("heat my seat"), "seat.heat", "driver", 2);
        assertSeat(first("seat heating off"), "seat.heat", "driver", 0);
    }

    // ── Climate power: every AC spelling ──────────────────────────────────────────────

    @Test
    public void acSpellingsAllTurnTheClimateOn() {
        for (String t : Arrays.asList("turn on the AC", "a/c on", "turn on the a c", "AC on")) {
            assertPower(first(t), true);
        }
        assertPower(first("turn off the climate"), false);
    }

    @Test
    public void airConditionerIsAFuzzyMatchForAirConditioning() {
        assertPower(first("turn on the air conditioner"), true);
    }

    // ── Numbers and degrees ─────────────────────────────────────────────────────────────

    @Test
    public void spokenAndNumericTemperaturesSetTheDial() {
        LocalCommandSpotter.Match m = first("twenty two degrees");
        assertEquals("climate.set_temp", m.intent);
        assertEquals(22, m.slots.optInt("celsius"));

        TranscriptMatcher.Result r = MATCHER.match("set the temperature to 22");
        assertNotNull(r);
        assertEquals("climate.set_temp", r.matches.get(0).intent);
        assertEquals(22, r.matches.get(0).slots.optInt("celsius"));
        assertEquals(1, r.extraWords);
    }

    @Test
    public void fanLevels() {
        LocalCommandSpotter.Match three = first("fan speed three");
        assertEquals("climate.fan", three.intent);
        assertEquals(3, three.slots.optInt("level"));
        LocalCommandSpotter.Match max = first("maximum fan");
        assertEquals("climate.fan", max.intent);
        assertEquals(7, max.slots.optInt("level"));
    }

    // ── Negation ────────────────────────────────────────────────────────────────────────

    @Test
    public void negatedCommandsMatchNothing() {
        assertNull(MATCHER.match("do not turn on the AC"));
        assertNull(MATCHER.match("please don't turn on the ac"));
        assertNull(MATCHER.match("never turn on the ac"));
    }

    @Test
    public void dontForgetIsPositive() {
        assertPower(first("don't forget to turn on the AC"), true);
    }

    @Test
    public void cancelBypassesNegation() {
        assertEquals(CommandCatalog.INTENT_CANCEL, first("no cancel that").intent);
        assertEquals(CommandCatalog.INTENT_CANCEL, first("don't cancel").intent);
    }

    @Test
    public void negatedClauseIsDroppedAndTheOtherKept() {
        TranscriptMatcher.Result r = MATCHER.match("don't turn on the AC, turn on passenger seat heating");
        assertNotNull(r);
        assertEquals(1, r.matches.size());
        assertSeat(r.matches.get(0), "seat.heat", "passenger", 2);
    }

    // ── Ambiguity and noise ─────────────────────────────────────────────────────────────

    @Test
    public void frontRearDefrosterTieMatchesNothing() {
        assertNull(MATCHER.match("turn on the defroster"));
    }

    @Test
    public void seatHeaterForThePassengerIsTooFarFromAnyPhrase() {
        assertNull(MATCHER.match("seat heater for the passenger"));
    }

    @Test
    public void nonsenseAndEmptyMatchNothing() {
        assertNull(MATCHER.match("hello there"));
        assertNull(MATCHER.match(""));
        assertNull(MATCHER.match(null));
    }

    // ── normalize() ─────────────────────────────────────────────────────────────────────

    @Test
    public void normalizeFoldsAcNumbersAndDegrees() {
        assertTrue(TranscriptMatcher.normalize("turn on the A.C. now").contains("ac"));
        assertEquals(List.of("21"), TranscriptMatcher.normalize("twenty one"));
        assertEquals(Arrays.asList("18", "degrees"), TranscriptMatcher.normalize("18°C"));
    }

    // ── Group-E acceptance ──────────────────────────────────────────────────────────────

    @Test
    public void bareSeatHeatingIsDriverNotPassenger() {
        assertSeat(first("turn on the seat heating"), "seat.heat", "driver", 2);
        assertSeat(first("turn off the seat heating"), "seat.heat", "driver", 0);
    }

    @Test
    public void seatHeaterForThePassengerRedirectsToPassenger() {
        assertSeat(first("turn on the seat heater for the passenger"), "seat.heat", "passenger", 2);
    }

    @Test
    public void rearSeatHeatingIsARefusalMatchNeverAFrontSeat() {
        for (String t : Arrays.asList("turn on the rear seat heating", "turn on the back seat heating")) {
            LocalCommandSpotter.Match m = first(t);
            assertEquals(t, "seat.heat", m.intent);
            assertEquals(t, "rear", m.slots.optString("seat"));
        }
    }

    @Test
    public void adverbialBackOnIsNotARearSeat() {
        // "back" names a seat only right before the seat noun; "back on" re-enables a function.
        // Neither phrasing is in the catalog, so they stay not-understood rather than refused.
        for (String t : Arrays.asList("turn the seat heating back on",
                "put the passenger seat heating back on")) {
            TranscriptMatcher.Result r = MATCHER.match(t);
            if (r == null) continue;
            for (LocalCommandSpotter.Match m : r.matches) {
                assertFalse(t + " -> " + m, "rear".equals(m.slots.optString("seat")));
                assertFalse(t + " -> " + m, TranscriptMatcher.UNSUPPORTED_SEAT_PHRASE.equals(m.phrase));
            }
        }
        // The side word right before the seat noun is still a rear seat.
        LocalCommandSpotter.Match rear = first("turn the back seat heating on");
        assertEquals("seat.heat", rear.intent);
        assertEquals("rear", rear.slots.optString("seat"));
    }

    @Test
    public void twoClausesComeBackInSpokenOrder() {
        TranscriptMatcher.Result r = MATCHER.match("turn off the AC and turn on the air conditioning");
        assertNotNull(r);
        assertEquals(2, r.matches.size());
        assertPower(r.matches.get(0), false);
        assertPower(r.matches.get(1), true);
    }

    @Test
    public void windowsThenAcInSpokenOrder() {
        TranscriptMatcher.Result r = MATCHER.match("close the windows and turn on the ac");
        assertNotNull(r);
        assertEquals(2, r.matches.size());
        assertEquals("windows.close_all", r.matches.get(0).intent);
        assertPower(r.matches.get(1), true);
    }

    @Test
    public void leadingCorrectionMarkerIsNotANegation() {
        TranscriptMatcher.Result r = MATCHER.match("no, actually, turn on the air conditioning");
        assertNotNull(r);
        assertEquals(1, r.matches.size());
        assertPower(r.matches.get(0), true);
    }

    @Test
    public void correctionReplacesThePreviousClauseAndResolvesIt() {
        TranscriptMatcher.Result r = MATCHER.match("turn on the AC, actually no, turn it off");
        assertNotNull(r);
        assertEquals(1, r.matches.size());
        assertPower(r.matches.get(0), false);
    }

    @Test
    public void negationScopeIsTheWholeClause() {
        assertNull(MATCHER.match("I do not want you to turn on the AC"));
    }

    @Test
    public void negatedFirstClauseKeepsOnlyTheSecond() {
        TranscriptMatcher.Result r = MATCHER.match("don't turn on the AC, turn on passenger seat heating");
        assertNotNull(r);
        assertEquals(1, r.matches.size());
        assertSeat(r.matches.get(0), "seat.heat", "passenger", 2);
    }

    // ── Wake-word tail guard: match(text, afterWakeWord) ────────────────────────────────

    @Test
    public void leadingTailTokenIsForgivenAfterAWakeWord() {
        TranscriptMatcher.Result r = MATCHER.match("life switch off the ac", true);
        assertNotNull(r);
        assertEquals(1, r.matches.size());
        assertPower(r.matches.get(0), false);
        assertEquals(0, r.extraWords);
        assertEquals(1, r.wakeTail);
    }

    @Test
    public void leadingTailTokenStillCountsWithoutAWakeWord() {
        TranscriptMatcher.Result r = MATCHER.match("life switch off the ac", false);
        assertNotNull(r);
        assertEquals(1, r.matches.size());
        assertPower(r.matches.get(0), false);
        assertEquals(1, r.extraWords);
        assertEquals(0, r.wakeTail);
    }

    @Test
    public void protectedWordsAreNeverATail() {
        TranscriptMatcher.Result r = MATCHER.match("right turn on the ac", true);
        assertNotNull(r);
        assertPower(r.matches.get(0), true);
        assertEquals(1, r.extraWords);
        assertEquals(0, r.wakeTail);
    }

    @Test
    public void rightSeatStaysAnUnsupportedSeatRefusal() {
        TranscriptMatcher.Result r = MATCHER.match("right seat heating on", true);
        assertNotNull(r);
        LocalCommandSpotter.Match m = r.matches.get(0);
        assertEquals(TranscriptMatcher.UNSUPPORTED_SEAT_PHRASE, m.phrase);
        assertEquals("right", m.slots.optString("seat"));
        assertEquals(0, r.wakeTail);
    }

    @Test
    public void fillersAreNeverATail() {
        TranscriptMatcher.Result r = MATCHER.match("the ac on", true);
        assertNotNull(r);
        assertPower(r.matches.get(0), true);
        assertEquals(0, r.extraWords);
        assertEquals(0, r.wakeTail);
    }

    @Test
    public void tailGuardNeedsAtLeastThreeWords() {
        assertNull(MATCHER.match("life ac", true));
        TranscriptMatcher.Result r = MATCHER.match("life ac on", true);
        assertNotNull(r);
        assertPower(r.matches.get(0), true);
        assertEquals(0, r.extraWords);
        assertEquals(1, r.wakeTail);
    }

    @Test
    public void questionsKeepTheirExtraWordsAfterAWakeWord() {
        TranscriptMatcher.Result r = MATCHER.match("tell me whether i should turn on the ac now", true);
        assertNotNull(r);
        assertPower(r.matches.get(0), true);
        assertEquals(3, r.extraWords);
        assertEquals(0, r.wakeTail);
    }

    @Test
    public void onlyOneLeadingTailTokenIsDropped() {
        TranscriptMatcher.Result r = MATCHER.match("why did you turn on the ac", true);
        assertNotNull(r);
        assertPower(r.matches.get(0), true);
        assertEquals(1, r.extraWords);
        assertEquals(1, r.wakeTail);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────

    private static LocalCommandSpotter.Match first(String transcript) {
        TranscriptMatcher.Result r = MATCHER.match(transcript);
        assertNotNull("no match for \"" + transcript + "\"", r);
        return r.matches.get(0);
    }

    private static void assertSeat(LocalCommandSpotter.Match m, String intent, String seat, int level) {
        assertEquals(m.toString(), intent, m.intent);
        assertEquals(m.toString(), seat, m.slots.optString("seat"));
        assertEquals(m.toString(), level, m.slots.optInt("level", -1));
    }

    private static void assertPower(LocalCommandSpotter.Match m, boolean on) {
        assertEquals(m.toString(), "climate.power", m.intent);
        assertTrue(m.toString(), m.slots.has("on"));
        assertEquals(m.toString(), on, m.slots.optBoolean("on", !on));
    }
}

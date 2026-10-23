package com.overdrive.app.safekeep;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Monthly mobile-data periods that reset on a user-chosen day (1–28). */
final class Budgets {

    private Budgets() {}

    static String periodKey(int resetDay, long now) {
        Calendar c = periodStart(resetDay, now);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    static String nextReset(int resetDay, long now) {
        Calendar c = periodStart(resetDay, now);
        c.add(Calendar.MONTH, 1);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    private static Calendar periodStart(int resetDay, long now) {
        int day = Math.max(1, Math.min(28, resetDay));
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        if (c.get(Calendar.DAY_OF_MONTH) < day) c.add(Calendar.MONTH, -1);
        c.set(Calendar.DAY_OF_MONTH, day);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }
}

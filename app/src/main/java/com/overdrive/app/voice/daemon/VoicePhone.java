package com.overdrive.app.voice.daemon;

import com.overdrive.app.logging.DaemonLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Voice calls in the daemon: a spoken name is looked up in the phone book the head unit synced
 * over Bluetooth (BYD's {@code com.android.bluetooth.contactprovider}, else the platform
 * contacts) and dialled through Telecom; a dialler that refuses the call intent gets the number
 * pre-filled instead. Contact names never leave the car except the one being called, or the few
 * that tie for a name the driver must choose between.
 */
final class VoicePhone {

    private static final DaemonLogger logger = DaemonLogger.getInstance("VoicePhone");

    static final String BYD_CONTACTS = "content://com.android.bluetooth.contactprovider";
    static final String PLATFORM_PHONES = "content://com.android.contacts/data/phones";
    private static final int MAX_CANDIDATES = 3;
    private static final double MIN_SCORE = 0.75;

    static final class Contact {
        final String name;
        final String number;

        Contact(String name, String number) {
            this.name = name;
            this.number = number;
        }
    }

    private VoicePhone() {
    }

    // ---- Pure (JVM-testable) ----

    /** Digits with an optional leading +, or null; spoken separators are dropped. */
    static String dialable(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') out.append(c);
            else if (c == '+' && out.length() == 0) out.append(c);
            else if (c == '*' || c == '#') out.append(c);
        }
        int digits = out.length() - (out.length() > 0 && out.charAt(0) == '+' ? 1 : 0);
        return digits >= 3 && digits <= 18 ? out.toString() : null;
    }

    static String normalize(String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /** 1 exact, 0.9 a whole leading word, 0.85 any whole word, else edit-distance similarity. */
    static double score(String spoken, String contact) {
        String a = normalize(spoken);
        String b = normalize(contact);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        if (b.startsWith(a + " ")) return 0.9;
        if ((" " + b + " ").contains(" " + a + " ")) return 0.85;
        int distance = levenshtein(a, b);
        return 1.0 - (double) distance / Math.max(a.length(), b.length());
    }

    /** The best-scoring distinct names at or above {@link #MIN_SCORE}, best first (ties kept). */
    static List<Contact> best(String spoken, List<Contact> book) {
        List<Contact> out = new ArrayList<>();
        double top = MIN_SCORE;
        for (Contact c : book) {
            double s = score(spoken, c.name);
            if (s > top + 1e-9) {
                top = s;
                out.clear();
                out.add(c);
            } else if (Math.abs(s - top) <= 1e-9 && s >= MIN_SCORE && !hasName(out, c.name)) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean hasName(List<Contact> list, String name) {
        for (Contact c : list) if (normalize(c.name).equals(normalize(name))) return true;
        return false;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // ---- Daemon side ----

    static VoiceNavigator.Result call(String contactName, String number) {
        String direct = dialable(number);
        String who = direct;
        if (direct == null) {
            String spoken = contactName == null ? "" : contactName.trim();
            if (spoken.isEmpty()) return new VoiceNavigator.Result(VoiceIntentExecutor.UNSUPPORTED, "Who should I call?");
            List<Contact> book = phoneBook();
            if (book.isEmpty()) {
                return new VoiceNavigator.Result(VoiceIntentExecutor.FAILED,
                        "I can't see the phone's contacts. Is the phone connected over Bluetooth?");
            }
            List<Contact> matches = best(spoken, book);
            if (matches.isEmpty()) {
                return new VoiceNavigator.Result(VoiceIntentExecutor.UNSUPPORTED, "I couldn't find " + spoken + " in your contacts.");
            }
            if (matches.size() > 1) {
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < matches.size() && i < MAX_CANDIDATES; i++) {
                    if (i > 0) names.append(i == Math.min(matches.size(), MAX_CANDIDATES) - 1 ? " or " : ", ");
                    names.append(matches.get(i).name);
                }
                return new VoiceNavigator.Result(VoiceIntentExecutor.UNSUPPORTED, "Did you mean " + names + "?");
            }
            direct = dialable(matches.get(0).number);
            who = matches.get(0).name;
            if (direct == null) {
                return new VoiceNavigator.Result(VoiceIntentExecutor.FAILED, who + " has no number I can dial.");
            }
        }
        String call = VoiceNavigator.runShell(
                "am start -a android.intent.action.CALL -f 0x10000000 -d 'tel:" + direct + "'");
        boolean placed = call != null && !call.contains("Error") && !call.contains("Exception");
        logger.info("Voice call: placed=" + placed);
        if (placed) return new VoiceNavigator.Result(VoiceIntentExecutor.DONE, "Calling " + who + ".");
        String dial = VoiceNavigator.runShell(
                "am start -a android.intent.action.DIAL -f 0x10000000 -d 'tel:" + direct + "'");
        if (dial != null && !dial.contains("Error") && !dial.contains("Exception")) {
            return new VoiceNavigator.Result(VoiceIntentExecutor.DONE, "Opened the dialler for " + who + "; tap call.");
        }
        return new VoiceNavigator.Result(VoiceIntentExecutor.FAILED, "The call could not be started.");
    }

    /** BYD's Bluetooth phone book (name, number), else the platform phone numbers. */
    static List<Contact> phoneBook() {
        List<Contact> out = query(BYD_CONTACTS, "name", "number");
        if (out.isEmpty()) out = query(PLATFORM_PHONES, "display_name", "data1");
        return out;
    }

    private static List<Contact> query(String uri, String nameColumn, String numberColumn) {
        List<Contact> out = new ArrayList<>();
        android.database.Cursor c = null;
        try {
            android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (ctx == null) return out;
            c = ctx.getContentResolver().query(android.net.Uri.parse(uri), null, null, null, null);
            if (c == null) return out;
            int name = c.getColumnIndex(nameColumn);
            int number = c.getColumnIndex(numberColumn);
            if (name < 0 || number < 0) return out;
            while (c.moveToNext() && out.size() < 5000) {
                String n = c.getString(name);
                String num = c.getString(number);
                if (n != null && num != null) out.add(new Contact(n, num));
            }
        } catch (Throwable t) {
            logger.warn("Phone book " + uri + " unreadable: " + t.getClass().getSimpleName());
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }
}

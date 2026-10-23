package com.overdrive.app.voice.local;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fixed spoken phrases per language and the intent + slots each one means. Pure Java (no Android,
 * no org.json) so tools/voice can dump it on a desktop JVM to build the tokenized keyword files.
 *
 * <p>Matches fire as soon as a phrase ends, so no phrase may be a token prefix of another phrase
 * with a different meaning; tools/voice/gen_kws_keywords.py rejects such pairs.
 */
public final class CommandCatalog {

    public static final String EN = "en";
    public static final String ZH_CN = "zh-CN";
    public static final String ZH_TW = "zh-TW";

    public static final String INTENT_CANCEL = "assistant.cancel";

    public static final class Entry {
        public final String language;
        public final String phrase;
        public final String intent;
        /** Alternating slot name / value; values are Boolean, Integer or String. */
        public final Object[] slots;
        /** The keyword file's {@code @tag}: whitespace-free, unique per language. */
        public final String tag;

        Entry(String language, String phrase, String intent, Object[] slots) {
            this.language = language;
            this.phrase = phrase;
            this.intent = intent;
            this.slots = slots;
            this.tag = tagOf(phrase);
        }

        public boolean isCancel() {
            return INTENT_CANCEL.equals(intent);
        }

        /** Same intent and slots, i.e. a prefix relation between the two is harmless. */
        public boolean sameMeaning(Entry other) {
            return other != null && intent.equals(other.intent) && Arrays.equals(slots, other.slots);
        }
    }

    private static final class Language {
        final List<Entry> entries;
        final Map<String, Entry> byTag;

        Language(List<Entry> entries) {
            this.entries = Collections.unmodifiableList(entries);
            Map<String, Entry> map = new HashMap<>();
            for (Entry e : entries) {
                if (map.put(e.tag, e) != null) {
                    throw new IllegalStateException("Duplicate phrase: " + e.language + " " + e.phrase);
                }
            }
            this.byTag = map;
        }
    }

    private static volatile Map<String, Language> languages;

    private CommandCatalog() {}

    public static List<String> languages() {
        return Collections.unmodifiableList(Arrays.asList(EN, ZH_CN, ZH_TW));
    }

    public static List<Entry> entries(String language) {
        Language lang = all().get(language);
        return lang != null ? lang.entries : Collections.<Entry>emptyList();
    }

    public static Entry find(String language, String tag) {
        Language lang = all().get(language);
        return lang != null && tag != null ? lang.byTag.get(tag) : null;
    }

    /** Lower-case, whitespace collapsed to '_' (Chinese phrases have none). */
    public static String tagOf(String phrase) {
        return phrase.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    private static Map<String, Language> all() {
        Map<String, Language> local = languages;
        if (local != null) return local;
        synchronized (CommandCatalog.class) {
            if (languages == null) {
                Map<String, Language> built = new LinkedHashMap<>();
                built.put(EN, new Language(english()));
                built.put(ZH_CN, new Language(chineseSimplified()));
                built.put(ZH_TW, new Language(chineseTraditional()));
                languages = Collections.unmodifiableMap(built);
            }
            return languages;
        }
    }

    // ── Builders ────────────────────────────────────────────────────────

    private static final class Builder {
        final String language;
        final List<Entry> out = new ArrayList<>();

        Builder(String language) {
            this.language = language;
        }

        void add(String intent, Object[] slots, String... phrases) {
            for (String p : phrases) out.add(new Entry(language, p, intent, slots));
        }
    }

    private static Object[] none() {
        return new Object[0];
    }

    private static Object[] on(boolean on) {
        return new Object[] {"on", on};
    }

    private static Object[] open(boolean open) {
        return new Object[] {"open", open};
    }

    private static Object[] celsius(int c) {
        return new Object[] {"celsius", c};
    }

    private static Object[] delta(int d) {
        return new Object[] {"delta", d};
    }

    private static Object[] level(int l) {
        return new Object[] {"level", l};
    }

    private static Object[] goal(String g) {
        return new Object[] {"goal", g};
    }

    private static Object[] seat(String seat, int level) {
        return new Object[] {"seat", seat, "level", level};
    }

    private static final String DRIVER = "driver";
    private static final String PASSENGER = "passenger";

    private static final String[] EN_NUMBERS = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty"
    };

    private static String english(int n) {
        if (n <= 20) return EN_NUMBERS[n];
        if (n < 30) return "twenty " + EN_NUMBERS[n - 20];
        return "thirty";
    }

    private static final String ZH_DIGITS = "零一二三四五六七八九";

    private static String chinese(int n) {
        if (n < 10) return String.valueOf(ZH_DIGITS.charAt(n));
        if (n == 10) return "十";
        if (n < 20) return "十" + ZH_DIGITS.charAt(n - 10);
        int tens = n / 10;
        int ones = n % 10;
        return ZH_DIGITS.charAt(tens) + "十" + (ones == 0 ? "" : String.valueOf(ZH_DIGITS.charAt(ones)));
    }

    private static final int SET_TEMP_MIN_C = 16;
    private static final int SET_TEMP_MAX_C = 30;
    private static final int FAN_MIN = 1;
    private static final int FAN_MAX = 7;
    /** Volume steps per "louder" / "quieter" (the head unit's media scale runs to about 40). */
    private static final int VOLUME_STEP = 3;

    // ── English ─────────────────────────────────────────────────────────

    private static List<Entry> english() {
        Builder b = new Builder(EN);
        b.add("climate.power", on(true),
                "turn on the AC", "turn the AC on", "switch on the AC", "AC on", "start the AC",
                "turn on the air conditioning", "turn on air conditioning", "air conditioning on",
                "start the air conditioning", "turn on the climate", "climate on");
        b.add("climate.power", on(false),
                "turn off the AC", "turn the AC off", "switch off the AC", "AC off", "stop the AC",
                "turn off the air conditioning", "turn off air conditioning",
                "air conditioning off", "stop the air conditioning", "turn off the climate",
                "climate off");

        for (int c = SET_TEMP_MIN_C; c <= SET_TEMP_MAX_C; c++) {
            String w = english(c);
            b.add("climate.set_temp", celsius(c), w + " degrees");
            // "twenty" alone is a prefix of twenty-one..twenty-nine; it needs "degrees".
            if (c != 20) {
                b.add("climate.set_temp", celsius(c),
                        "temperature to " + w, "temperature " + w, "AC to " + w);
            }
        }

        b.add("climate.step_temp", delta(1),
                "warmer", "a bit warmer", "make it warmer", "temperature up",
                "increase the temperature", "raise the temperature", "turn up the temperature",
                "turn the temperature up", "turn up the heat");
        b.add("climate.step_temp", delta(-1),
                "cooler", "a bit cooler", "make it cooler", "temperature down",
                "decrease the temperature", "lower the temperature", "turn down the temperature",
                "turn the temperature down");
        b.add("climate.step_temp", delta(2), "too cold", "it's too cold", "I'm cold", "I'm freezing");
        b.add("climate.step_temp", delta(-2), "too hot", "it's too hot", "I'm hot");

        for (int n = FAN_MIN; n <= FAN_MAX; n++) {
            String w = english(n);
            b.add("climate.fan", level(n), "fan speed " + w, "fan level " + w, "fan to " + w);
        }
        b.add("climate.fan", level(FAN_MAX), "maximum fan", "max fan", "fan to max", "fan to maximum");
        b.add("climate.fan", level(FAN_MIN), "minimum fan", "fan to minimum");
        b.add("climate.fan", delta(1),
                "fan up", "more fan", "increase fan speed", "fan speed up", "turn up the fan",
                "turn the fan up", "increase the fan");
        b.add("climate.fan", delta(-1),
                "fan down", "less fan", "decrease fan speed", "fan speed down",
                "turn down the fan", "turn the fan down", "decrease the fan");

        b.add("climate.recirculation", on(true),
                "recirculation on", "turn on recirculation", "recirculate the air",
                "recirculate air");
        b.add("climate.recirculation", on(false),
                "recirculation off", "turn off recirculation", "fresh air mode",
                "outside air mode");

        // No bare "maximum cooling": it is a prefix of "maximum cooling off".
        b.add("climate.max_cool", on(true),
                "max cooling on", "turn on max cooling", "maximum cooling on",
                "turn on maximum cooling");
        b.add("climate.max_cool", on(false),
                "max cooling off", "turn off max cooling", "maximum cooling off",
                "turn off maximum cooling");

        b.add("climate.auto", on(true),
                "auto mode on", "turn on auto mode", "automatic climate on");
        b.add("climate.auto", on(false),
                "auto mode off", "turn off auto mode", "automatic climate off");

        b.add("climate.defrost_front", on(true),
                "front defrost on", "turn on front defrost", "defrost the windshield",
                "windshield defrost on", "turn on the front defroster");
        b.add("climate.defrost_front", on(false),
                "front defrost off", "turn off front defrost", "windshield defrost off",
                "turn off the front defroster");
        b.add("climate.defrost_rear", on(true),
                "rear defrost on", "turn on rear defrost", "defrost the rear window",
                "heat the rear window", "turn on the rear defroster");
        b.add("climate.defrost_rear", on(false),
                "rear defrost off", "turn off rear defrost", "turn off the rear defroster");

        b.add("climate.goal", goal("cool"),
                "cool the cabin", "cool down the cabin", "cool down the car", "cool the car");
        b.add("climate.goal", goal("warm"),
                "warm up the car", "warm up the cabin", "warm the cabin", "warm the car",
                "heat up the car", "heat up the cabin");
        b.add("climate.goal", goal("defog"),
                "defog the windshield", "defog the windows", "demist the windshield",
                "demist the windows", "clear the fog");
        b.add("climate.goal", goal("fresh"),
                "get some fresh air", "let some fresh air in", "it's stuffy in here",
                "air out the car");

        englishSeat(b, "seat.heat", "seat heating", "heat my seat", "heat the passenger seat",
                new String[] {"turn on the seat heater", "seat heater on"},
                new String[] {"turn off the seat heater", "seat heater off"});
        englishSeat(b, "seat.vent", "seat ventilation", "ventilate my seat",
                "ventilate the passenger seat",
                new String[] {"cool my seat", "seat cooling on", "turn on seat cooling"},
                new String[] {"seat cooling off", "turn off seat cooling"});

        b.add("steering.heat", on(true),
                "heat the steering wheel", "warm the steering wheel", "steering wheel heating on",
                "turn on steering wheel heating", "turn on the steering wheel heating",
                "heated steering wheel on", "turn on the heated steering wheel");
        b.add("steering.heat", on(false),
                "steering wheel heating off", "turn off steering wheel heating",
                "turn off the steering wheel heating", "heated steering wheel off",
                "turn off the heated steering wheel");

        b.add("sunshade", open(true),
                "open the sunshade", "open sunshade", "open the shade", "sunshade open");
        b.add("sunshade", open(false),
                "close the sunshade", "close sunshade", "close the shade", "sunshade close");

        b.add("windows.close_all", none(),
                "close all windows", "close all the windows", "close the windows",
                "close the window", "close my windows", "windows up", "roll up the windows",
                "put the windows up");
        b.add("windows.vent_all", none(),
                "vent the windows", "vent all windows", "crack the windows",
                "crack the windows open", "open the windows a bit", "open the windows a little");

        b.add("ambient.power", on(true),
                "ambient lights on", "turn on the ambient lights", "turn on ambient lights",
                "ambient lighting on", "turn on ambient lighting", "turn on the ambient lighting",
                "mood lights on", "mood lighting on");
        b.add("ambient.power", on(false),
                "ambient lights off", "turn off the ambient lights", "turn off ambient lights",
                "ambient lighting off", "turn off ambient lighting",
                "turn off the ambient lighting", "mood lights off", "mood lighting off");

        b.add("recording.save_clip", none(),
                "save clip", "save a clip", "save the clip", "save that clip", "save the video",
                "save that video", "save the recording", "clip that", "record that");

        b.add("media.pause", none(),
                "pause the music", "pause music", "stop the music", "stop music", "pause the song",
                "pause playback");
        b.add("media.play", none(),
                "play music", "play the music", "resume the music", "resume music", "resume playback",
                "continue the music");
        b.add("media.next", none(),
                "next song", "next track", "skip this song", "skip the song", "skip song",
                "play the next song");
        b.add("media.previous", none(),
                "previous song", "previous track", "last song", "play the previous song",
                "go back a song");
        b.add("volume.step", delta(VOLUME_STEP),
                "volume up", "turn up the volume", "turn the volume up", "louder",
                "increase the volume", "raise the volume");
        b.add("volume.step", delta(-VOLUME_STEP),
                "volume down", "turn down the volume", "turn the volume down", "quieter",
                "decrease the volume");
        b.add("vehicle.lock", none(), "lock the car", "lock the doors", "lock my car", "lock the vehicle");

        b.add(INTENT_CANCEL, none(), "cancel", "cancel that", "never mind", "nevermind", "forget it");
        return b.out;
    }

    /** Driver is the default seat; "passenger …" forms are the only way to reach seat 2. */
    private static void englishSeat(Builder b, String intent, String noun, String driverOn,
                                    String passengerOn, String[] extraOn, String[] extraOff) {
        b.add(intent, seat(DRIVER, 2), driverOn, "turn on " + noun, noun + " on",
                "turn on my " + noun, "driver " + noun + " on", "turn on driver " + noun,
                noun + " high", "driver " + noun + " high", noun + " level two",
                "driver " + noun + " level two");
        b.add(intent, seat(DRIVER, 2), extraOn);
        b.add(intent, seat(DRIVER, 1), noun + " low", "driver " + noun + " low",
                noun + " level one", "driver " + noun + " level one");
        b.add(intent, seat(DRIVER, 0), noun + " off", "turn off " + noun, "turn off my " + noun,
                "driver " + noun + " off", "turn off driver " + noun);
        b.add(intent, seat(DRIVER, 0), extraOff);
        b.add(intent, seat(PASSENGER, 2), passengerOn, "passenger " + noun + " on",
                "turn on passenger " + noun, "turn on the passenger " + noun,
                "passenger " + noun + " high", "passenger " + noun + " level two");
        b.add(intent, seat(PASSENGER, 1), "passenger " + noun + " low",
                "passenger " + noun + " level one");
        b.add(intent, seat(PASSENGER, 0), "passenger " + noun + " off",
                "turn off passenger " + noun, "turn off the passenger " + noun);
    }

    // ── Chinese (Simplified) ────────────────────────────────────────────

    private static List<Entry> chineseSimplified() {
        Builder b = new Builder(ZH_CN);
        b.add("climate.power", on(true), "打开空调", "开空调", "开启空调", "空调打开", "把空调打开");
        b.add("climate.power", on(false),
                "关闭空调", "关空调", "关掉空调", "空调关闭", "把空调关掉", "把空调关了");

        for (int c = SET_TEMP_MIN_C; c <= SET_TEMP_MAX_C; c++) {
            String n = chinese(c);
            // "度" is mandatory: 二十 is a prefix of 二十一..二十九. Bare 十七度 is a suffix of
            // 二十七度, so 16..19 always carry a lead-in.
            if (c >= 20) b.add("climate.set_temp", celsius(c), n + "度");
            b.add("climate.set_temp", celsius(c), "温度调到" + n + "度", "空调调到" + n + "度",
                    "调到" + n + "度", "温度" + n + "度", "空调" + n + "度");
        }

        b.add("climate.step_temp", delta(1),
                "温度高一点", "温度调高一点", "调高温度", "升高温度", "暖和一点", "温度高一些");
        b.add("climate.step_temp", delta(-1),
                "温度低一点", "温度调低一点", "调低温度", "降低温度", "凉快一点", "温度低一些");
        b.add("climate.step_temp", delta(2), "太冷了", "好冷");
        b.add("climate.step_temp", delta(-2), "太热了", "好热");

        for (int n = FAN_MIN; n <= FAN_MAX; n++) {
            String w = chinese(n);
            b.add("climate.fan", level(n), "风量" + w + "档", "风量调到" + w + "档", "风速" + w + "档");
        }
        b.add("climate.fan", level(FAN_MAX), "风量最大", "最大风量", "风量调到最大");
        b.add("climate.fan", level(FAN_MIN), "风量最小", "最小风量", "风量调到最小");
        b.add("climate.fan", delta(1), "风量大一点", "风大一点", "加大风量", "风量调大");
        b.add("climate.fan", delta(-1), "风量小一点", "风小一点", "减小风量", "风量调小");

        b.add("climate.recirculation", on(true),
                "内循环", "打开内循环", "开启内循环", "切换内循环", "切换到内循环", "关闭外循环");
        b.add("climate.recirculation", on(false),
                "外循环", "打开外循环", "开启外循环", "切换外循环", "切换到外循环", "关闭内循环");

        b.add("climate.max_cool", on(true), "最大制冷", "打开最大制冷", "开启最大制冷");
        b.add("climate.max_cool", on(false), "关闭最大制冷");

        b.add("climate.auto", on(true), "自动空调", "打开自动空调", "开启自动空调", "打开自动模式");
        b.add("climate.auto", on(false), "关闭自动空调", "关闭自动模式");

        b.add("climate.defrost_front", on(true), "前除霜", "打开前除霜", "开启前除霜", "前挡除霜");
        b.add("climate.defrost_front", on(false), "关闭前除霜", "关闭前挡除霜");
        b.add("climate.defrost_rear", on(true), "后除霜", "打开后除霜", "开启后除霜", "后挡除霜");
        b.add("climate.defrost_rear", on(false), "关闭后除霜", "关闭后挡除霜");

        b.add("climate.goal", goal("cool"), "车内降温", "给车降温", "帮我降温", "快速降温");
        b.add("climate.goal", goal("warm"), "车内升温", "给车升温", "暖车", "快速升温");
        b.add("climate.goal", goal("defog"), "除雾", "前挡除雾", "玻璃除雾", "车窗除雾");
        b.add("climate.goal", goal("fresh"), "换换空气", "通通风", "车里好闷", "透透气");

        chineseSeat(b, "seat.heat", "加热", "主驾", "副驾", "档");
        chineseSeat(b, "seat.vent", "通风", "主驾", "副驾", "档");

        b.add("steering.heat", on(true), "方向盘加热", "打开方向盘加热", "开启方向盘加热");
        b.add("steering.heat", on(false), "关闭方向盘加热", "关掉方向盘加热");

        b.add("sunshade", open(true), "打开遮阳帘", "开启遮阳帘", "遮阳帘打开");
        b.add("sunshade", open(false), "关闭遮阳帘", "关上遮阳帘", "遮阳帘关闭");

        b.add("windows.close_all", none(),
                "关闭所有车窗", "关闭车窗", "关窗", "关上车窗", "把车窗关上", "车窗全关");
        b.add("windows.vent_all", none(),
                "车窗透气", "开窗透气", "打开车窗透气", "车窗开条缝", "开一点窗");

        b.add("ambient.power", on(true), "打开氛围灯", "开启氛围灯", "氛围灯打开");
        b.add("ambient.power", on(false), "关闭氛围灯", "关掉氛围灯", "氛围灯关闭");

        b.add("recording.save_clip", none(), "保存视频", "保存录像", "保存这段视频", "保存片段", "记录一下");

        b.add("media.pause", none(), "暂停音乐", "暂停播放", "停止播放");
        b.add("media.play", none(), "播放音乐", "继续播放");
        b.add("media.next", none(), "下一首", "下一曲", "切歌");
        b.add("media.previous", none(), "上一首", "上一曲");
        b.add("volume.step", delta(VOLUME_STEP), "音量调大", "调大音量", "大声一点", "声音大一点");
        b.add("volume.step", delta(-VOLUME_STEP), "音量调小", "调小音量", "小声一点", "声音小一点");
        b.add("vehicle.lock", none(), "锁车", "锁上车门");

        b.add(INTENT_CANCEL, none(), "取消", "算了", "不用了");
        return b.out;
    }

    // ── Chinese (Traditional, Taiwan vocabulary) ────────────────────────

    private static List<Entry> chineseTraditional() {
        Builder b = new Builder(ZH_TW);
        b.add("climate.power", on(true), "打開冷氣", "開冷氣", "開啟冷氣", "打開空調", "開空調");
        b.add("climate.power", on(false), "關閉冷氣", "關冷氣", "關掉冷氣", "關閉空調", "關空調");

        for (int c = SET_TEMP_MIN_C; c <= SET_TEMP_MAX_C; c++) {
            String n = chinese(c);
            if (c >= 20) b.add("climate.set_temp", celsius(c), n + "度");
            b.add("climate.set_temp", celsius(c), "溫度調到" + n + "度", "冷氣調到" + n + "度",
                    "調到" + n + "度", "溫度" + n + "度", "冷氣" + n + "度");
        }

        b.add("climate.step_temp", delta(1), "溫度高一點", "溫度調高一點", "調高溫度", "升高溫度", "暖一點");
        b.add("climate.step_temp", delta(-1), "溫度低一點", "溫度調低一點", "調低溫度", "降低溫度", "涼一點");
        b.add("climate.step_temp", delta(2), "太冷了", "好冷");
        b.add("climate.step_temp", delta(-2), "太熱了", "好熱");

        for (int n = FAN_MIN; n <= FAN_MAX; n++) {
            String w = chinese(n);
            b.add("climate.fan", level(n), "風量" + w + "段", "風量" + w + "檔", "風量調到" + w + "段");
        }
        b.add("climate.fan", level(FAN_MAX), "風量最大", "最大風量", "風量調到最大");
        b.add("climate.fan", level(FAN_MIN), "風量最小", "最小風量", "風量調到最小");
        b.add("climate.fan", delta(1), "風量大一點", "風大一點", "加大風量", "風量調大");
        b.add("climate.fan", delta(-1), "風量小一點", "風小一點", "減小風量", "風量調小");

        b.add("climate.recirculation", on(true), "內循環", "打開內循環", "切換內循環", "關閉外循環");
        b.add("climate.recirculation", on(false), "外循環", "打開外循環", "切換外循環", "關閉內循環");

        b.add("climate.max_cool", on(true), "最大製冷", "打開最大製冷");
        b.add("climate.max_cool", on(false), "關閉最大製冷");

        b.add("climate.auto", on(true), "自動空調", "打開自動空調", "打開自動模式");
        b.add("climate.auto", on(false), "關閉自動空調", "關閉自動模式");

        b.add("climate.defrost_front", on(true), "前除霜", "打開前除霜");
        b.add("climate.defrost_front", on(false), "關閉前除霜");
        b.add("climate.defrost_rear", on(true), "後除霜", "打開後除霜");
        b.add("climate.defrost_rear", on(false), "關閉後除霜");

        b.add("climate.goal", goal("cool"), "車內降溫", "幫我降溫");
        b.add("climate.goal", goal("warm"), "車內升溫", "暖車", "幫我升溫");
        b.add("climate.goal", goal("defog"), "除霧", "前擋除霧", "玻璃除霧", "車窗除霧");
        b.add("climate.goal", goal("fresh"), "換換空氣", "通通風", "車裡好悶");

        chineseSeat(b, "seat.heat", "加熱", "主駕", "副駕", "檔");
        chineseSeat(b, "seat.vent", "通風", "主駕", "副駕", "檔");

        b.add("steering.heat", on(true), "方向盤加熱", "打開方向盤加熱");
        b.add("steering.heat", on(false), "關閉方向盤加熱", "關掉方向盤加熱");

        b.add("sunshade", open(true), "打開遮陽簾", "遮陽簾打開");
        b.add("sunshade", open(false), "關閉遮陽簾", "遮陽簾關閉");

        b.add("windows.close_all", none(), "關閉所有車窗", "關閉車窗", "關窗", "把車窗關上");
        b.add("windows.vent_all", none(), "車窗透氣", "開窗透氣", "打開車窗透氣");

        b.add("ambient.power", on(true), "打開氛圍燈", "氛圍燈打開");
        b.add("ambient.power", on(false), "關閉氛圍燈", "氛圍燈關閉");

        b.add("recording.save_clip", none(), "儲存影片", "保存影片", "儲存錄影", "記錄一下");

        b.add("media.pause", none(), "暫停音樂", "暫停播放", "停止播放");
        b.add("media.play", none(), "播放音樂", "繼續播放");
        b.add("media.next", none(), "下一首", "下一曲");
        b.add("media.previous", none(), "上一首", "上一曲");
        b.add("volume.step", delta(VOLUME_STEP), "音量調大", "調大音量", "大聲一點");
        b.add("volume.step", delta(-VOLUME_STEP), "音量調小", "調小音量", "小聲一點");
        b.add("vehicle.lock", none(), "鎖車", "鎖上車門");

        b.add(INTENT_CANCEL, none(), "取消", "算了", "不用了");
        return b.out;
    }

    /** No bare "座椅加热" form: it would be a prefix of the 一档 phrase. */
    private static void chineseSeat(Builder b, String intent, String noun, String driver,
                                    String passenger, String gear) {
        boolean traditional = "檔".equals(gear);
        String turnOn = traditional ? "打開" : "打开";
        String turnOff = traditional ? "關閉" : "关闭";
        String base = "座椅" + noun;
        b.add(intent, seat(DRIVER, 2), turnOn + base, turnOn + driver + base,
                base + "二" + gear, driver + base + "二" + gear);
        b.add(intent, seat(DRIVER, 1), base + "一" + gear, driver + base + "一" + gear);
        b.add(intent, seat(DRIVER, 0), turnOff + base, turnOff + driver + base);
        b.add(intent, seat(PASSENGER, 2), turnOn + passenger + base, passenger + base + "二" + gear);
        b.add(intent, seat(PASSENGER, 1), passenger + base + "一" + gear);
        b.add(intent, seat(PASSENGER, 0), turnOff + passenger + base);
    }
}

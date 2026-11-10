package com.exteragram.messenger.inline.time;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import kotlin.collections.CollectionsKt;
import kotlin.collections.SetsKt;
import kotlin.jvm.internal.Intrinsics;
import kotlin.text.MatchResult;
import kotlin.text.Regex;
import kotlin.text.StringsKt;
import kotlin.text.Typography;
import org.mvel2.MVEL;
import org.mvel2.asm.signature.SignatureVisitor;

public final class TimeZones {
    public static final TimeZones INSTANCE = new TimeZones();
    private static final Set<String> targetMarkers = SetsKt.setOf(new String[]{"в", "во", "to", "in"});
    private static final Set<String> sourceMarkers = SetsKt.setOf("по");
    private static final Set<String> nowWords = SetsKt.setOf(new String[]{"сейчас", "now"});
    private static final Set<String> meridiems = SetsKt.setOf(new String[]{"am", "pm", "a.m.", "p.m."});
    private static final Regex timePattern = new Regex("^(\\d{1,2})(?:[:.](\\d{2}))?(am|pm|a\\.m\\.|p\\.m\\.)?$");
    private static final Regex offsetPattern = new Regex("^(utc|gmt|msk|мск) ?([+-]) ?(\\d{1,2})(?::?(\\d{2}))?$");
    private static final HashMap<String, String> zones = new HashMap<>();

    private TimeZones() {
    }

    static {
        _init_$add("UTC", "utc");
        _init_$add("GMT", "gmt");
        _init_$add("Europe/Moscow", "msk", "мск", "москва", "москве", "moscow", "питер", "питере", "спб", "петербург", "петербурге", "санкт-петербург", "санкт-петербурге", "st petersburg", "saint petersburg");
        _init_$add("Europe/Kaliningrad", "калининград", "калининграде", "kaliningrad");
        _init_$add("Europe/Samara", "самара", "самаре", "samara");
        _init_$add("Asia/Yekaterinburg", "екб", "екатеринбург", "екатеринбурге", "yekaterinburg");
        _init_$add("Asia/Omsk", "омск", "омске", "omsk");
        _init_$add("Asia/Novosibirsk", "нск", "новосибирск", "новосибирске", "novosibirsk");
        _init_$add("Asia/Krasnoyarsk", "красноярск", "красноярске", "krasnoyarsk");
        _init_$add("Asia/Irkutsk", "иркутск", "иркутске", "irkutsk");
        _init_$add("Asia/Yakutsk", "якутск", "якутске", "yakutsk");
        _init_$add("Asia/Vladivostok", "владивосток", "владивостоке", "vladivostok");
        _init_$add("Asia/Magadan", "магадан", "магадане", "magadan");
        _init_$add("Asia/Kamchatka", "камчатка", "камчатке", "kamchatka");
        _init_$add("Europe/Kiev", "киев", "киеве", "київ", "києві", "kyiv", "kiev");
        _init_$add("Europe/Minsk", "минск", "минске", "minsk");
        _init_$add("Asia/Almaty", "алматы", "almaty", "астана", "астане", "astana");
        _init_$add("Asia/Tashkent", "ташкент", "ташкенте", "tashkent");
        _init_$add("Asia/Bishkek", "бишкек", "бишкеке", "bishkek");
        _init_$add("Asia/Dushanbe", "душанбе", "dushanbe");
        _init_$add("Asia/Tbilisi", "тбилиси", "tbilisi");
        _init_$add("Asia/Yerevan", "ереван", "ереване", "yerevan");
        _init_$add("Asia/Baku", "баку", "baku");
        _init_$add("Europe/Chisinau", "кишинев", "кишиневе", "chisinau");
        _init_$add("Europe/Riga", "рига", "риге", "riga");
        _init_$add("Europe/Vilnius", "вильнюс", "вильнюсе", "vilnius");
        _init_$add("Europe/Tallinn", "таллин", "таллине", "tallinn");
        _init_$add("Europe/Helsinki", "eet", "eest", "хельсинки", "helsinki");
        _init_$add("Europe/Warsaw", "варшава", "варшаве", "warsaw");
        _init_$add("Europe/Prague", "прага", "праге", "prague");
        _init_$add("Europe/Berlin", "cet", "cest", "берлин", "берлине", "berlin");
        _init_$add("Europe/Paris", "париж", "париже", "paris");
        _init_$add("Europe/Rome", "рим", "риме", "rome");
        _init_$add("Europe/Madrid", "мадрид", "мадриде", "madrid", "барселона", "барселоне", "barcelona");
        _init_$add("Europe/Amsterdam", "амстердам", "амстердаме", "amsterdam");
        _init_$add("Europe/London", "bst", "лондон", "лондоне", "london");
        _init_$add("Europe/Lisbon", "wet", "west", "лиссабон", "лиссабоне", "lisbon");
        _init_$add("Europe/Istanbul", "стамбул", "стамбуле", "istanbul", "анталья", "анталье", "antalya");
        _init_$add("Asia/Jerusalem", "тель-авив", "тель-авиве", "tel aviv", "иерусалим", "иерусалиме", "jerusalem");
        _init_$add("Asia/Dubai", "дубай", "дубае", "dubai");
        _init_$add("Africa/Cairo", "каир", "каире", "cairo");
        _init_$add("Asia/Kolkata", "ist", "дели", "delhi", "new delhi", "мумбаи", "mumbai");
        _init_$add("Asia/Bangkok", "бангкок", "бангкоке", "bangkok", "пхукет", "пхукете", "phuket");
        _init_$add("Asia/Makassar", "бали", "bali");
        _init_$add("Asia/Singapore", "sgt", "сингапур", "сингапуре", "singapore");
        _init_$add("Asia/Hong_Kong", "hkt", "гонконг", "гонконге", "hong kong");
        _init_$add("Asia/Shanghai", "пекин", "пекине", "beijing", "шанхай", "шанхае", "shanghai");
        _init_$add("Asia/Seoul", "kst", "сеул", "сеуле", "seoul");
        _init_$add("Asia/Tokyo", "jst", "токио", "tokyo");
        _init_$add("Australia/Perth", "awst", "перт", "перте", "perth");
        _init_$add("Australia/Sydney", "aest", "aedt", "сидней", "сиднее", "sydney");
        _init_$add("Australia/Melbourne", "мельбурн", "мельбурне", "melbourne");
        _init_$add("Pacific/Auckland", "nzst", "nzdt", "окленд", "окленде", "auckland");
        _init_$add("America/New_York", "est", "edt", "нью-йорк", "нью-йорке", "new york", "nyc", "вашингтон", "вашингтоне", "washington", "майами", "miami");
        _init_$add("America/Toronto", "торонто", "toronto");
        _init_$add("America/Chicago", "cst", "cdt", "чикаго", "chicago");
        _init_$add("America/Denver", "mst", "mdt", "денвер", "денвере", "denver");
        _init_$add("America/Los_Angeles", "pst", "pdt", "лос-анджелес", "лос-анджелесе", "los angeles", "сан-франциско", "san francisco", "сиэтл", "сиэтле", "seattle");
        _init_$add("America/Vancouver", "ванкувер", "ванкувере", "vancouver");
        _init_$add("America/Anchorage", "akst", "akdt", "анкоридж", "анкоридже", "anchorage");
        _init_$add("Pacific/Honolulu", "hst", "гонолулу", "honolulu");
        _init_$add("America/Mexico_City", "мехико", "mexico city");
        _init_$add("America/Sao_Paulo", "сан-паулу", "sao paulo");
        _init_$add("America/Argentina/Buenos_Aires", "буэнос-айрес", "буэнос-айресе", "buenos aires");
    }

    public final String valueAt(CharSequence text, int equalsIndex, TimeZoneOptions options) {
        ZoneId zoneIdZone;
        ZoneId zoneIdZone2;
        int iMax = Math.max(0, equalsIndex - 64);
        int i = equalsIndex - 1;
        if (iMax <= i) {
            while (true) {
                if (text.charAt(i) == '\n' || text.charAt(i) == '=') {
                    iMax = i + 1;
                    break;
                }
                if (i == iMax) {
                    break;
                }
                i--;
            }
        }
        List listSplit$default = StringsKt.split((CharSequence) StringsKt.replace(StringsKt.replace(text.subSequence(iMax, equalsIndex).toString().toLowerCase(Locale.ROOT), (char) 1105, (char) 1077, false), (char) 8722, SignatureVisitor.SUPER, false), new char[]{' ', '\t', Typography.nbsp}, false, 0);
        ArrayList arrayList = new ArrayList();
        for (Object obj : listSplit$default) {
            if (((String) obj).length() > 0) {
                arrayList.add(obj);
            }
        }
        int size = arrayList.size();
        int i2 = 3;
        while (true) {
            if (i2 <= 0) {
                zoneIdZone = null;
                break;
            }
            int i3 = size - i2;
            int i4 = i3 - 1;
            if (i4 >= 0 && targetMarkers.contains(arrayList.get(i4)) && (zoneIdZone = zone(arrayList, i3, size)) != null) {
                size -= i2 + 1;
                break;
            }
            i2--;
        }
        int i5 = 3;
        while (true) {
            if (i5 <= 0) {
                zoneIdZone2 = null;
                break;
            }
            int i6 = size - i5;
            if (i6 >= 1 && (zoneIdZone2 = zone(arrayList, i6, size)) != null) {
                if (sourceMarkers.contains(arrayList.get(i6 - 1)) && i6 > 1) {
                    i6--;
                }
                size = i6;
                break;
            }
            i5--;
        }
        if (size < 1) {
            return null;
        }
        Object string = arrayList.get(size - 1);
        Set<String> set = meridiems;
        if (set.contains(string) && size >= 2) {
            Object obj2 = arrayList.get(size - 2);
            StringBuilder sb = new StringBuilder();
            sb.append(obj2);
            sb.append(string);
            string = sb.toString();
        }
        if (zoneIdZone2 == null && zoneIdZone == null) {
            // without any zone only a 12-hour time ("6pm =") is converted
            boolean meridiem = false;
            Iterator<?> it = set.iterator();
            while (it.hasNext()) {
                if (StringsKt.endsWith((String) string, (String) it.next(), false)) {
                    meridiem = true;
                    break;
                }
            }
            if (!meridiem) {
                return null;
            }
        }
        ZoneId local = options.getLocal();
        ZonedDateTime now = options.getNow();
        if (now == null) {
            now = ZonedDateTime.now(local);
        }
        if (nowWords.contains(string)) {
            if (zoneIdZone == null || zoneIdZone2 != null) {
                return null;
            }
            return format(now.withZoneSameInstant(zoneIdZone).toLocalTime(), null);
        }
        MatchResult matchResultMatchEntire = timePattern.matchEntire((CharSequence) string);
        if (matchResultMatchEntire == null) {
            return null;
        }
        int i7 = Integer.parseInt(matchResultMatchEntire.getGroupValues().get(1));
        String str = matchResultMatchEntire.getGroupValues().get(2);
        String str2 = matchResultMatchEntire.getGroupValues().get(3);
        if (str2.length() == 0) {
            str2 = null;
        }
        String str3 = str2;
        if (str.length() == 0 && str3 == null && zoneIdZone2 == null) {
            return null;
        }
        int i8 = str.length() == 0 ? 0 : Integer.parseInt(str);
        if (i8 > 59) {
            return null;
        }
        if (str3 != null) {
            if (1 > i7 || i7 >= 13) {
                return null;
            }
            i7 = (i7 % 12) + (StringsKt.startsWith((CharSequence) str3, 'p', false) ? 12 : 0);
        } else if (i7 > 23) {
            return null;
        }
        if (zoneIdZone2 == null && zoneIdZone == null) {
            return format(LocalTime.of(i7, i8), null);
        }
        if (zoneIdZone2 == null) {
            zoneIdZone2 = local;
        }
        if (zoneIdZone == null) {
            zoneIdZone = local;
        }
        ZonedDateTime zonedDateTimeOf = ZonedDateTime.of(now.withZoneSameInstant(zoneIdZone2).toLocalDate(), LocalTime.of(i7, i8), zoneIdZone2);
        ZonedDateTime zonedDateTimeO = zonedDateTimeOf.withZoneSameInstant(zoneIdZone);
        if (Intrinsics.areEqual(zonedDateTimeO.toLocalDateTime(), zonedDateTimeOf.toLocalDateTime())) {
            return null;
        }
        long epochDay = zonedDateTimeO.toLocalDate().toEpochDay() - zonedDateTimeOf.toLocalDate().toEpochDay();
        String str4 = format(zonedDateTimeO.toLocalTime(), str3);
        if (epochDay > 0) {
            return str4 + " (+" + epochDay + ")";
        }
        if (epochDay >= 0) {
            return str4;
        }
        return str4 + " (" + epochDay + ")";
    }

    private final String format(LocalTime time, String meridiem) {
        String strPadStart = StringsKt.padStart(String.valueOf(time.getMinute()), 2, '0');
        if (meridiem == null) {
            return StringsKt.padStart(String.valueOf(time.getHour()), 2, '0') + ":" + strPadStart;
        }
        return (((time.getHour() + 11) % 12) + 1) + ":" + strPadStart + " " + (time.getHour() < 12 ? "am" : "pm");
    }

    private final ZoneId zone(List<String> words, int from, int to) {
        String strJoinToString$default = CollectionsKt.joinToString(words.subList(from, to), " ", "", "", -1, "...", null);
        String str = zones.get(normalize(strJoinToString$default));
        if (str != null) {
            try {
                return ZoneId.of(str);
            } catch (DateTimeException unused) {
                return null;
            }
        }
        MatchResult matchResultMatchEntire = offsetPattern.matchEntire(strJoinToString$default);
        if (matchResultMatchEntire == null) {
            return null;
        }
        int i = (Intrinsics.areEqual(matchResultMatchEntire.getGroupValues().get(1), "msk") || Intrinsics.areEqual(matchResultMatchEntire.getGroupValues().get(1), "мск")) ? 180 : 0;
        int i2 = Integer.parseInt(matchResultMatchEntire.getGroupValues().get(3));
        String str2 = matchResultMatchEntire.getGroupValues().get(4);
        if (str2.length() == 0) {
            str2 = MVEL.VERSION_SUB;
        }
        int i3 = Integer.parseInt(str2);
        if (i2 <= 18 && i3 <= 59) {
            try {
                return ZoneOffset.ofTotalSeconds((i + ((Intrinsics.areEqual(matchResultMatchEntire.getGroupValues().get(2), "-") ? -1 : 1) * ((i2 * 60) + i3))) * 60);
            } catch (DateTimeException unused2) {
            }
        }
        return null;
    }

    private final String normalize(String name) {
        return StringsKt.replace(StringsKt.replace(name.toLowerCase(Locale.ROOT), (char) 1105, (char) 1077, false), SignatureVisitor.SUPER, ' ', false);
    }

    private static final void _init_$add(String str, String... strArr) {
        for (String str2 : strArr) {
            zones.put(INSTANCE.normalize(str2), str);
        }
    }
}

package com.zwlib.quick;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Reservation flow, reverse engineered from the site bundle. Four steps, not three:
 *
 *   1  POST /static/frontApi/res/buildingFloorDate/{}/            -> data.buildings[].floors[], data.dates[]
 *   2  POST /static/frontApi/res/findRoomDuration/{venueId}/{date} -> data.pageList[]   (座位区/房间)
 *   3  POST /static/frontApi/res/freeSeatIdsDuration/{roomId}/{date} -> free seat ids
 *   4  POST /static/frontApi/make/freeBook/{seat}/{date}/{b}/{e}?capToken=capToken
 *
 * Step 2 is what the first version missed: buildingFloorDate stops at floor level,
 * so a building/floor id can never be used as a roomId in steps 3/4.
 *
 * start/end are minutes since midnight; dates are yyyy-MM-dd strings.
 * Every request needs the HMAC headers the site's axios interceptor adds.
 */
final class Booker {

    static final String HOST = "https://zwlib.ruc.edu.cn";
    static final String BASE = HOST + "/jsq";
    static final String PROBE = BASE + "/static/frontApi/user/getUserInfo";
    static final String SSO = HOST + "/rem/static/sso/login"
            + "?redirectUrl=" + HOST + "/jsq-v";
    static final String AUTH_URL = HOST + "/jsq-v/";
    static final String CAS_URL = "https://cas.ruc.edu.cn";

    static final int VALID = 1;
    static final int EXPIRED = 2;
    static final int UNKNOWN = 3;

    static final int MAX_ROOMS_TRIED = 6;
    static final int MAX_SEATS_TRIED = 3;   // 座位被抢时换下一个，别死磕

    static final int MODE_CFG = 0;    // 按配置（定时任务用）
    static final int MODE_DRY = 1;    // 强制演练
    static final int MODE_REAL = 2;   // 强制真实下单（手动试约用）

    /* ------------------------------------------------------------------ */
    /* config                                                             */
    /* ------------------------------------------------------------------ */

    static final class Cfg {
        boolean enabled;
        boolean dryRun = true;         // safety default: never book until the user clears it
        int minuteOfDay = 7 * 60 + 30; // 07:30
        String venueId = "";
        String venueName = "";
        String roomId = "";            // 座位区；空 = 自动挑最空的
        String roomName = "";
        String seatPriority = "";      // 有序座位标签，如 "122,124,126"；空 = 区内取 id 最小
        int floorId = 0;               // 0 = all floors
        int dateOffset = 0;            // 0 = 今天，1 = 明天（按语义，不按列表位置）
        String dateLabel = "";
        int beginMinute = -1;          // -1 = 未设置，必须每次自己指定
        int endMinute = -1;

        static Cfg load(Context c) {
            SharedPreferences p = c.getSharedPreferences("zw_flags", Context.MODE_PRIVATE);
            Cfg f = new Cfg();
            f.enabled = p.getBoolean("bk_enabled", false);
            f.dryRun = p.getBoolean("bk_dry", true);
            f.minuteOfDay = p.getInt("bk_time", 7 * 60 + 30);
            f.venueId = p.getString("bk_venue", "");
            f.venueName = p.getString("bk_venue_name", "");
            f.floorId = p.getInt("bk_floor", 0);
            f.dateOffset = p.getInt("bk_date_offset", 0);
            f.dateLabel = p.getString("bk_date_label", "");
            f.roomId = p.getString("bk_room", "");
            f.roomName = p.getString("bk_room_name", "");
            f.seatPriority = p.getString("bk_seat_priority", "");
            f.beginMinute = p.getInt("bk_begin", -1);
            f.endMinute = p.getInt("bk_end", -1);
            return f;
        }

        void save(Context c) {
            c.getSharedPreferences("zw_flags", Context.MODE_PRIVATE).edit()
                    .putBoolean("bk_enabled", enabled)
                    .putBoolean("bk_dry", dryRun)
                    .putInt("bk_time", minuteOfDay)
                    .putString("bk_venue", venueId)
                    .putString("bk_venue_name", venueName)
                    .putInt("bk_floor", floorId)
                    .putString("bk_room", roomId)
                    .putString("bk_room_name", roomName)
                    .putString("bk_seat_priority", seatPriority)
                    .putInt("bk_date_offset", dateOffset)
                    .putString("bk_date_label", dateLabel)
                    .putInt("bk_begin", beginMinute)
                    .putInt("bk_end", endMinute)
                    .apply();
        }

        String timeText() { return hhmm(minuteOfDay); }

        boolean hasWindow() { return beginMinute >= 0 && endMinute > beginMinute; }

        String windowText() {
            return hasWindow() ? (hhmm(beginMinute) + " - " + hhmm(endMinute)) : "未设置";
        }
    }

    static String hhmm(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    static class Item {
        String id;
        String label;
        int free = -1;   // seatFree when the server reports it, else unknown

        Item(String id, String label) {
            this.id = id;
            this.label = label;
        }

        Item(String id, String label, int free) {
            this.id = id;
            this.label = label;
            this.free = free;
        }
    }

    static final class Seat {
        final String id;
        final String label;

        Seat(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    static final class Outcome {
        boolean ok;
        boolean dry;
        String title = "";
        String detail = "";
        String bookingId;
        JSONObject raw;

        /** @param code short, human-relayable reason — becomes the dialog TITLE */
        static Outcome fail(String code) {
            Outcome o = new Outcome();
            o.title = code;
            return o;
        }
    }

    /* ------------------------------------------------------------------ */
    /* token                                                              */
    /* ------------------------------------------------------------------ */

    static int probe(String token) {
        return probe(token, null);
    }

    static int probe(String token, Net.Jar jar) {
        if (token == null || token.length() < 4) {
            return EXPIRED;
        }
        Net.Resp r = Net.postJson(PROBE, token, "{}", jar);
        if (r.code != 200) {
            return UNKNOWN;
        }
        if (r.body.contains("20003") || r.body.contains("20002")) {
            return EXPIRED;
        }
        if (r.body.contains("\"status\" : true") || r.body.contains("\"status\":true")) {
            return VALID;
        }
        return UNKNOWN;
    }

    static String sessionToken(String sessionJson) {
        if (sessionJson == null || sessionJson.length() < 3) {
            return null;
        }
        try {
            return firstStr(new JSONObject(sessionJson), "token");
        } catch (Exception e) {
            return null;
        }
    }

    static Net.Jar loadJar(SecureStore sec) {
        return Net.Jar.fromJson(sec.get("cookies"));
    }

    static void saveJar(SecureStore sec, Net.Jar jar) {
        sec.put("cookies", jar.toJson());
    }

    /** The live WebView jar is the source of truth; the stored copy is only a fallback. */
    static void primeJar(Net.Jar jar, boolean fromWebView) {
        if (!fromWebView) {
            return;
        }
        jar.seed(AUTH_URL);
        jar.seed(CAS_URL);
    }

    /** Walk the CAS SSO chain with the cookies we already have; returns a fresh token. */
    static String refreshToken(Net.Jar jar) {
        Net.Resp r = Net.request("GET", SSO, null, null, jar);
        String t = Net.tokenFromUrl(r.url);
        if (t == null) {
            t = Net.tokenFromUrl(r.url.replace("#", "?"));
        }
        return t;
    }

    static String ensureToken(Context ctx, SecureStore sec, Net.Jar jar, String session,
                              StringBuilder log) {
        String token = sessionToken(session);
        if (token != null) {
            int st = probe(token, jar);
            log.append("· 座位 token 探活: ")
                    .append(st == VALID ? "有效" : st == EXPIRED ? "已过期" : "未知（网络问题，先按有效用）")
                    .append('\n');
            if (st != EXPIRED) {
                return token;
            }
        } else {
            log.append("· 没有保存的座位 token\n");
        }

        log.append("· 尝试用 7 天免登录凭据静默换新 token…\n");
        String fresh = refreshToken(jar);
        saveJar(sec, jar);
        if (fresh == null || fresh.length() < 4) {
            log.append("· 换新失败（7 天免登录可能已到期，需要人工过一次验证码）\n");
            return null;
        }
        if (probe(fresh) == EXPIRED) {
            log.append("· 换到的新 token 也不被接受\n");
            return null;
        }
        try {
            JSONObject sess = session == null ? new JSONObject() : new JSONObject(session);
            sess.put("token", fresh);
            if (!sess.has("loginType")) {
                sess.put("loginType", "cas");
            }
            sec.put("session", sess.toString());
        } catch (Exception ignored) {
        }
        log.append("· 换新成功\n");
        return fresh;
    }

    /* ------------------------------------------------------------------ */
    /* request signing (the site's axios interceptor)                     */
    /* ------------------------------------------------------------------ */

    static void installSigner(String sessionJson, Net.Jar jar, StringBuilder log) {
        JSONObject sys = null;
        try {
            String si = sessionJson == null ? null
                    : new JSONObject(sessionJson).optString("systemInfo", null);
            if (si != null && si.length() > 2) {
                sys = new JSONObject(si);
            }
        } catch (Exception ignored) {
        }
        if (sys == null) {
            JSONObject r = post("/static/public/cg/getSysSet/PC", null, "{}", jar, null);
            sys = r == null ? null : r.optJSONObject("data");
        }
        if (sys == null) {
            if (log != null) {
                log.append("· 请求签名: 拿不到 systemInfo\n");
            }
            return;
        }
        int hmac = sys.optInt("hmac", 0);
        String enc = sys.optString("hmacKey", "");
        if (hmac == 1 && !enc.isEmpty()) {
            String key = Net.unwrapKey(enc);
            Net.signKey = key;
            if (log != null) {
                log.append("· 请求签名: 已启用（hmac=1，密钥长度 ")
                        .append(key == null ? 0 : key.length()).append("）\n");
            }
        } else if (log != null) {
            log.append("· 请求签名: 服务端未要求（hmac=").append(hmac).append("）\n");
        }
    }

    /* ------------------------------------------------------------------ */
    /* http                                                               */
    /* ------------------------------------------------------------------ */

    static JSONObject post(String path, String token, String body, Net.Jar jar) {
        return post(path, token, body, jar, null);
    }

    static JSONObject post(String path, String token, String body, Net.Jar jar, StringBuilder log) {
        Net.Resp r = Net.postJson(BASE + path, token, body, jar);
        if (log != null) {
            String b = r.body == null ? "" : r.body.replace('\n', ' ').replace('\r', ' ').trim();
            if (b.length() > 320) {
                b = b.substring(0, 320) + "…";
            }
            log.append("· ").append(path.replace("/static/frontApi", ""))
                    .append(" → HTTP ").append(r.code);
            if (Net.signKey != null) {
                log.append(" 已签名");
            }
            log.append('\n');
            if (!b.isEmpty()) {
                log.append("    ").append(b).append('\n');
            }
        }
        if (r.code != 200) {
            return null;
        }
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            return null;
        }
    }

    static JSONObject venues(String token, Net.Jar jar, StringBuilder log) {
        return post("/static/frontApi/res/buildingFloorDate", token, "{}", jar, log);
    }

    /* ------------------------------------------------------------------ */
    /* step 1 data: buildings / floors / dates                            */
    /* ------------------------------------------------------------------ */

    static JSONArray buildingsOf(JSONObject venuesResp) {
        JSONObject d = venuesResp == null ? null : venuesResp.optJSONObject("data");
        JSONArray a = d == null ? null : d.optJSONArray("buildings");
        return a == null ? new JSONArray() : a;
    }

    static List<Item> buildings(JSONObject venuesResp) {
        List<Item> out = new ArrayList<Item>();
        JSONArray a = buildingsOf(venuesResp);
        for (int i = 0; i < a.length(); i++) {
            JSONObject b = a.optJSONObject(i);
            if (b == null) {
                continue;
            }
            String id = firstStr(b, "id", "venueId", "buildId");
            String name = firstStr(b, "name", "buildName", "venueName");
            if (id != null) {
                out.add(new Item(id, name == null ? ("馆 " + id) : name));
            }
        }
        return out;
    }

    static List<Item> floors(JSONObject venuesResp, String venueId) {
        List<Item> out = new ArrayList<Item>();
        out.add(new Item("0", "全部楼层"));
        JSONArray a = buildingsOf(venuesResp);
        for (int i = 0; i < a.length(); i++) {
            JSONObject b = a.optJSONObject(i);
            if (b == null || !String.valueOf(firstStr(b, "id", "venueId")).equals(String.valueOf(venueId))) {
                continue;
            }
            JSONArray fs = b.optJSONArray("floors");
            if (fs == null) {
                continue;
            }
            for (int j = 0; j < fs.length(); j++) {
                JSONObject f = fs.optJSONObject(j);
                if (f == null) {
                    continue;
                }
                String fid = firstStr(f, "id", "floorId");
                String fname = firstStr(f, "name", "floorName");
                if (fid != null) {
                    out.add(new Item(fid, fname == null ? ("楼层 " + fid) : fname));
                }
            }
        }
        return out;
    }

    static List<String> bookableDates(JSONObject venuesResp) {
        List<String> out = new ArrayList<String>();
        JSONObject d = venuesResp == null ? null : venuesResp.optJSONObject("data");
        Object dates = d == null ? null : d.opt("dates");
        if (dates instanceof JSONArray) {
            JSONArray a = (JSONArray) dates;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v instanceof String) {
                    out.add((String) v);
                } else if (v instanceof JSONObject) {
                    String s = firstStr((JSONObject) v, "value", "date", "makeDate", "day");
                    if (s != null) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    /** Pure. Out-of-range indexes clamp to the first bookable date, never crash. */
    static String pickDate(List<String> dates, int index, String fallbackToday) {
        if (dates == null || dates.isEmpty()) {
            return fallbackToday;
        }
        int i = (index < 0 || index >= dates.size()) ? 0 : index;
        return dates.get(i);
    }

    /** Pure. Minutes since midnight for a given instant. */
    static int minuteOfDay(java.util.Date d) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTime(d);
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    /** Pure. yyyy-MM-dd plus n days. */
    static String addDays(String yyyyMMdd, int n) {
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(yyyyMMdd));
            c.add(java.util.Calendar.DAY_OF_YEAR, n);
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Pure. Resolve "today + offset" against the server's bookable list.
     *
     * Returns null when that day is not bookable (yet). It never substitutes another
     * day: booking 今天 because 明天 had not opened yet would create an unattended
     * no-show — i.e. a violation.
     */
    static String resolveDate(List<String> dates, String today, int offset) {
        String want = addDays(today, offset);
        if (want == null) {
            return null;
        }
        if (dates == null || dates.isEmpty()) {
            return offset == 0 ? today : null;
        }
        for (String d : dates) {
            if (want.equals(d)) {
                return want;
            }
        }
        return null;
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /* ------------------------------------------------------------------ */
    /* step 2: rooms (座位区) for a venue + date + window                  */
    /* ------------------------------------------------------------------ */

    static List<Item> rooms(String token, Net.Jar jar, String venueId, String date,
                            int begin, int end, int floorId, StringBuilder log) {
        JSONObject body = new JSONObject();
        try {
            body.put("beginMinute", begin);
            body.put("endMinute", end);
            body.put("floorId", floorId);
            body.put("minMinute", 0);
            body.put("currentPage", 1);
            body.put("pageSize", 50);
            body.put("power", false);
            body.put("roomType", false);
            body.put("sortField", "");
            body.put("sortType", "");
            body.put("windows", false);
        } catch (Exception ignored) {
        }
        JSONObject resp = post("/static/frontApi/res/findRoomDuration/" + venueId + "/" + date,
                token, body.toString(), jar, log);
        List<Item> out = parseRooms(resp);
        if (log != null) {
            JSONObject d = resp == null ? null : resp.optJSONObject("data");
            JSONArray list = d == null ? null : d.optJSONArray("pageList");
            if (list != null && list.length() > 0) {
                String raw = list.optJSONObject(0) == null ? "" : list.optJSONObject(0).toString();
                log.append("    首个座位区原始数据: ")
                        .append(raw.length() > 220 ? raw.substring(0, 220) + "…" : raw).append('\n');
            }
        }
        return out;
    }

    /**
     * Pure. pageList entries carry: id, name/nameE, buildingName, floorName,
     * seatTotal, seatFree (all read straight off the site's room card template).
     */
    static List<Item> parseRooms(JSONObject resp) {
        List<Item> out = new ArrayList<Item>();
        JSONObject d = resp == null ? null : resp.optJSONObject("data");
        JSONArray list = d == null ? null : d.optJSONArray("pageList");
        if (list == null) {
            return out;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.optJSONObject(i);
            if (r == null) {
                continue;
            }
            String id = firstStr(r, "id", "roomId");
            String name = firstStr(r, "roomName", "name", "label");
            int free = r.optInt("seatFree", -1);
            if (id != null) {
                out.add(new Item(id, name == null ? ("座位区 " + id) : name, free));
            }
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* step 3: free seats inside one room                                 */
    /* ------------------------------------------------------------------ */

    static List<Seat> freeSeats(String token, Net.Jar jar, String roomId, String date,
                                int begin, int end, StringBuilder log) {
        JSONObject body = new JSONObject();
        try {
            body.put("beginMinute", begin);
            body.put("endMinute", end);
            body.put("minMinute", 0);
        } catch (Exception ignored) {
        }
        JSONObject resp = post("/static/frontApi/res/freeSeatIdsDuration/" + roomId + "/" + date,
                token, body.toString(), jar, log);
        int[] stats = new int[2];
        List<Seat> seats = parseSeats(resp, stats);
        if (log != null && stats[0] != stats[1]) {
            log.append("    （响应共 ").append(stats[0]).append(" 项，滤掉 ")
                    .append(stats[0] - stats[1]).append(" 个已约/暂离，剩 ")
                    .append(stats[1]).append(" 个真可用）\n");
        }
        return seats;
    }

    /**
     * Pure. The real payload is an OBJECT keyed by seat id — the seat map does
     * `t[i.seat.id]` to look a seat up, and `Object.keys(o).length` to test emptiness.
     * Arrays are still accepted so a server-side change does not silently break us.
     */
    /**
     * Pure. The real payload is an OBJECT keyed by seat id, each value carrying a
     * human label — the seat map does `t[i.seat.id]` and the booking confirmation
     * shows `label` ("B102"), which is what a person needs to find their seat.
     */
    static List<Seat> parseSeats(JSONObject resp) {
        return parseSeats(resp, null);
    }

    /**
     * Pure.
     *
     * IMPORTANT: this endpoint does NOT return only free seats. Real payloads mix in
     *   status "AWAY" (暂离 — reserved, occupant stepped out) and "FULL" (已约),
     * which is how a room reports "seatFree 80" but hands back 138 entries.
     * Booking one of those gets rejected, so they must be filtered out.
     *
     * @param stats optional 1-element array; gets [total entries, kept entries]
     */
    static List<Seat> parseSeats(JSONObject resp, int[] stats) {
        List<Seat> out = new ArrayList<Seat>();
        if (resp == null || !resp.optBoolean("status")) {
            return out;
        }
        int total = 0;
        Object data = resp.opt("data");
        if (data instanceof JSONObject) {
            JSONObject m = (JSONObject) data;
            for (Iterator<String> it = m.keys(); it.hasNext(); ) {
                String id = it.next();
                total++;
                JSONObject v = m.optJSONObject(id);
                String status = v == null ? null : firstStr(v, "status");
                // no status field at all -> trust the endpoint (older/other shapes)
                if (status != null && !"FREE".equalsIgnoreCase(status)) {
                    continue;
                }
                String label = v == null ? null : firstStr(v, "label", "seatLabel", "name");
                out.add(new Seat(id, label));
            }
        } else if (data instanceof JSONArray) {
            JSONArray a = (JSONArray) data;
            for (int i = 0; i < a.length(); i++) {
                total++;
                Object v = a.opt(i);
                if (v instanceof String) {
                    out.add(new Seat((String) v, null));
                } else if (v instanceof JSONObject) {
                    JSONObject o = (JSONObject) v;
                    String st = firstStr(o, "status");
                    if (st != null && !"FREE".equalsIgnoreCase(st)) {
                        continue;
                    }
                    String id = firstStr(o, "id", "seatId", "value");
                    if (id != null) {
                        out.add(new Seat(id, firstStr(o, "label", "seatLabel", "name")));
                    }
                } else if (v instanceof JSONArray) {
                    JSONArray p = (JSONArray) v;
                    if (p.length() > 0 && p.opt(0) != null) {
                        String id = String.valueOf(p.opt(0));
                        out.add(new Seat(id, p.length() > 1 ? String.valueOf(p.opt(1)) : null));
                    }
                }
            }
        }
        if (stats != null) {
            stats[0] = total;
            stats[1] = out.size();
        }
        // org.json's keySet() is hash-ordered, so pick a deterministic seat:
        // ascending id (ids are equal-length numeric strings, so lexicographic == numeric)
        java.util.Collections.sort(out, new java.util.Comparator<Seat>() {
            @Override
            public int compare(Seat a, Seat b) {
                if (a.id.length() != b.id.length()) {
                    return a.id.length() - b.id.length();
                }
                return a.id.compareTo(b.id);
            }
        });
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* step 4b: the cancellable booking id                               */
    /* ------------------------------------------------------------------ */

    static String lastMakeId(String token, Net.Jar jar, StringBuilder log) {
        JSONObject resp = post("/static/frontApi/user/lastMake", token, "{}", jar, null);
        return parseLastMakeId(resp);
    }

    /**
     * Pure. /user/lastMake returns a LIST; each record has id + status.
     * The cancel action needs that id — the freeBook response's orderObj has
     * message/makeDateStr/makeBeginStr/makeEndStr/location/seatLabel but NO id.
     */
    static String parseLastMakeId(JSONObject resp) {
        if (resp == null || !resp.optBoolean("status")) {
            return null;
        }
        Object d = resp.opt("data");
        if (!(d instanceof JSONArray)) {
            return null;
        }
        JSONArray a = (JSONArray) d;
        String first = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null) {
                continue;
            }
            String id = firstStr(r, "id", "makeId");
            if (id == null) {
                continue;
            }
            if (first == null) {
                first = id;
            }
            if ("RESERVE".equals(r.optString("status"))) {
                return id;
            }
        }
        return first;
    }

    /* ------------------------------------------------------------------ */
    /* seat preference                                                    */
    /* ------------------------------------------------------------------ */

    /** Pure. Splits on comma / Chinese comma / whitespace / 、 */
    static List<String> parseSeatPriority(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[,\\s，、;；]+")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Pure. Lenient label match so a person can type "122" for a seat labelled "B122",
     * without letting "22" match "122" (the char before must not be a digit).
     */
    static boolean labelMatches(String label, String token) {
        if (label == null || token == null) {
            return false;
        }
        String l = label.trim().toUpperCase();
        String t = token.trim().toUpperCase();
        if (l.isEmpty() || t.isEmpty()) {
            return false;
        }
        if (l.equals(t)) {
            return true;
        }
        if (l.endsWith(t)) {
            int idx = l.length() - t.length();
            if (idx == 0) {
                return true;
            }
            // Rule: the prefix must contain no NON-ZERO digit.
            //   "B122" vs "122" -> prefix "B"   (letter)      -> match
            //   "040"  vs "40"  -> prefix "0"   (zero)        -> match
            //   "122"  vs "22"  -> prefix "1"   (non-zero!)   -> reject, it is a different seat
            //   "B1022" vs "22" -> prefix "B10" (has a "1")   -> reject
            for (int i = 0; i < idx; i++) {
                char ch = l.charAt(i);
                if (Character.isDigit(ch) && ch != '0') {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** Pure. First preferred seat that is free; falls back to the lowest id. */
    static Seat chooseSeat(List<Seat> free, String priority, StringBuilder log) {
        if (free == null || free.isEmpty()) {
            return null;
        }
        for (String want : parseSeatPriority(priority)) {
            for (Seat st : free) {
                if (labelMatches(st.label, want) || want.equals(st.id)) {
                    if (log != null) {
                        log.append("    · 命中优先座位 ")
                                .append(st.label == null ? st.id : st.label).append('\n');
                    }
                    return st;
                }
            }
            if (log != null) {
                log.append("    · 优先座位 ").append(want).append(" 此刻没空，看下一个\n");
            }
        }
        return free.get(0);
    }

    /* ------------------------------------------------------------------ */
    /* captcha gate                                                       */
    /* ------------------------------------------------------------------ */

    static final class Risk {
        int mack = -1;
        boolean high;
        boolean needCaptcha;
        String text = "";

        @Override
        public String toString() {
            return text;
        }
    }

    static Risk risk(String token, Net.Jar jar) {
        return risk(token, jar, null);
    }

    static Risk risk(String token, Net.Jar jar, StringBuilder log) {
        Risk r = new Risk();
        JSONObject sys = post("/static/public/cg/getSysSet/PC", null, "{}", jar, null);
        JSONObject d = sys == null ? null : sys.optJSONObject("data");
        r.mack = d == null ? -1 : d.optInt("mackCaptcha", -1);
        if (r.mack == 1) {
            JSONObject h = post("/static/cap/cg/checkHigh", token, "{}", jar, log);
            r.high = h != null && h.optBoolean("data");
        }
        r.needCaptcha = (r.mack == 2) || (r.mack == 1 && r.high);
        StringBuilder b = new StringBuilder();
        b.append("mackCaptcha = ").append(r.mack < 0 ? "读不到" : String.valueOf(r.mack)).append('\n');
        if (r.mack == 1) {
            b.append("checkHigh（高峰期）= ").append(r.high).append('\n');
        }
        b.append('\n');
        if (r.mack == 0) {
            b.append("结论：不需要验证码 —— 预约接口直接放行，任何时间点都一样。");
        } else if (r.mack == 2) {
            b.append("结论：当前配置为「总是要验证码」，脚本不会代过，到点只能通知你手动预约。");
        } else if (r.mack == 1) {
            b.append(r.high ? "结论：此刻是高峰期，会弹滑块。脚本不会代过。"
                    : "结论：此刻不是高峰期，不需要验证码。");
        } else {
            b.append("结论：读不到服务端配置，无法判断。");
        }
        r.text = b.toString();
        return r;
    }

    /* ------------------------------------------------------------------ */
    /* the whole run                                                      */
    /* ------------------------------------------------------------------ */

    static Outcome run(Context ctx, SecureStore sec, Cfg cfg, int mode, boolean foreground) {
        StringBuilder log = new StringBuilder();
        try {
            Net.Jar jar = loadJar(sec);
            primeJar(jar, foreground);
            installSigner(sec.get("session"), jar, log);

            String session = sec.get("session");
            String token = ensureToken(ctx, sec, jar, session, log);
            if (token == null) {
                String why = session == null ? "S0 从没登录过（没有会话快照，先打开 App 登录一次）"
                        : sessionToken(session) == null ? "S1 会话快照里没有 token"
                        : "S2 token 已过期，且用 7 天免登录凭据换新失败";
                Outcome o = Outcome.fail(why);
                o.detail = state(sec) + log;
                return o;
            }
            if (foreground) {
                saveJar(sec, jar);
            }

            /* step 1 */
            JSONObject venues = venues(token, jar, log);
            if (venues == null || !venues.optBoolean("status")) {
                String why;
                if (Net.lastHttp != 200) {
                    why = "S4 连接/HTTP 失败（HTTP " + Net.lastHttp + "）";
                } else if (!Net.lastJson) {
                    why = "S7 响应不是 JSON（可能被网关拦了）";
                } else if (venues == null) {
                    why = "S5 响应无法解析";
                } else {
                    why = "S6 服务端拒绝：" + venues.optString("message");
                }
                Outcome o = Outcome.fail(why);
                o.detail = state(sec) + log;
                return o;
            }
            List<Item> bl = buildings(venues);
            List<String> dates = bookableDates(venues);
            log.append("· 馆: ").append(bl.size()).append(" 个 ");
            for (int i = 0; i < bl.size() && i < 6; i++) {
                log.append('[').append(i).append(']').append(bl.get(i).label).append(' ');
            }
            log.append('\n');
            log.append("· 可预约日期: ").append(dates).append('\n');
            if (bl.isEmpty()) {
                Outcome o = Outcome.fail("S8 解析为空：响应里没有 buildings");
                o.detail = state(sec) + log;
                return o;
            }

            String venueId = cfg.venueId;
            if (venueId == null || venueId.isEmpty()) {
                venueId = bl.get(0).id;
                log.append("· 未配置馆，暂用: ").append(bl.get(0).label).append('\n');
            }

            String today = today();
            String date = resolveDate(dates, today, cfg.dateOffset);
            if (date == null) {
                Outcome o = Outcome.fail("S10 目标日期 " + addDays(today, cfg.dateOffset)
                        + " 还不在可约列表 " + dates + " 里 —— 没有乱约别的日子");
                o.detail = state(sec) + log;
                return o;
            }
            log.append("· 目标日期: ").append(date)
                    .append(cfg.dateOffset == 0 ? "（今天）" : "（" + cfg.dateOffset + " 天后）")
                    .append('\n');
            if (!cfg.hasWindow()) {
                Outcome o = Outcome.fail("S9 还没设置时间段 —— 设置里点「设置时间段」指定一次");
                o.detail = state(sec) + log;
                return o;
            }
            log.append("· 目标时段: ").append(cfg.windowText()).append('\n');
            // 已经开始的时段服务端是允许的（手机端手动就能约），所以只记一笔，不拦
            int nowMin = minuteOfDay(new java.util.Date());
            if (date.equals(today) && cfg.beginMinute < nowMin) {
                log.append("· 注意：该时段已开始 ").append(nowMin - cfg.beginMinute)
                        .append(" 分钟，服务端允许这种预约\n");
            }

            /* step 2 */
            List<Item> roomList = rooms(token, jar, venueId, date, cfg.beginMinute,
                    cfg.endMinute, cfg.floorId, log);
            log.append("· 座位区: ").append(roomList.size()).append(" 个\n");
            if (roomList.isEmpty()) {
                Outcome o = Outcome.fail("S8 解析为空：响应里没有 pageList");
                o.detail = state(sec) + log;
                return o;
            }

            Risk risk = risk(token, jar, log);
            log.append("· 风控: ").append(risk.text.replace("\n", " ")).append('\n');
            boolean dry = (mode == MODE_DRY) || (mode == MODE_CFG && cfg.dryRun);
            if (risk.needCaptcha && !dry) {
                Outcome o = Outcome.fail("系统当前要求滑块验证码（高峰期）。"
                        + "脚本不代过验证码 —— 请手动预约这一次。");
                o.detail = log.toString();
                return o;
            }

            /* step 3: walk the room list until one has a free seat */
            // preferred room first (if configured and still listed), then the emptiest
            List<Item> ordered = new ArrayList<Item>();
            if (cfg.roomId != null && !cfg.roomId.isEmpty()) {
                Item pref = null;
                for (Item r : roomList) {
                    if (r.id.equals(cfg.roomId)) {
                        pref = r;
                        break;
                    }
                }
                if (pref != null) {
                    ordered.add(pref);
                    log.append("· 用指定的座位区: ").append(pref.label)
                            .append("（该时段空座 ").append(pref.free).append("）\n");
                } else {
                    log.append("· 指定的座位区不在本次列表里，改用最空的区\n");
                }
            }
            List<Item> rest = new ArrayList<Item>();
            for (Item r : roomList) {
                if (ordered.isEmpty() || !r.id.equals(ordered.get(0).id)) {
                    rest.add(r);
                }
            }
            java.util.Collections.sort(rest, new java.util.Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return b.free - a.free;
                }
            });
            ordered.addAll(rest);
            if (!ordered.isEmpty() && ordered.get(0).free > 0 && ordered.get(0).free != Integer.MIN_VALUE) {
                log.append("· 首选区剩余 ").append(ordered.get(0).free).append(" 个座位\n");
            }

            String roomId = null;
            String roomName = null;
            String seatId = null;
            String seatLabel = null;
            List<Seat> candidateSeats = new ArrayList<Seat>();
            int tried = 0;
            for (Item room : ordered) {
                if (tried++ >= MAX_ROOMS_TRIED) {
                    break;
                }
                if (room.free == 0) {
                    log.append("    ").append(room.label).append(" → 服务端说满了，跳过\n");
                    continue;
                }
                List<Seat> seats = freeSeats(token, jar, room.id, date, cfg.beginMinute,
                        cfg.endMinute, log);
                log.append("    ").append(room.label).append(" → 空座 ").append(seats.size())
                        .append(" 个\n");
                if (seats.isEmpty()) {
                    continue;
                }
                Seat chosen = chooseSeat(seats, cfg.seatPriority, log);
                roomId = room.id;
                roomName = room.label;
                seatId = chosen.id;
                seatLabel = chosen.label;
                // 首选排最前，其余留着当备胎
                candidateSeats = new ArrayList<Seat>();
                candidateSeats.add(chosen);
                for (Seat st : seats) {
                    if (!st.id.equals(chosen.id)) {
                        candidateSeats.add(st);
                    }
                }
                break;
            }
            if (seatId == null) {
                Outcome o = Outcome.fail("试了 " + Math.min(tried, MAX_ROOMS_TRIED)
                        + " 个座位区，这个时段都没有空座");
                o.detail = log.toString();
                return o;
            }
            log.append("· 选中: ").append(roomName).append(" / 座位 ")
                    .append(seatLabel == null ? seatId : seatLabel).append('\n');

            if (dry) {
                Outcome o = new Outcome();
                o.ok = true;
                o.dry = true;
                o.title = "演练完成（未下单）";
                o.detail = log + "\n演练不会真的预约。确认无误后可在设置里关闭试运行。";
                return o;
            }

            /* step 4 —— 被拒就换下一个空座（服务端自己说"请选择其他时段或座位"） */
            Outcome out = new Outcome();
            int seatTries = 0;
            Seat accepted = null;
            for (Seat cand : candidateSeats) {
                if (seatTries++ >= MAX_SEATS_TRIED) {
                    break;
                }
                String cl = cand.label == null ? cand.id : cand.label;
                JSONObject resp = post("/static/frontApi/make/freeBook/" + cand.id + "/" + date
                                + "/" + cfg.beginMinute + "/" + cfg.endMinute + "?capToken=capToken",
                        token, "{}", jar, log);
                out.raw = resp;
                if (resp != null && resp.optBoolean("status")) {
                    accepted = cand;
                    out.ok = true;
                    out.title = "预约成功";
                    break;
                }
                String m = resp == null ? "请求没拿到有效响应" : resp.optString("message");
                boolean seatIssue = m != null && (m.contains("座位") || m.contains("时段"));
                log.append("    ✗ 座位 ").append(cl).append(" 被拒：").append(m).append('\n');
                if (!seatIssue) {
                    // 不是"换一个就行"的问题（比如验证码/黑名单），重试没意义
                    Outcome o = Outcome.fail("下单被拒: " + m);
                    o.detail = state(sec) + log + explain(token, jar, log);
                    return o;
                }
                if (seatTries >= MAX_SEATS_TRIED) {
                    Outcome o = Outcome.fail("试了 " + seatTries + " 个座位都被拒: " + m);
                    o.detail = state(sec) + log + explain(token, jar, log);
                    return o;
                }
                log.append("    → 换下一个空座再试\n");
            }
            if (accepted == null) {
                Outcome o = Outcome.fail("没有可用的座位了");
                o.detail = state(sec) + log;
                return o;
            }
            seatId = accepted.id;
            seatLabel = accepted.label;
            {
                JSONObject d = out.raw == null ? null : out.raw.optJSONObject("data");
                String when = d == null ? date : d.optString("makeDateStr", date);
                String span = d == null ? (hhmm(cfg.beginMinute) + "-" + hhmm(cfg.endMinute))
                        : (d.optString("makeBeginStr", hhmm(cfg.beginMinute)) + "-"
                           + d.optString("makeEndStr", hhmm(cfg.endMinute)));
                String place = d == null ? null : d.optString("location", null);
                if (place != null && place.length() > 1) {
                    place = place.replace('|', ' ').trim();
                } else {
                    place = roomName;
                }
                out.detail = place + " 座位 " + (seatLabel == null ? seatId : seatLabel)
                        + "\n" + when + "  " + span;
                out.bookingId = lastMakeId(token, jar, log);
                saveJar(sec, jar);
                return out;
            }
        } catch (Throwable t) {
            Outcome o = Outcome.fail("异常: " + t);
            o.detail = log.toString();
            return o;
        }
    }

    /**
     * After a rejected booking, ask the server the questions that actually explain it:
     * an existing active reservation ("当天只能有一个有效预约") and the violation count.
     */
    static String explain(String token, Net.Jar jar, StringBuilder log) {
        StringBuilder b = new StringBuilder("\n—— 失败原因排查 ——\n");
        try {
            JSONObject cur = post("/static/frontApi/user/currentUseMake", token, "{}", jar, null);
            if (cur != null && cur.optBoolean("status")) {
                Object d = cur.opt("data");
                boolean has = d instanceof JSONObject && ((JSONObject) d).length() > 0;
                b.append(has
                        ? "· 你当前已有一条有效预约 —— 「当天只能有一个有效预约」，先取消它再约\n"
                        : "· 当前没有其他有效预约（排除这一条）\n");
                if (has) {
                    String t = d.toString();
                    b.append("  ").append(t.length() > 200 ? t.substring(0, 200) : t).append('\n');
                }
            } else {
                b.append("· currentUseMake 查不到（").append(cur == null ? "无响应"
                        : cur.optString("message")).append("）\n");
            }

            JSONObject br = post("/static/frontApi/user/breach/1/5", token, "{}", jar, null);
            if (br != null && br.optBoolean("status")) {
                Object dd = br.opt("data");
                JSONArray list = dd instanceof JSONObject ? ((JSONObject) dd).optJSONArray("list") : null;
                int n = list == null ? 0 : list.length();
                b.append("· 近 30 天违约记录：").append(n).append(" 条（满 5 次进黑名单 1 天）\n");
                for (int i = 0; i < n && i < 3; i++) {
                    JSONObject r = list.optJSONObject(i);
                    if (r != null) {
                        b.append("    ").append(r.toString().length() > 120
                                ? r.toString().substring(0, 120) : r.toString()).append('\n');
                    }
                }
            }
        } catch (Throwable t) {
            b.append("· 排查时出错: ").append(t).append('\n');
        }
        b.append("—— 若上面都正常，那就是该时段/座位在查询与下单之间被抢了 ——\n");
        return b.toString();
    }

    /** Cancel a booking by id (/make/cancel/{id}). */
    static String cancel(String token, Net.Jar jar, String bookingId, StringBuilder log) {
        if (bookingId == null || bookingId.isEmpty()) {
            return "没有可取消的预约 id";
        }
        JSONObject r = post("/static/frontApi/make/cancel/" + bookingId, token, "{}", jar, log);
        if (r != null && r.optBoolean("status")) {
            return "已取消预约";
        }
        return "取消失败: " + (r == null ? "无响应" : r.optString("message"));
    }

    /** One-line health summary, so a screenshot of the dialog is enough to diagnose. */
    static String state(SecureStore sec) {
        String session = sec.get("session");
        boolean hasTok = sessionToken(session) != null;
        boolean hasCreds = sec.get("user") != null;
        boolean hasCookies = sec.get("cookies") != null;
        StringBuilder b = new StringBuilder("诊断: ");
        b.append("session=").append(session == null ? "无" : "有");
        b.append(" token=").append(hasTok ? "有" : "无");
        b.append(" 签名=").append(Net.signKey == null ? "未启用" : "已启用");
        b.append(" cookie快照=").append(hasCookies ? "有" : "无");
        b.append(" 账密=").append(hasCreds ? "有" : "无");
        b.append('\n');
        return b.toString();
    }

    static String firstStr(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v != null && v != JSONObject.NULL) {
                String s = String.valueOf(v);
                if (!s.isEmpty() && !"null".equals(s)) {
                    return s;
                }
            }
        }
        return null;
    }
}

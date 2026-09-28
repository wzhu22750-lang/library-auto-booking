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
        boolean ciEnabled;             // 暂离自动返回（默认关）
        boolean ciDry = true;          // 守护试运行：只记录，不调返回接口
        int ciGraceMin = 60;           // 平时：离座后多久算超时（图书馆规矩 1 小时）
        int ciMealGraceMin = 120;      // 饭点：宽限 2 小时
        int ciMealStartMin = 11 * 60;  // 饭点 11:00
        int ciMealEndMin = 13 * 60 + 30;
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
        int ciBeginMinute = 7 * 60;        // 自动签到守护时段（默认 07:00，与预约时段完全独立）
        int ciEndMinute = 22 * 60 + 30;    // 自动签到守护时段（默认 22:30）

        static Cfg load(Context c) {
            SharedPreferences p = c.getSharedPreferences("zw_flags", Context.MODE_PRIVATE);
            Cfg f = new Cfg();
            f.enabled = p.getBoolean("bk_enabled", false);
            f.dryRun = p.getBoolean("bk_dry", true);
            f.ciEnabled = p.getBoolean("bk_ci_enabled", false);
            f.ciDry = p.getBoolean("bk_ci_dry", true);
            f.ciGraceMin = p.getInt("bk_ci_grace", 60);
            f.ciMealGraceMin = p.getInt("bk_ci_meal_grace", 120);
            f.ciMealStartMin = p.getInt("bk_ci_meal_start", 11 * 60);
            f.ciMealEndMin = p.getInt("bk_ci_meal_end", 13 * 60 + 30);
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
            f.ciBeginMinute = p.getInt("bk_ci_begin", 7 * 60);
            f.ciEndMinute = p.getInt("bk_ci_end", 22 * 60 + 30);
            return f;
        }

        void save(Context c) {
            c.getSharedPreferences("zw_flags", Context.MODE_PRIVATE).edit()
                    .putBoolean("bk_enabled", enabled)
                    .putBoolean("bk_dry", dryRun)
                    .putBoolean("bk_ci_enabled", ciEnabled)
                    .putBoolean("bk_ci_dry", ciDry)
                    .putInt("bk_ci_grace", ciGraceMin)
                    .putInt("bk_ci_meal_grace", ciMealGraceMin)
                    .putInt("bk_ci_meal_start", ciMealStartMin)
                    .putInt("bk_ci_meal_end", ciMealEndMin)
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
                    .putInt("bk_ci_begin", ciBeginMinute)
                    .putInt("bk_ci_end", ciEndMinute)
                    .apply();
        }

        String timeText() { return hhmm(minuteOfDay); }

        boolean hasWindow() { return beginMinute >= 0 && endMinute > beginMinute; }

        String windowText() {
            return hasWindow() ? (hhmm(beginMinute) + " - " + hhmm(endMinute)) : "未设置";
        }

        boolean hasCiWindow() { return ciBeginMinute >= 0 && ciEndMinute > ciBeginMinute; }

        String ciWindowText() {
            return hasCiWindow() ? (hhmm(ciBeginMinute) + " - " + hhmm(ciEndMinute)) : "07:00 - 22:30";
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
    /* 签到状态 · 暂离守护                                                  */
    /* ------------------------------------------------------------------ */

    static final String CUR_MAKE = "/static/frontApi/user/currentUseMake";
    static final String DOOR_LOG = "/static/frontApi/user/doorLog/";
    /** 站点 PC 代码里就是这一行：qrMd5 传字面量 "PC"，PC 渠道不真扫二维码。 */
    static final String CHECK_IN = "/static/frontApi/make/checkIn?qrMd5=PC";
    static final String ST_AWAY = "AWAY";

    /** 当前有效预约的快照；has=false 表示服务端说此刻没有有效预约。 */
    static final class Now {
        boolean has;
        String state = "";
        String id, date, begin, end, place, seat;
        String awayRange;          // 站点的「暂离/返回时间」，如 "14:38~15:38"

        String text() {
            if (!has) {
                return "服务端说此刻没有有效预约";
            }
            StringBuilder b = new StringBuilder(stateText(state));
            if (place != null) {
                b.append(" · ").append(place.replace('|', ' ').trim());
            }
            if (seat != null) {
                b.append(' ').append(seat);
            }
            if (date != null || begin != null) {
                b.append('\n');
                if (date != null) {
                    b.append(date).append(' ');
                }
                if (begin != null) {
                    b.append(begin);
                }
                if (end != null) {
                    b.append('-').append(end);
                }
            }
            return b.toString();
        }
    }

    /** 一次巡检的结果：WatchReceiver 拿它决定发不发通知、下一次什么时候跑。 */
    static final class Tick {
        boolean ok;
        boolean notify;
        boolean ask;           // 这一轮发的是「要不要帮你签到」的询问（带两个按钮）
        boolean awayActive;    // 这一轮服务端说「暂离」
        boolean cancelAsk;     // 暂离结束 / 已经处理过 → 撤掉那条询问
        String kind = "";      // 用于同一状态不重复打扰（见 WatchReceiver）
        String title = "";
        String body = "";
        String detail = "";
        String line = "还没巡检过";
        long leftMin = -1;     // 距预计释放还剩几分钟（-1 = 不知道）
        long nextAt;           // 下一次巡检的绝对时间；0 = 收工
    }

    /** 一次「暂离」过程中的状态：用户答没答、催了几次、从什么时候离座。存普通 prefs。 */
    static final class Away {
        String id = "";
        long since;            // 离座时刻（毫秒）：优先服务端给的，其次门禁记录，最后第一次观测
        long askedAt;          // 上一次询问的时刻
        int asked;
        String decision = "";  // "" 没答 / "yes" 立刻签 / "no" 用户说别管

        static Away load(Context c) {
            Away a = new Away();
            android.content.SharedPreferences p =
                    c.getSharedPreferences("zw_flags", Context.MODE_PRIVATE);
            a.id = p.getString("ci_away_id", "");
            a.since = p.getLong("ci_away_since", 0);
            a.askedAt = p.getLong("ci_away_asked_at", 0);
            a.asked = p.getInt("ci_away_asked", 0);
            a.decision = p.getString("ci_away_decision", "");
            return a;
        }

        void save(Context c) {
            c.getSharedPreferences("zw_flags", Context.MODE_PRIVATE).edit()
                    .putString("ci_away_id", id)
                    .putLong("ci_away_since", since)
                    .putLong("ci_away_asked_at", askedAt)
                    .putInt("ci_away_asked", asked)
                    .putString("ci_away_decision", decision)
                    .apply();
        }

        static void reset(Context c) {
            c.getSharedPreferences("zw_flags", Context.MODE_PRIVATE).edit()
                    .remove("ci_away_id").remove("ci_away_since").remove("ci_away_asked_at")
                    .remove("ci_away_asked").remove("ci_away_decision").apply();
        }
    }

    /** 站点自己的 status 文案（bundle 里 my.table1.status1..8）。 */
    static String stateText(String state) {
        String s = state == null ? "" : state.trim().toUpperCase(Locale.US);
        if ("RESERVE".equals(s)) {
            return "预约（未签到）";
        }
        if ("CHECK_IN".equals(s)) {
            return "履约中（已签到）";
        }
        if ("AWAY".equals(s)) {
            return "暂离";
        }
        if ("LEAVE_EARLY".equals(s)) {
            return "早退";
        }
        if ("STOP".equals(s)) {
            return "已结束";
        }
        if ("NO_STOP".equals(s)) {
            return "未签退";
        }
        if ("MISS".equals(s)) {
            return "失约";
        }
        if ("CANCEL".equals(s)) {
            return "已取消";
        }
        return s.isEmpty() ? "状态未知" : s;
    }

    /**
     * Pure. /user/currentUseMake → 当前有效预约。
     * 字段名沿用站点自己的 currentBook（与 freeBook 的 orderObj 同一套）。
     * "没有有效预约"时服务端给 data:{}，这里当没有处理。
     */
    static Now parseNow(JSONObject resp) {
        Now n = new Now();
        if (resp == null || !resp.optBoolean("status")) {
            return n;
        }
        JSONObject d = resp.optJSONObject("data");
        if (d == null || d.length() == 0) {
            return n;
        }
        String state = firstStr(d, "status", "makeStatus", "state");
        String id = firstStr(d, "id", "makeId");
        if (state == null && id == null) {
            return n;
        }
        n.has = true;
        n.state = state == null ? "" : state.trim().toUpperCase(Locale.US);
        n.id = id;
        n.date = firstStr(d, "makeDateStr", "date");
        n.begin = firstStr(d, "makeBeginStr", "beginTime");
        n.end = firstStr(d, "makeEndStr", "endTime");
        n.place = firstStr(d, "location", "locationE");
        n.seat = firstStr(d, "seatLabel");
        n.awayRange = firstStr(d, "awayRange");
        return n;
    }

    /** Pure. "12:05" → 725；解析不出来返回 -1。"12:05:00" 也收。 */
    static int minuteText(String hhmm) {
        if (hhmm == null) {
            return -1;
        }
        String t = hhmm.trim();
        int c = t.indexOf(':');
        if (c <= 0 || c + 1 >= t.length()) {
            return -1;
        }
        String rest = t.substring(c + 1).trim();
        int c2 = rest.indexOf(':');
        if (c2 >= 0) {
            rest = rest.substring(0, c2).trim();
        }
        try {
            int h = Integer.parseInt(t.substring(0, c).trim());
            int m = Integer.parseInt(rest);
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return -1;
            }
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Pure. 记录的结束时刻（毫秒）；解析不出来返回 -1。 */
    static long endAtMillis(String date, String end) {
        if (date == null || end == null) {
            return -1;
        }
        String t = end.trim();
        if (t.length() == 5) {
            t = t + ":00";
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .parse(date.trim() + " " + t).getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Pure. 服务端的 token 失效码（探活与状态查询共用同一套判定）。 */
    static boolean tokenGone(JSONObject resp) {
        if (resp == null || resp.optBoolean("status")) {
            return false;
        }
        String s = resp.toString();
        return s.contains("20003") || s.contains("20002");
    }

    /** Pure. 宽松解析 {code:200,data:[...]} 或 {status:true,data:[...]} 两种外壳。 */
    static JSONArray dataList(JSONObject resp) {
        if (resp == null) {
            return null;
        }
        if (!resp.optBoolean("status") && resp.optInt("code", 0) != 200) {
            return null;
        }
        Object d = resp.opt("data");
        return d instanceof JSONArray ? (JSONArray) d : null;
    }

    /** Pure. 门禁记录的 direction：0=入馆，其余当离馆。 */
    static boolean isLeave(String direction) {
        if (direction == null) {
            return false;
        }
        String s = direction.trim();
        return "1".equals(s) || s.contains("离");
    }

    /** Pure. 宽松解析日期时间；只有时间时用 fallbackDate（yyyy-MM-dd）补日期。拿不到返回 -1。 */
    static long parseDateTime(String s, String fallbackDate) {
        if (s == null) {
            return -1;
        }
        String t = s.trim().replace('T', ' ');
        if (t.isEmpty()) {
            return -1;
        }
        String full = t.indexOf(' ') > 0 ? t
                : (fallbackDate == null ? null : fallbackDate.trim() + " " + t);
        if (full == null) {
            return -1;
        }
        String[] pats = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"};
        for (String p : pats) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setLenient(false);
                return f.parse(full).getTime();
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    /** Pure. 从 awayRange 取暂离时刻；多段时取最后一段。拿不到返回 -1。 */
    static long awayStartMillis(String awayRange, String date) {
        if (awayRange == null) {
            return -1;
        }
        String s = awayRange.trim();
        // 同一天可能有多次暂离（"11:00~11:20,14:38~"）：只认最后一段
        int comma = s.lastIndexOf(',');
        if (comma >= 0) {
            s = s.substring(comma + 1).trim();
        }
        int cut = s.indexOf('~');
        if (cut < 0) {
            cut = s.indexOf('_');
        }
        if (cut > 0) {
            s = s.substring(0, cut);
        }
        return parseDateTime(s, date);
    }

    /** Pure. 拿不到离座时刻时的估算：观测时刻往前推一个巡检间隔（宁可早算，别晚算）。 */
    static long fallbackSince(long observedMs) {
        return observedMs - Scheduler.WATCH_EVERY_MIN * 60000L;
    }

    /** Pure. 登录态失效后多久再试：窗口内 30 分钟一次，出了窗口就收工。 */
    static int stallDelayMin(int nowMin, int beginMinute, int endMinute) {
        int d = nextTickDelayMin(nowMin, beginMinute, endMinute, false, -1, false);
        return d < 0 ? -1 : Math.max(d, Scheduler.WATCH_RETRY_MIN);
    }

    /** Pure. 门禁记录里最后一次「离馆」的时刻（毫秒）；拿不到返回 -1。 */
    static long lastLeaveIn(JSONObject resp, String date) {
        JSONArray a = dataList(resp);
        if (a == null) {
            return -1;
        }
        long best = -1;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null || !isLeave(firstStr(r, "direction", "directionStr", "inOut"))) {
                continue;
            }
            long ts = parseDateTime(firstStr(r, "dateTimeStr", "dateTime", "time",
                    "accessTime", "createDate"), date);
            if (ts > best) {
                best = ts;
            }
        }
        return best;
    }

    /** 门禁记录里最后一次离馆时刻；网络失败返回 -1（只是兜底，失败不影响主流程）。 */
    static long lastLeaveAt(String token, Net.Jar jar, String date, StringBuilder log) {
        JSONObject r = post(DOOR_LOG + date, token, "{}", jar, log);
        return lastLeaveIn(r, date);
    }

    /**
     * Pure. 变更记录里最近一次「暂离」的时刻（毫秒）；拿不到返回 -1。
     * /user/makeLife/{id} 的每项有 stage / stageName / createdDate，
     * 这是服务端自己记的暂离时间，比门禁反推更准。
     */
    static long lastAwayIn(JSONObject resp, String date) {
        JSONArray a = dataList(resp);
        if (a == null) {
            return -1;
        }
        long best = -1;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null) {
                continue;
            }
            String name = firstStr(r, "stageName");
            String code = firstStr(r, "stage");
            String up = code == null ? "" : code.toUpperCase(Locale.US);
            boolean awayStage = "AWAY".equals(up)
                    || (name != null && name.contains("暂离") && !"LEAVE_EARLY".equals(up));
            if (!awayStage) {
                continue;
            }
            long ts = parseDateTime(firstStr(r, "createdDate", "createTime", "date"), date);
            if (ts > best) {
                best = ts;
            }
        }
        return best;
    }

    /** 变更记录里最近一次暂离时刻；拿不到返回 -1。 */
    static long lastAwayAt(String token, Net.Jar jar, String id, String date, StringBuilder log) {
        JSONObject r = post("/static/frontApi/user/makeLife/" + id, token, "{}", jar, log);
        return lastAwayIn(r, date);
    }

    /**
     * Pure. 这次暂离给多少宽限（分钟）。
     * 图书馆的规矩：平时离座 1 小时，饭点（默认 11:00-13:30）2 小时，超时座位被释放。
     */
    static int graceMin(int awayMin, int mealStart, int mealEnd, int mealGrace, int normGrace) {
        if (awayMin >= 0 && mealStart >= 0 && mealEnd > mealStart
                && awayMin >= mealStart && awayMin < mealEnd) {
            return mealGrace;
        }
        return normGrace;
    }
    /** 暂离后返回 —— 站点 PC 页面点「返回」走的就是这个接口。 */
    static JSONObject checkIn(String token, Net.Jar jar, StringBuilder log) {
        return post(CHECK_IN, token, "{}", jar, log);
    }

    /**
     * Pure. 下一次巡检该等几分钟；-1 = 收工。
     *
     * 时段内有预约 → 每 5 分钟；暂离中 → 每 2 分钟（要盯着释放时间）；
     * 时段开始前 → 睡到提前量；出了时段尾巴 → 停。
     * 记录自己声明还没结束时（recordEndMin 有效）多盯到记录结束 + 30 分钟。
     */
    static int nextTickDelayMin(int nowMin, int beginMinute, int endMinute,
                                boolean hasTodayBooking, int recordEndMin, boolean away) {
        boolean hasWindow = beginMinute >= 0 && endMinute > beginMinute;
        int winStart = hasWindow ? beginMinute - Scheduler.WATCH_LEAD_MIN : -1;
        int limit = hasWindow ? endMinute + Scheduler.WATCH_TAIL_MIN : -1;
        if (hasTodayBooking && recordEndMin >= 0) {
            limit = Math.max(limit, recordEndMin + 30);
        }
        if (limit < 0) {
            // 没配时段：只有"今天确实有预约"才值得盯
            limit = hasTodayBooking ? 24 * 60 - 1 : -1;
        }
        if (limit < 0 || nowMin >= limit) {
            return -1;
        }
        if (winStart >= 0 && nowMin < winStart) {
            return winStart - nowMin;
        }
        int every = away ? Scheduler.WATCH_AWAY_MIN : Scheduler.WATCH_EVERY_MIN;
        return Math.max(1, Math.min(every, limit - nowMin));
    }

    private static long nextAt(long nowMs, int nowMin, Cfg cfg, boolean todayBooking,
                               int recordEndMin, boolean away) {
        int delay = nextTickDelayMin(nowMin, cfg.ciBeginMinute, cfg.ciEndMinute,
                todayBooking, recordEndMin, away);
        return delay < 0 ? 0 : nowMs + delay * 60000L;
    }

    /**
     * 登录态失效：守护暂时停摆。窗口内每 30 分钟自己再试一次（cookie 被前台刷新了就能自愈），
     * 出了窗口就收工，等第二天重新武装。
     */
    private static Tick stall(Tick t, StringBuilder log, long nowMs, int nowMin, Cfg cfg, String line) {
        t.notify = true;
        t.kind = "stopped";
        t.cancelAsk = true;
        t.title = "守护暂时停了：登录态失效";
        t.body = "座位 token 没了，7 天免登录也换不回来。\n打开 App 登录一次就能恢复；"
                + "在那之前守护每 " + Scheduler.WATCH_RETRY_MIN + " 分钟自己再试一次。";
        t.line = line;
        t.detail = t.body + "\n\n—— 巡检日志 ——\n" + log;
        int delay = stallDelayMin(nowMin, cfg.ciBeginMinute, cfg.ciEndMinute);
        t.nextAt = delay < 0 ? 0 : nowMs + delay * 60000L;
        return t;
    }

    static final int WATCH_BG = 0;       // 后台巡检：按策略问 / 催 / 到点替签
    static final int WATCH_MANUAL = 1;   // 手动看一眼：只读，不改任何状态
    static final int WATCH_REPAIR = 2;   // 用户在通知里点了「帮我签到」：立刻签

    /**
     * 巡检一次。暂离时的策略：
     *   1 先问 —— 通知里给「帮我签到」/「不用，我自己回来」两个按钮；
     *   2 没回应就每 10 分钟再问一次；
     *   3 一直没回应 → 释放前 10 分钟替用户签（WATCH_REPAIR 是用户自己点的立刻签）。
     * 只读 + 最多一次写，不并发、不重试风暴，跑在 WatchReceiver 的线程里。
     */
    static Tick watchTick(Context ctx, SecureStore sec, Cfg cfg, boolean foreground, int mode) {
        Tick t = new Tick();
        StringBuilder log = new StringBuilder();
        long nowMs = System.currentTimeMillis();
        int nowMin = minuteOfDay(new java.util.Date(nowMs));
        String session = sec.get("session");
        try {
            Net.Jar jar = loadJar(sec);
            primeJar(jar, foreground);
            installSigner(session, jar, log);

            String token = sessionToken(session);
            if (token == null) {
                token = ensureToken(ctx, sec, jar, session, log);
            }
            if (token == null) {
                return stall(t, log, nowMs, nowMin, cfg, "登录态失效，守护暂停");
            }
            if (foreground) {
                saveJar(sec, jar);
            }

            JSONObject resp = post(CUR_MAKE, token, "{}", jar, log);
            if (resp == null || tokenGone(resp)) {
                log.append("· token 可能已失效，换新后重试…\n");
                token = ensureToken(ctx, sec, jar, session, log);
                if (token == null) {
                    return stall(t, log, nowMs, nowMin, cfg, "登录态失效，守护暂停");
                }
                saveJar(sec, jar);
                resp = post(CUR_MAKE, token, "{}", jar, log);
            }
            if (resp == null) {
                t.line = hhmm(nowMin) + " 查询失败（HTTP " + Net.lastHttp + "）";
                t.detail = log.toString();
                t.nextAt = nextAt(nowMs, nowMin, cfg, false, -1, false);
                return t;
            }

            Now n = parseNow(resp);
            t.ok = true;
            log.append("· 状态: ").append(n.has ? stateText(n.state) : "没有有效预约").append('\n');
            boolean todayBooking = n.has && (n.date == null || today().equals(n.date.trim()));
            int recordEndMin = todayBooking ? minuteText(n.end) : -1;
            boolean away = todayBooking && ST_AWAY.equals(n.state);

            if (away) {
                t.awayActive = true;
                String key = n.id == null ? "" : n.id;
                Away a = Away.load(ctx);
                if (!key.equals(a.id)) {
                    a = new Away();                       // 换了一条预约：重新开始
                    a.id = key;
                }
                long since = a.since;
                if (since <= 0) {
                    since = awaySince(jar, n, token, log);
                    if (since > 0) {
                        a.since = since;                 // 只有真拿到才落盘，拿不到下一轮再找
                    } else {
                        since = fallbackSince(nowMs);    // 宁可早算，别晚算
                        log.append("· 拿不到离座时刻，先按「第一次看到暂离再往前 ")
                                .append(Scheduler.WATCH_EVERY_MIN).append(" 分钟」估算，下一轮继续找\n");
                    }
                }
                int awayMin = minuteOfDay(new java.util.Date(since));
                int grace = graceMin(awayMin, cfg.ciMealStartMin, cfg.ciMealEndMin,
                        cfg.ciMealGraceMin, cfg.ciGraceMin);
                long deadline = since + grace * 60000L;
                long autoAt = deadline - Scheduler.AUTO_LEAD_MIN * 60000L;
                long leftMin = Math.max(0, (deadline - nowMs + 59999L) / 60000L);
                t.leftMin = leftMin;
                log.append("· 离座 ").append(hhmm(awayMin)).append("，宽限 ").append(grace)
                        .append(" 分钟 → 预计 ")
                        .append(hhmm(minuteOfDay(new java.util.Date(deadline))))
                        .append(" 释放（还剩约 ").append(leftMin).append(" 分钟）\n");

                if (mode == WATCH_MANUAL) {
                    t.kind = "away_peek";
                    t.line = hhmm(nowMin) + " 暂离 · 距释放约 " + leftMin + " 分钟";
                    log.append("· 手动查看：不改变任何状态\n");
                } else if ("no".equals(a.decision) && mode != WATCH_REPAIR) {
                    t.kind = "away_declined";
                    t.line = hhmm(nowMin) + " 暂离（你选了不用）· 距释放约 " + leftMin + " 分钟";
                } else {
                    long recordEndMs = endAtMillis(n.date, n.end);
                    boolean bookingOver = recordEndMs > 0 && nowMs > recordEndMs + 5 * 60000L;
                    boolean userSaidYes = "yes".equals(a.decision);
                    boolean lastResort = nowMs >= autoAt && !bookingOver;
                    if (mode == WATCH_REPAIR || userSaidYes || lastResort) {
                        boolean justTried = a.askedAt > 0
                                && nowMs - a.askedAt < Scheduler.ASK_EVERY_MIN * 60000L;
                        if (justTried && mode != WATCH_REPAIR) {
                            t.kind = "away_yes";          // 刚试过，下一轮再重试
                            t.line = hhmm(nowMin) + " 暂离（已点过帮我签到，等待重试）";
                        } else {
                            a.decision = "yes";
                            a.askedAt = nowMs;
                            t.cancelAsk = true;
                            repair(sec, cfg, t, jar, token, n, log, nowMs, mode == WATCH_REPAIR);
                        }
                    } else {
                        boolean due = a.askedAt <= 0
                                || nowMs - a.askedAt >= Scheduler.ASK_EVERY_MIN * 60000L;
                        if (due) {
                            a.askedAt = nowMs;
                            a.asked++;
                            t.ask = true;
                            t.notify = true;
                            t.title = "座位暂离 · 要我帮你签到吗？";
                            t.body = awayBody(n, since, grace, deadline, leftMin);
                        }
                        t.kind = "away_ask";
                        t.line = hhmm(nowMin) + " 暂离 · 已提醒 " + a.asked + " 次 · 距释放约 "
                                + leftMin + " 分钟";
                    }
                }
                if (mode != WATCH_MANUAL) {
                    a.save(ctx);
                }
            } else {
                if (mode != WATCH_MANUAL) {
                    Away.reset(ctx);
                }
                t.cancelAsk = true;
                if (!n.has) {
                    t.line = hhmm(nowMin) + " 没有有效预约";
                } else {
                    t.kind = "ok";
                    t.line = hhmm(nowMin) + " " + stateText(n.state)
                            + (n.seat == null ? "" : " " + n.seat);
                }
                if (mode == WATCH_REPAIR) {
                    // 用户点了「帮我签到」，但此刻已经不在暂离（可能自己刷回来了）
                    t.notify = true;
                    t.kind = "repair_noop";
                    t.title = "现在不用签到";
                    t.body = n.has ? "当前状态：" + stateText(n.state) : "当前没有有效预约";
                }
            }
            t.detail = n.text() + "\n\n—— 巡检日志 ——\n" + log;
            t.nextAt = nextAt(nowMs, nowMin, cfg, todayBooking, recordEndMin, away);
            return t;
        } catch (Throwable e) {
            t.line = "巡检异常：" + e;
            t.detail = log + "\n" + e;
            t.nextAt = nextAt(nowMs, nowMin, cfg, false, -1, false);
            return t;
        }
    }

    /**
     * 这次暂离从什么时候开始，按可信度依次找：
     * 记录里的 awayRange → 变更记录(makeLife) → 门禁最后一次离馆。
     * 都拿不到返回 -1，由调用方按「观测时刻往前推」兜底（下一轮还会再找）。
     */
    private static long awaySince(Net.Jar jar, Now n, String token, StringBuilder log) {
        String date = n.date == null ? today() : n.date;
        long best = awayStartMillis(n.awayRange, date);
        String src = best > 0 ? "记录 awayRange" : null;
        if (best < 0 && n.id != null) {
            long life = lastAwayAt(token, jar, n.id, date, log);
            if (life > 0) {
                best = life;
                src = "变更记录";
            }
        }
        if (best < 0) {
            long door = lastLeaveAt(token, jar, date, log);
            if (door > 0) {
                best = door;
                src = "门禁记录";
            }
        }
        if (best > 0) {
            log.append("· 离座 ").append(hhmm(minuteOfDay(new java.util.Date(best))))
                    .append("（来源：").append(src).append("）\n");
        }
        return best;
    }

    /** 真的调一次「返回」并复查；试运行开着时只记录。 */
    private static void repair(SecureStore sec, Cfg cfg, Tick t, Net.Jar jar, String token,
                               Now n, StringBuilder log, long nowMs, boolean byUser) {
        String at = hhmm(minuteOfDay(new java.util.Date(nowMs)));
        if (cfg.ciDry) {
            t.notify = true;
            t.kind = "away_dry";
            t.title = "发现暂离（试运行，未调返回）";
            t.body = n.text() + "\n\n试运行开着：只记录，没有真的调返回接口。";
            t.line = at + " 暂离（试运行，未调返回）";
            log.append("· 试运行开着：没有真的调返回接口\n");
            return;
        }
        log.append(byUser ? "· 你在通知里点了「帮我签到」，调返回接口…\n"
                : "· 临近释放，替用户调返回接口…\n");
        JSONObject r = checkIn(token, jar, log);
        boolean fixed = r != null && r.optBoolean("status");
        saveJar(sec, jar);
        t.notify = true;
        if (fixed) {
            Now after = parseNow(post(CUR_MAKE, token, "{}", jar, null));
            t.kind = "away_fixed";
            t.title = "已帮你签到：暂离 → "
                    + (after.has ? stateText(after.state) : "复查已无有效预约");
            t.body = n.text() + "\n\n闸机漏记了入馆才会这样；如果经常发生，记得找回馆门禁补刷一次。";
            t.line = at + " 暂离 → 已签到";
        } else {
            String m = r == null ? "请求没拿到有效响应" : r.optString("message");
            t.kind = "away_failed";
            t.title = "签到失败";
            t.body = "服务端拒绝：" + m + "\n" + n.text();
            t.line = at + " 暂离 → 签到失败：" + m;
        }
    }

    private static String awayBody(Now n, long since, int grace, long deadline, long leftMin) {
        StringBuilder b = new StringBuilder();
        b.append(hhmm(minuteOfDay(new java.util.Date(since)))).append(" 离座 · 宽限 ")
                .append(grace).append(" 分钟\n预计 ")
                .append(hhmm(minuteOfDay(new java.util.Date(deadline))))
                .append(" 释放（还剩约 ").append(leftMin).append(" 分钟）\n\n")
                .append("点「帮我签到」= 现在就替你签\n")
                .append("点「不用」= 不再打扰，也不自动签\n")
                .append("不回应 = 每 ").append(Scheduler.ASK_EVERY_MIN).append(" 分钟提醒一次，")
                .append("释放前 ").append(Scheduler.AUTO_LEAD_MIN).append(" 分钟自动替你签\n\n");
        if (n.seat != null) {
            b.append("座位 ").append(n.seat).append(" · ");
        }
        if (n.place != null) {
            b.append(n.place.replace('|', ' ').trim());
        }
        return b.toString();
    }

    /* ------------------------------------------------------------------ */
    /* 手动签到：点一下就签                                                  */
    /* ------------------------------------------------------------------ */

    static final int SIGN_NONE = 0;   // 没有有效预约
    static final int SIGN_DONE = 1;   // 已经履约中，不用签
    static final int SIGN_DEAD = 2;   // 记录已经结束（早退/已结束/未签退/失约/已取消）
    static final int SIGN_GO = 3;     // 值得调一次签到接口

    /** Pure. 手动点「立即签到」时，这个状态要不要真的调接口。 */
    static int signPlan(Now n) {
        if (n == null || !n.has) {
            return SIGN_NONE;
        }
        String s = n.state == null ? "" : n.state.trim().toUpperCase(Locale.US);
        if ("CHECK_IN".equals(s)) {
            return SIGN_DONE;
        }
        if ("LEAVE_EARLY".equals(s) || "STOP".equals(s) || "NO_STOP".equals(s)
                || "MISS".equals(s) || "CANCEL".equals(s)) {
            return SIGN_DEAD;
        }
        return SIGN_GO;   // RESERVE / AWAY / 没见过的状态：试一次，以服务端返回为准
    }

    /** 一次手动签到的结果：给「立即签到」的对话框用。 */
    static final class Sign {
        boolean ok;        // 这一下做成了（或本来就不需要做）
        String title = "立即签到";
        String body = "";
        String line = "";  // 一行摘要，写回 ci_last
        String detail = "";
    }

    /**
     * 手动签到：把当前有效预约立刻签掉。已经履约中 / 记录已结束 / 没有有效预约
     * 就不调接口，只如实报告；其余（暂离、预约未签到、没见过的状态）都试一次，
     * 以服务端返回为准。用户自己点的这条路不看守护开关与试运行，且只调一次、不重试。
     */
    static Sign signNow(Context ctx, SecureStore sec, boolean foreground) {
        Sign s = new Sign();
        StringBuilder log = new StringBuilder();
        String at = hhmm(minuteOfDay(new java.util.Date()));
        try {
            Net.Jar jar = loadJar(sec);
            primeJar(jar, foreground);
            String session = sec.get("session");
            installSigner(session, jar, log);

            String token = sessionToken(session);
            if (token == null) {
                token = ensureToken(ctx, sec, jar, session, log);
            }
            if (token != null && foreground) {
                saveJar(sec, jar);
            }

            JSONObject resp = token == null ? null : post(CUR_MAKE, token, "{}", jar, log);
            if (token != null && (resp == null || tokenGone(resp))) {
                log.append("· token 可能已失效，换新后重试…\n");
                token = ensureToken(ctx, sec, jar, session, log);
                if (token != null) {
                    saveJar(sec, jar);
                    resp = post(CUR_MAKE, token, "{}", jar, log);
                }
            }
            if (token == null) {
                s.title = "读不到登录态";
                s.body = "先在页面里登录一次（7 天免登录也换不回 token）。";
            } else if (resp == null) {
                s.title = "查询失败";
                s.body = "拿不到当前预约状态（HTTP " + Net.lastHttp + "）。";
            } else {
                Now n = parseNow(resp);
                int plan = signPlan(n);
                log.append("· 点之前: ").append(n.has ? stateText(n.state) : "没有有效预约").append('\n');

                if (plan == SIGN_NONE) {
                    s.ok = true;
                    s.title = "此刻没有要签的预约";
                    s.body = "服务端说此刻没有有效预约 —— 没有可签的。\n"
                            + "（刚下单的话，过一分钟再点一次。）";
                    s.line = at + " 手动签到：没有有效预约";
                } else if (plan == SIGN_DONE) {
                    s.ok = true;
                    s.title = "已经是履约中（已签到）";
                    s.body = "没有重复调接口。\n\n" + n.text();
                    s.line = at + " 手动签到：已经签过了";
                } else if (plan == SIGN_DEAD) {
                    s.title = "记录已经结束（" + stateText(n.state) + "）";
                    s.body = "签到接口对它没有意义，没调。\n\n" + n.text();
                    s.line = at + " 手动签到：记录已结束（" + stateText(n.state) + "）";
                } else {
                    String before = stateText(n.state);
                    log.append("· 调签到接口（qrMd5=PC）…\n");
                    JSONObject r = checkIn(token, jar, log);
                    saveJar(sec, jar);
                    if (r != null && r.optBoolean("status")) {
                        Now after = parseNow(post(CUR_MAKE, token, "{}", jar, null));
                        String to = after.has ? stateText(after.state) : "复查已无有效预约";
                        s.ok = true;
                        s.title = "已签到：" + before + " → " + to;
                        s.body = n.text() + "\n\n服务端已受理，复查：" + to + "\n";
                        s.line = at + " 手动签到：" + before + " → " + to;
                    } else {
                        String m = r == null ? "请求没拿到有效响应" : r.optString("message");
                        s.title = "签到失败";
                        s.body = "服务端拒绝：" + m + "\n\n" + n.text();
                        s.line = at + " 手动签到失败：" + m;
                        log.append("· 签到被拒: ").append(m).append('\n');
                    }
                }
            }
        } catch (Throwable e) {
            s.title = "签到异常";
            s.body = String.valueOf(e);
            log.append("· 异常: ").append(e).append('\n');
        }
        if (s.line.isEmpty()) {
            s.line = at + " 手动签到失败：" + s.title;
        }
        s.detail = s.body + "\n\n" + state(sec) + "—— 日志 ——\n" + log;
        return s;
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

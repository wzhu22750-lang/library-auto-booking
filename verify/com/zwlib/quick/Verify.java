package com.zwlib.quick;

import org.json.JSONObject;

import java.util.List;

/**
 * Seam tests for the reservation logic. Expected values come from independent
 * sources: the site's own crypto-js (run in node), headers captured off the real
 * server, and hand-computed fixtures whose field names are copied from the
 * bundle's templates.
 */
public class Verify {

    static int pass = 0;
    static int fail = 0;

    static void eq(String name, Object actual, Object expected) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name
                    + "\n          expected: " + expected
                    + "\n          actual:   " + actual);
        }
    }

    static void ok(String name, boolean cond) {
        eq(name, cond, true);
    }

    // ---- fixtures -------------------------------------------------------
    // Field names copied from the bundle:
    //   buildList        <- data.buildings[].id / .name / .floors[].id / .name
    //   customDates      <- data.dates  (array of "yyyy-MM-dd" strings)
    //   e.dataList       <- data.data.pageList[] : id, name, seatFree, seatTotal
    //   freeSeatIds data : OBJECT keyed by seat id  (reRender does t[i.seat.id])
    static final String VENUES = "{\"status\":true,\"code\":200,\"data\":{"
            + "\"buildings\":["
            + "{\"id\":11,\"name\":\"图书馆\",\"seTime\":\"08:00_22:00\",\"floors\":["
            + "{\"id\":111,\"name\":\"三层\"},{\"id\":112,\"name\":\"四层\"}]},"
            + "{\"id\":22,\"nameE\":\"Archives\",\"floors\":[{\"id\":221,\"name\":\"一层\"}]}"
            + "],\"dates\":[\"2026-09-15\",\"2026-09-16\"]}}";

    static final String ROOMS = "{\"status\":true,\"data\":{\"totalCount\":2,\"pageList\":["
            + "{\"id\":9001,\"name\":\"三层A区\",\"buildingName\":\"图书馆\",\"floorName\":\"三层\","
            + "\"seatTotal\":\"120\",\"seatFree\":\"7\"},"
            + "{\"id\":9002,\"name\":\"三层B区\",\"seatFree\":0}]}}";

    static JSONObject j(String s) {
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** /user/currentUseMake 的一条记录，只关心 status。 */
    static Booker.Now nowWith(String state) {
        return Booker.parseNow(j("{\"status\":true,\"data\":{\"id\":\"1878649390807224320\","
                + "\"status\":\"" + state + "\",\"makeDateStr\":\"2026-09-25\","
                + "\"makeBeginStr\":\"10:00\",\"makeEndStr\":\"12:00\"}}"));
    }

    static String fixture(String name) {
        try {
            String dir = System.getProperty("fixtures", "verify/fixtures");
            java.nio.file.Path p = java.nio.file.Paths.get(dir, name);
            return new String(java.nio.file.Files.readAllBytes(p), "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException("读不到 fixture " + name + ": " + e);
        }
    }

    public static void main(String[] args) throws Exception {
        crypto();
        realCapture();
        lastMake();
        cookies();
        urls();
        venuesParsing();
        roomsParsing();
        freeSeatsParsing();
        windowRequired();
        freeSeatFiltering();
        seatPriority();
        datesAndFormat();
        checkInWatch();
        checkInIndependence();

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) {
            System.exit(1);
        }
    }

    /* ---------------- 1. crypto: oracle = the site's own crypto-js ------------- */
    static void crypto() {
        System.out.println("\n[1] 签名与密钥解包（oracle: 站点 crypto-js 原文）");
        eq("unwrapKey(真实 hmacKey) == ruc2024lib",
                Net.unwrapKey("vECLlcxq3mdtoIOCKdF/Gg=="), "ruc2024lib");

        String rid = "11111111-2222-4333-8444-555555555555";
        long ts = 1757856123456L;
        eq("hmacSignature POST 与 crypto-js 完全一致",
                Net.hmacSignature("ruc2024lib", rid, ts, "POST"),
                "37b92edd6c13ef79b40668dbacfc3dc7b5f4f7bf46246b77516da9fc9820affb");
        eq("方法名大小写不影响签名",
                Net.hmacSignature("ruc2024lib", rid, ts, "post"),
                "37b92edd6c13ef79b40668dbacfc3dc7b5f4f7bf46246b77516da9fc9820affb");
        ok("不同方法产生不同签名",
                !"37b92edd6c13ef79b40668dbacfc3dc7b5f4f7bf46246b77516da9fc9820affb"
                        .equals(Net.hmacSignature("ruc2024lib", rid, ts, "GET")));
        ok("错误密钥产生不同签名",
                !"37b92edd6c13ef79b40668dbacfc3dc7b5f4f7bf46246b77516da9fc9820affb"
                        .equals(Net.hmacSignature("wrong", rid, ts, "POST")));
        eq("坏 base64 不抛异常", Net.unwrapKey("!!!not-base64!!!"), null);
    }

    /* ---------------- 1b. lastMake: 取消预约需要的 id --------------------------- */
    // 字段抄自 cancelBook(e.row) 里的 t.id 和模板里的 e.row.status
    static void lastMake() {
        System.out.println("\n[1b] /user/lastMake 解析（取消按钮要用的 id）");
        eq("优先挑 status=RESERVE 的可取消预约",
                Booker.parseLastMakeId(j("{\"status\":true,\"data\":["
                        + "{\"id\":501,\"status\":\"CHECK_IN\"},"
                        + "{\"id\":502,\"status\":\"RESERVE\"}]}")), "502");
        eq("只有已签到的记录时退回第一条",
                Booker.parseLastMakeId(j("{\"status\":true,\"data\":[{\"id\":601,\"status\":\"CHECK_IN\"}]}")),
                "601");
        eq("data 不是数组时返回 null",
                Booker.parseLastMakeId(j("{\"status\":true,\"data\":{}}")), null);
        eq("status=false 返回 null",
                Booker.parseLastMakeId(j("{\"status\":false,\"message\":\"x\"}")), null);
        eq("null 不崩", Booker.parseLastMakeId(null), null);
    }

    /* ---------------- 1c. 真实抓包回归（最硬的 oracle：线上服务器自己的响应） -------- */
    static void realCapture() {
        System.out.println("\n[1c] 真实抓包回归（fixture 来自线上实际响应）");
        JSONObject v = j(fixture("buildingFloorDate.json"));

        List<Booker.Item> bl = Booker.buildings(v);
        eq("线上馆数量 = 2", bl.size(), 2);
        eq("馆 id（19 位字符串，不能被转成科学计数法）", bl.get(0).id, "1875080631899230208");
        eq("馆名", bl.get(0).label, "图书馆");
        eq("第二馆", bl.get(1).label, "藏书馆");

        List<String> dates = Booker.bookableDates(v);
        eq("线上可预约日期", dates.toString(), "[2026-09-14, 2026-09-15]");
        eq("index 0 是今天", Booker.pickDate(dates, 0, "x"), "2026-09-14");
        eq("index 1 是明天", Booker.pickDate(dates, 1, "x"), "2026-09-15");
        ok("楼层列表已展开（含「全部楼层」）", Booker.floors(v, bl.get(0).id).size() > 1);

        List<Booker.Item> rooms = Booker.parseRooms(j(fixture("findRoomDuration.json")));
        eq("线上座位区数量", rooms.size(), 4);
        eq("座位区 id（19 位字符串保真）", rooms.get(0).id, "1878649390807224320");
        eq("座位区名", rooms.get(0).label, "B1自习区");
        eq("seatFree = 41", rooms.get(0).free, 41);
        eq("第二个座位区 seatFree = 20", rooms.get(1).free, 20);
        ok("按 seatFree 排序后 B1自习区(41) 在 1F外文二区(16) 前面",
                rooms.get(0).free > rooms.get(2).free);

        List<Booker.Seat> seats = Booker.parseSeats(j(fixture("freeSeatIdsDuration.json")));
        eq("线上空座数量", seats.size(), 3);
        eq("取最小 id，结果确定（不受 org.json 的 hash 顺序影响）",
                seats.get(0).id, "1878687954123198560");
        eq("带出人类可读的座位号 label", seats.get(0).label, "B102");
    }

    /* ---------------- 2. cookie jar: real captured headers --------------------- */
    static void cookies() {
        System.out.println("\n[2] Cookie jar（fixture: 从真实服务器抓到的 Set-Cookie）");
        Net.Jar jar = new Net.Jar();
        // captured from https://zwlib.ruc.edu.cn/rem/static/sso/login
        jar.absorb("https://zwlib.ruc.edu.cn/rem/static/sso/login",
                "rem_JSESSIONID=D40F59B30B016BAB108D9129245C922D; Path=/rem; HttpOnly");
        eq("落库的是 name=value，不带 Path/HttpOnly",
                jar.header("https://zwlib.ruc.edu.cn/jsq/static/x"),
                "rem_JSESSIONID=D40F59B30B016BAB108D9129245C922D");

        jar.absorb("https://cas.ruc.edu.cn/cas/login", "CASTGC=TGT-12345; Path=/cas; Secure");
        eq("CASTGC 归到 cas.ruc.edu.cn",
                jar.header("https://cas.ruc.edu.cn/cas/login"), "CASTGC=TGT-12345");
        eq("不同 host 互不污染",
                jar.header("https://zwlib.ruc.edu.cn/jsq/static/x"),
                "rem_JSESSIONID=D40F59B30B016BAB108D9129245C922D");
        eq("没见过的 host 返回 null", jar.header("https://example.com/x"), null);

        Net.Jar back = Net.Jar.fromJson(jar.toJson());
        eq("序列化往返不丢 cookie",
                back.header("https://cas.ruc.edu.cn/cas/login"), "CASTGC=TGT-12345");
        eq("空 jar 序列化后仍为空", Net.Jar.fromJson("{}").header("https://a.b/c"), null);
    }

    /* ---------------- 3. token extraction from the SSO redirect --------------- */
    static void urls() {
        System.out.println("\n[3] SSO 回跳里抠 token");
        eq("普通 query", Net.tokenFromUrl("https://zwlib.ruc.edu.cn/jsq-v/?token=abc123"), "abc123");
        eq("token 后面还有 hash",
                Net.tokenFromUrl("https://zwlib.ruc.edu.cn/jsq-v/?token=abc123#/main/home"), "abc123");
        eq("token 在中间",
                Net.tokenFromUrl("https://zwlib.ruc.edu.cn/jsq-v/?a=1&token=xyz&b=2"), "xyz");
        eq("没有 token 返回 null",
                Net.tokenFromUrl("https://zwlib.ruc.edu.cn/jsq-v/"), null);
        eq("空 token 是空串而不是崩溃",
                Net.tokenFromUrl("https://zwlib.ruc.edu.cn/jsq-v/?token="), "");
    }

    /* ---------------- 4. buildingFloorDate 解析 ------------------------------- */
    static void venuesParsing() {
        System.out.println("\n[4] buildingFloorDate 解析（字段名抄自 buildList 模板）");
        JSONObject v = j(VENUES);

        List<Booker.Item> bl = Booker.buildings(v);
        eq("馆数量", bl.size(), 2);
        eq("第一个馆 id", bl.get(0).id, "11");
        eq("第一个馆名", bl.get(0).label, "图书馆");
        eq("缺 name 的馆有兜底标签", bl.get(1).label, "馆 22");

        List<Booker.Item> f11 = Booker.floors(v, "11");
        eq("楼层列表含「全部楼层」", f11.size(), 3);
        eq("floorId=0 表示全部楼层", f11.get(0).id, "0");
        eq("第一层 id", f11.get(1).id, "111");
        eq("第一层名", f11.get(1).label, "三层");
        eq("只列该馆的楼层", Booker.floors(v, "22").size(), 2);
        eq("未知馆只返回「全部楼层」", Booker.floors(v, "999").size(), 1);
        eq("不存在的馆不崩", Booker.floors(null, "11").size(), 1);

        eq("可预约日期原样读出", Booker.bookableDates(v).toString(),
                "[2026-09-15, 2026-09-16]");
        eq("null 响应不崩", Booker.bookableDates(null).size(), 0);
    }

    /* ---------------- 5. findRoomDuration 解析 ------------------------------- */
    static void roomsParsing() {
        System.out.println("\n[5] findRoomDuration 解析（字段名抄自房间卡片模板）");
        List<Booker.Item> r = Booker.parseRooms(j(ROOMS));
        eq("座位区数量", r.size(), 2);
        eq("第一个座位区 id", r.get(0).id, "9001");
        eq("第一个座位区名", r.get(0).label, "三层A区");
        eq("seatFree 被读出来（字符串转 int）", r.get(0).free, 7);
        eq("seatFree=0 的座位区", r.get(1).free, 0);
        eq("缺 pageList 返回空", Booker.parseRooms(j("{\"status\":true,\"data\":{}}")).size(), 0);
        eq("响应为 null 不崩", Booker.parseRooms(null).size(), 0);
    }

    /* ---------------- 6. freeSeatIds 的 data 形状（这就是那个致命 bug） -------- */
    static void freeSeatsParsing() {
        System.out.println("\n[6] freeSeatIds 的 data 形状（真实形状是 map，不是数组）");
        // 真实形状：reRender 用 t[i.seat.id] 取值 → 是按座位 id 索引的对象
        List<Booker.Seat> map = Booker.parseSeats(j(
                "{\"status\":true,\"data\":{\"456\":{\"label\":\"A01\"},\"789\":{\"label\":\"A02\"}}}"));
        eq("map 形状能取到座位 id（回归锁）", map.size(), 2);
        eq("map 里的 label 也读出来", map.get(0).label, "A01");
        eq("map 第二个 label", map.get(1).label, "A02");

        eq("数组形状仍然兼容",
                Booker.parseSeats(j("{\"status\":true,\"data\":[\"456\",\"789\"]}")).size(), 2);
        eq("对象数组兼容",
                Booker.parseSeats(j("{\"status\":true,\"data\":[{\"id\":\"456\"},{\"seatId\":\"789\"}]}")).size(), 2);

        eq("空 map → 0 个空座",
                Booker.parseSeats(j("{\"status\":true,\"data\":{}}")).size(), 0);
        eq("status=false → 0 个空座",
                Booker.parseSeats(j("{\"status\":false,\"message\":\"closed\"}")).size(), 0);
        eq("null → 0 个空座", Booker.parseSeats(null).size(), 0);
        eq("label 缺失时为 null",
                Booker.parseSeats(j("{\"status\":true,\"data\":{\"456\":{}}}")).get(0).label, null);
    }

    /* ---------------- 6b. 时间段必须显式设置 ---------------------------------- */
    static void windowRequired() {
        System.out.println("\n[6b] 时间段不再有默认值");
        Booker.Cfg fresh = new Booker.Cfg();
        ok("默认没有时间段", !fresh.hasWindow());
        eq("未设置时文案", fresh.windowText(), "未设置");
        fresh.beginMinute = 10 * 60;
        fresh.endMinute = 12 * 60;
        ok("设置后有效", fresh.hasWindow());
        eq("窗口文案", fresh.windowText(), "10:00 - 12:00");
        fresh.endMinute = fresh.beginMinute;
        ok("结束等于开始时无效", !fresh.hasWindow());
    }

    /* ---------------- 6e. 自动签到与定时预约完全独立 -------------------------- */
    static void checkInIndependence() {
        System.out.println("\n[6e] 自动签到与定时预约完全独立");
        Booker.Cfg cfg = new Booker.Cfg();
        ok("定时预约默认未配置时间段", !cfg.hasWindow());
        ok("自动签到默认已有独立守护时段", cfg.hasCiWindow());
        eq("自动签到默认时段文案", cfg.ciWindowText(), "07:00 - 22:30");

        // 修改签到守护时段，预约时段不受任何影响
        cfg.ciBeginMinute = 8 * 60;
        cfg.ciEndMinute = 21 * 60;
        ok("预约依然未设置", !cfg.hasWindow());
        eq("签到守护新文案", cfg.ciWindowText(), "08:00 - 21:00");

        // 预约未配置时，签到守护时段内的 delay 计算独立有效
        int delay = Booker.nextTickDelayMin(10 * 60, cfg.ciBeginMinute, cfg.ciEndMinute, false, -1, false);
        eq("即使未配置预约，签到时段内依然正常按 5 分钟巡检", delay, 5);

        // 守护时段非法时正确判断
        cfg.ciEndMinute = cfg.ciBeginMinute;
        ok("签到结束等于开始时无效", !cfg.hasCiWindow());
    }

    /* ---------------- 6d. freeSeatIds 混了非空座（真机日志抓到的） ------------ */
    static void freeSeatFiltering() {
        System.out.println("\n[6d] freeSeatIds 必须过滤 AWAY / FULL（线上真实样本）");
        int[] stats = new int[2];
        List<Booker.Seat> ok = Booker.parseSeats(j(fixture("freeSeatIdsMixed.json")), stats);
        eq("响应共 4 项", stats[0], 4);
        eq("只留下 2 个真 FREE", stats[1], 2);
        eq("AWAY(暂离) 被滤掉", ok.size(), 2);
        // 结果按 id 升序（确定性），不是按座位号排：
        //   id ...873(137) < id ...875(060)  → 137 在前
        eq("按 id 升序，137(id..873) 在前", ok.get(0).label, "137");
        eq("060(id..875) 在后", ok.get(1).label, "060");
        ok("两个真 FREE 都在", ok.get(0).id.startsWith("1878744732215119873"));
        for (Booker.Seat st : ok) {
            ok("结果里不能有 136/138", !"136".equals(st.label) && !"138".equals(st.label));
        }
        // 没有 status 字段时不要误杀
        eq("无 status 字段时全部保留",
                Booker.parseSeats(j("{\"status\":true,\"data\":{\"456\":{\"label\":\"A\"}}}")).size(), 1);
    }

    /* ---------------- 6c. 座位优先级 ------------------------------------------ */
    static Booker.Seat seat(String id, String label) {
        return new Booker.Seat(id, label);
    }

    static void seatPriority() {
        System.out.println("\n[6c] 座位优先顺序");
        eq("逗号分隔", Booker.parseSeatPriority("122,124,126").toString(), "[122, 124, 126]");
        eq("中英混用分隔符（空格/，/、/;）",
                Booker.parseSeatPriority("122 124，126、128;130").toString(),
                "[122, 124, 126, 128, 130]");
        eq("空串 → 空列表", Booker.parseSeatPriority("").size(), 0);
        eq("null → 空列表", Booker.parseSeatPriority(null).size(), 0);
        eq("多余空格被吃掉", Booker.parseSeatPriority(" 122 , 124 ").toString(), "[122, 124]");

        ok("'122' 匹配 'B122'", Booker.labelMatches("B122", "122"));
        ok("大小写无关", Booker.labelMatches("b122", "B122"));
        ok("完全相等", Booker.labelMatches("122", "122"));
        ok("'040' 匹配 '40'（前导零是噪音）", Booker.labelMatches("040", "40"));
        ok("'122' 不能匹配 '22'（会约错座位）", !Booker.labelMatches("122", "22"));
        ok("'B1022' 不能匹配 '22'", !Booker.labelMatches("B1022", "22"));
        ok("空 token 不匹配", !Booker.labelMatches("B122", ""));
        ok("null 不匹配", !Booker.labelMatches(null, "122"));

        java.util.List<Booker.Seat> free = new java.util.ArrayList<Booker.Seat>();
        free.add(seat("111", "101"));
        free.add(seat("222", "122"));
        free.add(seat("333", "124"));
        free.add(seat("444", "126"));

        eq("按优先级取第一个可用的", Booker.chooseSeat(free, "124,122", null).label, "124");
        eq("第一个优先项没空时顺延", Booker.chooseSeat(free, "999,126", null).label, "126");
        eq("优先项全没空 → 退回 id 最小",
                Booker.chooseSeat(free, "999,888", null).label, "101");
        eq("没设优先级 → id 最小", Booker.chooseSeat(free, "", null).label, "101");
        eq("没设优先级(null) → id 最小", Booker.chooseSeat(free, null, null).label, "101");
        eq("短标签能模糊命中", Booker.chooseSeat(free, "26,124", null).label, "124");
        eq("空列表返回 null",
                Booker.chooseSeat(new java.util.ArrayList<Booker.Seat>(), "1", null), null);
    }

    /* ---------------- 7. 日期选择与格式化 ------------------------------------- */
    static void datesAndFormat() {
        System.out.println("\n[7] 日期选择 / 时间格式化");
        List<String> d = Booker.bookableDates(j(VENUES));   // [2026-09-15, 2026-09-16]
        eq("偏移 0 = 今天", Booker.resolveDate(d, "2026-09-15", 0), "2026-09-15");
        eq("偏移 1 = 明天", Booker.resolveDate(d, "2026-09-15", 1), "2026-09-16");
        // 关键：目标日还没开放时绝不顶替成别的日子
        eq("明天还没开放 → null，不顶替成今天",
                Booker.resolveDate(java.util.Collections.singletonList("2026-09-15"),
                        "2026-09-15", 1), null);
        eq("今天可约、明天不可约时，今天仍然正常",
                Booker.resolveDate(java.util.Collections.singletonList("2026-09-15"),
                        "2026-09-15", 0), "2026-09-15");
        eq("列表为空时今天仍可用",
                Booker.resolveDate(null, "2026-09-15", 0), "2026-09-15");
        eq("列表为空时明天返回 null",
                Booker.resolveDate(null, "2026-09-15", 1), null);
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(2026, 8, 14, 21, 11, 0);
        eq("21:11 → 第 1271 分钟", Booker.minuteOfDay(c.getTime()), 1271);
        c.set(2026, 8, 14, 0, 0, 0);
        eq("00:00 → 0", Booker.minuteOfDay(c.getTime()), 0);
        c.set(2026, 8, 14, 23, 59, 0);
        eq("23:59 → 1439", Booker.minuteOfDay(c.getTime()), 1439);

        eq("跨月：09-30 + 1 天", Booker.addDays("2026-09-30", 1), "2026-10-01");
        eq("跨年：12-31 + 1 天", Booker.addDays("2026-12-31", 1), "2027-01-01");
        eq("闰年：2028-02-28 + 1 天", Booker.addDays("2028-02-28", 1), "2028-02-29");
        eq("非法输入返回 null", Booker.addDays("not-a-date", 1), null);

        eq("10:00", Booker.hhmm(600), "10:00");
        eq("00:00", Booker.hhmm(0), "00:00");
        eq("补零 07:05", Booker.hhmm(7 * 60 + 5), "07:05");
        eq("23:59", Booker.hhmm(1439), "23:59");

        eq("firstStr 跳过空串", Booker.firstStr(j("{\"a\":\"\",\"b\":\"x\"}"), "a", "b"), "x");
        eq("firstStr 跳过 null", Booker.firstStr(j("{\"a\":null,\"b\":\"y\"}"), "a", "b"), "y");
        eq("firstStr 数字转字符串", Booker.firstStr(j("{\"a\":9001}"), "a"), "9001");
        eq("firstStr 全空返回 null", Booker.firstStr(j("{\"a\":\"\"}"), "a", "z"), null);
    }

    /* ---------------- 8. 签到状态 / 暂离守护 ------------------------------- */
    // currentUseMake 的字段名抄自站点自己的 currentBook（与 freeBook 的 orderObj 同一套：
    // makeDateStr / makeBeginStr / makeEndStr / location / seatLabel），status 文案来自
    // bundle 里的 my.table1.status1..8；排期期望值是手算的。
    static void checkInWatch() {
        System.out.println("\n[8] 当前预约状态 / 暂离守护排期");

        eq("data 为空对象 = 没有有效预约", Booker.parseNow(j("{\"status\":true,\"data\":{}}")).has, false);
        eq("data 为 null = 没有有效预约", Booker.parseNow(j("{\"status\":true,\"data\":null}")).has, false);
        eq("status=false = 没有有效预约", Booker.parseNow(j("{\"status\":false,\"message\":\"x\"}")).has, false);
        eq("null 响应不崩", Booker.parseNow(null).has, false);
        eq("形状不认识时当作没有", Booker.parseNow(j("{\"status\":true,\"data\":{\"foo\":1}}")).has, false);

        Booker.Now away = Booker.parseNow(j("{\"status\":true,\"data\":{"
                + "\"id\":\"1878649390807224320\",\"status\":\"AWAY\","
                + "\"makeDateStr\":\"2026-09-25\",\"makeBeginStr\":\"10:00\",\"makeEndStr\":\"12:00\","
                + "\"location\":\"图书馆|三层A区\",\"seatLabel\":\"B102\"}}"));
        eq("有有效预约", away.has, true);
        eq("状态原样取出", away.state, "AWAY");
        eq("日期", away.date, "2026-09-25");
        eq("开始/结束", away.begin + "-" + away.end, "10:00-12:00");
        ok("文案里地点竖线还原成空格", away.text().contains("图书馆 三层A区"));
        ok("文案里带座位号", away.text().contains("B102"));
        ok("AWAY 的中文是「暂离」", away.text().startsWith("暂离"));
        eq("小写状态也认", Booker.parseNow(j("{\"status\":true,\"data\":{\"id\":1,\"status\":\"away\"}}"))
                .state, "AWAY");
        eq("没有有效预约的文案", Booker.parseNow(j("{\"status\":true,\"data\":{}}")).text(),
                "服务端说此刻没有有效预约");

        eq("状态文案 CHECK_IN", Booker.stateText("CHECK_IN"), "履约中（已签到）");
        eq("状态文案 RESERVE", Booker.stateText("RESERVE"), "预约（未签到）");
        eq("状态文案 MISS", Booker.stateText("MISS"), "失约");
        eq("状态文案 NO_STOP", Booker.stateText("NO_STOP"), "未签退");
        eq("状态文案全小写也认", Booker.stateText("leave_early"), "早退");
        eq("没见过的状态原样显示", Booker.stateText("WEIRD"), "WEIRD");

        eq("12:05 → 725", Booker.minuteText("12:05"), 725);
        eq("07:00 → 420", Booker.minuteText("07:00"), 420);
        eq("带秒也认", Booker.minuteText("07:00:30"), 420);
        eq("空返回 -1", Booker.minuteText(null), -1);
        eq("坏输入返回 -1", Booker.minuteText("25:99"), -1);

        ok("结束时刻能解析", Booker.endAtMillis("2026-09-25", "12:00") > 0);
        eq("结束时刻坏输入", Booker.endAtMillis("x", "12:00"), -1L);
        eq("结束时刻缺一半", Booker.endAtMillis(null, "12:00"), -1L);

        eq("tokenGone: 20003", Booker.tokenGone(j("{\"status\":false,\"code\":20003}")), true);
        eq("tokenGone: 正常响应", Booker.tokenGone(j("{\"status\":true,\"data\":{}}")), false);
        eq("tokenGone: null", Booker.tokenGone(null), false);

        int B = 10 * 60, E = 12 * 60;
        eq("时段开始前：睡到「开始 - 5 分钟」", Booker.nextTickDelayMin(9 * 60 + 30, B, E, false, -1, false), 25);
        eq("时段内：每 5 分钟", Booker.nextTickDelayMin(10 * 60 + 30, B, E, true, -1, false), 5);
        eq("正好到提前量：进入巡检节奏", Booker.nextTickDelayMin(9 * 60 + 55, B, E, false, -1, false), 5);
        eq("时段尾巴内还盯一会儿", Booker.nextTickDelayMin(12 * 60 + 3, B, E, false, -1, false), 2);
        eq("出了时段尾巴：收工", Booker.nextTickDelayMin(12 * 60 + 10, B, E, false, -1, false), -1);
        eq("记录自己说 12:00 才结束 → 过了尾巴也继续盯",
                Booker.nextTickDelayMin(12 * 60 + 10, B, E, true, E, false), 5);
        eq("没配时段、今天有预约 → 每 5 分钟", Booker.nextTickDelayMin(10 * 60, -1, -1, true, -1, false), 5);
        eq("没配时段、没有预约 → 收工", Booker.nextTickDelayMin(10 * 60, -1, -1, false, -1, false), -1);
        eq("时段非法（结束早于开始）视作没配", Booker.nextTickDelayMin(10 * 60, E, B, false, -1, false), -1);
        eq("暂离中：2 分钟一枪（要盯着释放时间）",
                Booker.nextTickDelayMin(10 * 60 + 30, B, E, true, -1, true), 2);
        eq("暂离中也不能越过时段尾巴",
                Booker.nextTickDelayMin(12 * 60 + 4, B, E, true, -1, true), 1);

        /* 暂离时刻：记录里的 awayRange → 门禁记录的最后一次离馆 → 第一次观测 */
        long t1438 = Booker.parseDateTime("2026-09-25 14:38:00", null);
        ok("awayRange 里的时刻能解析", t1438 > 0);
        eq("awayRange 只有时间 → 用记录日期补", Booker.awayStartMillis("14:38~15:38", "2026-09-25"), t1438);
        eq("awayRange 带日期也认", Booker.awayStartMillis("2026-09-25 14:38:00~15:38", "2026-09-25"), t1438);
        eq("awayRange 用下划线分隔也认", Booker.awayStartMillis("14:38_15:38", "2026-09-25"), t1438);
        eq("返回了（只有开始）也对", Booker.awayStartMillis("14:38", "2026-09-25"), t1438);
        eq("同一天多次暂离：只认最后一段",
                Booker.awayStartMillis("11:00~11:20,14:38~15:38", "2026-09-25"), t1438);
        eq("空 awayRange（~~）→ -1", Booker.awayStartMillis("~~", "2026-09-25"), -1L);
        eq("null awayRange → -1", Booker.awayStartMillis(null, "2026-09-25"), -1L);

        /* 变更记录：服务端自己记的暂离时刻（比门禁反推准） */
        JSONObject life = j("{\"status\":true,\"data\":["
                + "{\"stage\":\"RESERVE\",\"stageName\":\"预约\",\"createdDate\":\"2026-09-25 09:58:01\"},"
                + "{\"stage\":\"CHECK_IN\",\"stageName\":\"签到\",\"createdDate\":\"2026-09-25 10:02:11\"},"
                + "{\"stage\":\"AWAY\",\"stageName\":\"暂离\",\"createdDate\":\"2026-09-25 14:38:07\"},"
                + "{\"stage\":\"LEAVE_EARLY\",\"stageName\":\"早退\",\"createdDate\":\"2026-09-25 15:50:00\"}]}");
        eq("变更记录里取最近一次暂离",
                Booker.lastAwayIn(life, "2026-09-25"),
                Booker.parseDateTime("2026-09-25 14:38:07", null));
        eq("只有早退没有暂离 → -1",
                Booker.lastAwayIn(j("{\"status\":true,\"data\":[{\"stageName\":\"早退\",\"createdDate\":\"2026-09-25 15:50:00\"}]}"),
                        "2026-09-25"), -1L);
        eq("只有英文 stage 也认",
                Booker.lastAwayIn(j("{\"status\":true,\"data\":[{\"stage\":\"AWAY\",\"createdDate\":\"2026-09-25 16:40:00\"}]}"),
                        "2026-09-25"), Booker.parseDateTime("2026-09-25 16:40:00", null));
        eq("变更记录拿不到 → -1", Booker.lastAwayIn(j("{\"status\":false}"), "2026-09-25"), -1L);

        /* 手动签到（远程）：只有「值得调接口」的状态才真的调，别的如实报告 */
        eq("没预约 → 不调", Booker.signPlan(Booker.parseNow(j("{\"status\":true,\"data\":{}}"))),
                Booker.SIGN_NONE);
        eq("响应为 null → 不调", Booker.signPlan(Booker.parseNow(null)), Booker.SIGN_NONE);
        eq("暂离 → 要调", Booker.signPlan(nowWith("AWAY")), Booker.SIGN_GO);
        eq("预约（未签到）→ 要调", Booker.signPlan(nowWith("RESERVE")), Booker.SIGN_GO);
        eq("小写状态也认", Booker.signPlan(nowWith("reserve")), Booker.SIGN_GO);
        eq("没见过的状态 → 试一次（以服务端返回为准）", Booker.signPlan(nowWith("WEIRD")),
                Booker.SIGN_GO);
        eq("有记录但没状态 → 试一次",
                Booker.signPlan(Booker.parseNow(j("{\"status\":true,\"data\":{\"id\":\"1\"}}"))),
                Booker.SIGN_GO);
        eq("已经履约中 → 不重复调", Booker.signPlan(nowWith("CHECK_IN")), Booker.SIGN_DONE);
        eq("早退 → 不调", Booker.signPlan(nowWith("LEAVE_EARLY")), Booker.SIGN_DEAD);
        eq("已结束 → 不调", Booker.signPlan(nowWith("STOP")), Booker.SIGN_DEAD);
        eq("未签退 → 不调", Booker.signPlan(nowWith("NO_STOP")), Booker.SIGN_DEAD);
        eq("失约 → 不调", Booker.signPlan(nowWith("MISS")), Booker.SIGN_DEAD);
        eq("已取消 → 不调", Booker.signPlan(nowWith("CANCEL")), Booker.SIGN_DEAD);

        /* 兜底：拿不到离座时刻时往前推一个巡检间隔（宁可早算，别晚算） */
        eq("兜底时刻 = 观测时刻 - 5 分钟", Booker.fallbackSince(1000000L), 1000000L - 5 * 60000L);

        /* 登录态失效后的重试节奏：窗口内 30 分钟，出了窗口收工 */
        eq("失效后：窗口内 30 分钟再试", Booker.stallDelayMin(10 * 60 + 30, B, E), 30);
        eq("失效后：窗口前也按 30 分钟（不早于窗口起点）", Booker.stallDelayMin(9 * 60 + 30, B, E), 30);
        eq("失效后：一大早就等很久", Booker.stallDelayMin(6 * 60, B, E), 235);
        eq("失效后：出了窗口就收工", Booker.stallDelayMin(12 * 60 + 10, B, E), -1);

        JSONObject door = j("{\"code\":200,\"data\":["        // 门禁：0=入馆, 1=离馆
                + "{\"direction\":0,\"doorName\":\"东门\",\"dateTimeStr\":\"2026-09-25 13:00:00\"},"
                + "{\"direction\":1,\"doorName\":\"东门\",\"dateTimeStr\":\"2026-09-25 14:38:12\"},"
                + "{\"direction\":0,\"doorName\":\"东门\",\"dateTimeStr\":\"2026-09-25 15:10:00\"}]}");
        eq("门禁取最后一条离馆（漏记入馆时那条依然是 14:38）",
                Booker.lastLeaveIn(door, "2026-09-25"),
                Booker.parseDateTime("2026-09-25 14:38:12", null));
        eq("direction 是字符串也认",
                Booker.lastLeaveIn(j("{\"code\":200,\"data\":[{\"direction\":\"1\",\"dateTimeStr\":\"09:10:00\"}]}"),
                        "2026-09-25"), Booker.parseDateTime("2026-09-25 09:10:00", null));
        eq("门禁没有任何离馆记录 → -1",
                Booker.lastLeaveIn(j("{\"code\":200,\"data\":[{\"direction\":0,\"dateTimeStr\":\"09:10:00\"}]}"),
                        "2026-09-25"), -1L);
        eq("门禁接口失败 → -1", Booker.lastLeaveIn(j("{\"code\":500,\"data\":null}"), "2026-09-25"), -1L);
        eq("门禁：status 外壳也认",
                Booker.lastLeaveIn(j("{\"status\":true,\"data\":[{\"direction\":1,\"dateTimeStr\":\"09:10:00\"}]}"),
                        "2026-09-25"), Booker.parseDateTime("2026-09-25 09:10:00", null));
        eq("isLeave: 1 = 离馆", Booker.isLeave("1"), true);
        eq("isLeave: 0 = 入馆", Booker.isLeave("0"), false);
        eq("isLeave: 中文也认", Booker.isLeave("离馆"), true);

        /* 宽限：平时 60 分钟，饭点（11:00-13:30）120 分钟 */
        int MS = 11 * 60, ME = 13 * 60 + 30;
        eq("14:38 离座 → 1 小时", Booker.graceMin(14 * 60 + 38, MS, ME, 120, 60), 60);
        eq("11:30 离座（饭点）→ 2 小时", Booker.graceMin(11 * 60 + 30, MS, ME, 120, 60), 120);
        eq("饭点起点 11:00 算饭点", Booker.graceMin(MS, MS, ME, 120, 60), 120);
        eq("饭点终点 13:30 不算饭点", Booker.graceMin(ME, MS, ME, 120, 60), 60);
        eq("离座时刻未知 → 按平时算", Booker.graceMin(-1, MS, ME, 120, 60), 60);
    }
}

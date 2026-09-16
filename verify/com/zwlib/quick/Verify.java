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
}

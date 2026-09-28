# 图书馆座位 · 快捷 APK

把 `https://zwlib.ruc.edu.cn/jsq-v/#/main/index` 封成一个可侧载的 Android 应用，
并解决"每次打开都要重新登录"的问题。

## 登录链路（逆向结论）

```
APP → https://zwlib.ruc.edu.cn/jsq-v/           座位预约 SPA
      └─ 无 token 时 router guard 调 Se()
         └─ CASLOGIN=1 → location.href = https://zwlib.ruc.edu.cn/rem/static/sso/login?redirectUrl=...
            └─ 302 → https://cas.ruc.edu.cn/cas/login?service=...   ← 统一身份认证
               └─ 登录成功回跳 ?token=xxx → SPA 换 token 存进 sessionStorage
```

关键事实：

| 事实 | 影响 |
|---|---|
| 座位系统 token 存在 `sessionStorage['jsq_p-token']` | 关掉 WebView 即丢，所以每次都要重新登录 |
| 路由表里**没有** `/main/index`，真实首页是 `/main/home` | 用错地址会白屏 |
| `GET/POST /jsq/static/frontApi/user/getUserInfo`（header `token`） | 可原生探活：`status:true` 有效，`20003` 过期 |
| CAS 页有「7天内自动登录」(`#rememberMe`)，默认**不勾** | 勾一次 = 7 天静默免密，这是最高价值的一步 |
| CAS 提交时密码 RSA 加密进 hidden 字段，且**验证码必填** | 只预填、不自动提交，验证码留给人 |

## 三层免密策略

1. **token 续期** — 冷启动先用原生 HTTP 探活保存的 token；有效就把整份 sessionStorage
   快照在 document-start 注入回去，直接进 `/main/home`，完全跳过统一认证跳转。
2. **CAS 兜底** — token 失效/不存在时直接走 CAS 页：自动勾选「7天内自动登录」、
   预填学工号与密码、把光标放到验证码框。用户只需敲 4 位验证码。
3. **自愈** — 页面内若拿到 `20003`，脚本上报 `tokenGone`，原生立即丢弃死快照，
   避免下次再注入一个坏 token 形成死循环。

凭据（学工号/密码）用 **Android Keystore AES-256/GCM** 加密后存 SharedPreferences，
密钥不出 TEE；注入的 `<script>` 执行后自删，密码不落 DOM 全局。
登录页右下角 ⚙ 可随时关闭并清除。

## 构建

```bash
./build.sh <versionCode> <versionName>   # 纯 aapt2 + javac + d8 + apksigner，无 Gradle
```

产物：`zwlib-quick.apk`（debug keystore 签名，可直接安装）

## 验证

```bash
node test/verify.js
```

用 Playwright 直接打真实线上页面，验证注入逻辑（CAS 预填/勾选/不自动提交、
token 注入是否真的跳过统一认证、失效后是否自愈回 CAS）。当前 9/9 通过。

---

## 定时预约（v1.9）

### 流程（逆向自 app.js）

```
POST /static/frontApi/res/buildingFloorDate                  {}                       → 场馆 + 可约日期
POST /static/frontApi/res/freeSeatIdsDuration/{room}/{date}  {beginMinute,endMinute}   → 空座位 id
POST /static/frontApi/make/freeBook/{seat}/{date}/{b}/{e}?capToken=capToken  {}        → 下单
```

`beginMinute`/`endMinute` 是**当天第几分钟**（站点用 `Math.floor(t/60)` + `t%60` 还原成 HH:MM）；
`date` 是 `yyyy-MM-dd`。`capToken` 在 `mackCaptcha=0` 时是字面量字符串 `"capToken"`。

### 后台为什么能自己换 token

07:01 时手机多半在 Doze，WebView 不参与，所以：

1. 前台每次 `onPause` 把 WebView 的 cookie 快照（含 `CASTGC` = 7 天免登录）加密存下来；
2. 后台跑时先 `POST getUserInfo` 探活座位 token；
3. 过期就用快照里的 cookie 走一遍
   `GET /rem/static/sso/login?redirectUrl=...jsq-v`，手动跟 302，从终点 URL 抠出 `token=`；
4. 新 token 写回快照，WebView 下次直接复用。

CAS 表单那一步（要滑块验证码）永远不碰——所以 7 天到期后只会通知你去手动登录一次。

### 安全默认

- `bk_enabled` 默认 **false**：不点启用就不会跑
- `bk_dry` 默认 **true**：到点只查询、**不下单**
- 只取 `freeSeatIdsDuration` 返回的**第一个**空位，不做并发、不重试风暴、不抢 7:00
- 服务端若返回验证码相关拒绝，直接放弃并通知，不尝试绕过

### 关于「7点有验证码」的误解

预约的验证码**不是按时间触发**的，是服务端开关：

```js
openCaptcha(mackCaptcha, 弹滑块, 直接下单) {
    mackCaptcha == 0 ? 直接下单()     // ← 现状，任何时间都一样
  : mackCaptcha == 2 ? 弹滑块()       // 总是
  : /* ==1 */ checkHigh().then(高峰期 ? 弹滑块 : 直接下单)
}
```

`getSysSet` 返回 `mackCaptcha: 0` → **任何时间点预约都不需要验证码**，
`checkHigh`（高峰期判断）只在 `mackCaptcha==1` 时才会被调用。

有验证码的是 **CAS 登录页**（`#authcode`，前端强制必填），那是登录时的事，
7 天免登录有效期内不会遇到。

### 两处对应的代码

- `Booker.risk()` —— 完整复刻上面那段判断，只读，不尝试满足任何验证码。
  设置里「检查风控状态」可随时查 `mackCaptcha` / `checkHigh` 的实时值。
- `Booker.run()` —— 下单前先跑一次风控预检；若当前需要验证码，
  **直接放弃并通知**，不去撞也不去绕。

---

## 座位守护 · 暂离提醒与自动签到（v3.2）

签到/返回是**闸机刷卡**触发的：出馆记一条「离馆」、回来记一条「入馆」。实际会碰到
「出去被记到了、回来没被记到」——人明明在座位上，系统却记你**暂离**，不管它最后按**早退**处理。

图书馆的时限规矩（设置里可改）：**平时离座 1 小时、饭点（默认 11:00-13:30）2 小时**，
超时座位被释放。

状态全在服务端，客户端只读；`status` 的取值和站点自己的文案：

| status | 页面文案 | 含义 |
|---|---|---|
| `RESERVE` | 预约 | 已约、未签到 |
| `CHECK_IN` | 履约中 | 已签到 |
| `AWAY` | 暂离 | 闸机记了离馆、没记入馆 |
| `LEAVE_EARLY` / `STOP` / `NO_STOP` / `MISS` / `CANCEL` | 早退 / 已结束 / 未签退 / 失约 / 已取消 | |

识别到暂离后的三段式（后台 `WatchReceiver` 闹钟链驱动，暂离中每 2 分钟一次）：

```
① 发通知问：「座位暂离 · 要我帮你签到吗？」
     [帮我签到]            → 立刻调返回，不等时限
     [不用，我自己回来]     → 记下来：不再打扰，也不自动签
② 不回 → 每 10 分钟再问一次（通知里带剩余分钟数）
③ 一直不回 → 释放前 10 分钟自动替你签
```

签到时刻从哪来（越准越关键）：

```
awayRange（记录里的「暂离/返回时间」，如 14:38~15:38）   ← 优先
  └─ 拿不到 → /user/doorLog/{date} 里最后一条「离馆」的 dateTimeStr
       └─ 再拿不到 → 第一次巡检看到「暂离」的时间（宁可早算，不会晚）
```

释放时间 = 离座时刻 + 宽限（平时 60 / 饭点 120 分钟）；通知里的"还剩 N 分钟"和自动签到的
时点都按它算。`POST /static/frontApi/make/checkIn?qrMd5=PC` 就是站点 PC 页面「返回」走的那个接口。

- **完全独立于定时预约**：自动签到有自己独立的守护时段配置（默认 07:00-22:30，可自定义），开启时不依赖预约配置，不开定时预约也能完全独立运行。
- **独立闹钟自循环**：在守护时段开始前 5 分钟排第一枪，时段内每 5 分钟（暂离每 2 分钟）巡检，当天守护结束后自动排到次日守护起点，跨天自动轮转，不再依赖定时预约唤醒。
- 用 `setExactAndAllowWhileIdle`：不进省电白名单也能醒，但 Doze 深睡时会被拉长到 ~9 分钟一次，
  所以是分钟级、不是秒级（"还剩 N 分钟"会有几分钟误差）。
- **安全默认**：`bk_ci_enabled` 默认 **false**，`bk_ci_dry` 默认 **true**。
  试运行下询问照发，但点「帮我签到」不会真的调接口，只记录。
- 同一个结果只打扰一次（连续失败不会每 2 分钟弹一条）；暂离结束自动撤掉那条询问。
- 设置里「签到状态」是**只读**的：看一眼此刻服务端的状态与倒计时，不改任何守护状态。

> ⚠ 这个开关**不判断你人在不在馆**。服务端说「暂离」它就按上面的流程走，所以你真出去办事时
> 它也会在时限前把你「修」回履约中——那是占座，规则上算违约。只把它当闸机漏记的补丁用。

---

## 手动签到 · 远程一键（v3.4）

设置页「座位守护」下面多了一块：**立即签到**。点一下，App 直接替你调

```
POST /static/frontApi/make/checkIn?qrMd5=PC
```

然后把「点之前是什么状态 → 点之后是什么状态」当场弹出来。不用等巡检、
不用等服务端说「暂离」，也不用先去网页里翻。

按当前状态决定调不调：

| 服务端此刻的状态 | 点了之后 |
|---|---|
| 暂离 / 预约（未签到） | 真的调一次接口，再复查一次并把结果对比出来 |
| 履约中（已签到） | 不重复调，只告诉你已经签过了 |
| 早退 / 已结束 / 未签退 / 失约 / 已取消 | 记录已经结束了，不调 |
| 没有有效预约 | 不调 |

- **不受守护开关与试运行限制**：`bk_ci_enabled` 关着、`bk_ci_dry` 开着都不影响 ——
  这是你手动点的，就是要真签（那套开关只管自动巡检那条路）。
- 只调一次，不重试、不并发；被拒就把服务端原话显示出来，不猜原因。
- 结果写进设置页那行「签到状态」，签成了顺手撤掉可能还挂着的「要我帮你签到吗」通知。
- 要登录态：7 天免登录过期时会让你先回页面登录一次。

> ⚠ 同上一条：它不判断你人在不在馆。服务端说这条预约还能签，它签完就是履约中 ——
> 人不在就是占座，规则上算违约。别拿它替别人签，只当闸机漏记的补丁和自己的应急按钮。

---

## 验证（verify/）

```bash
./build.sh 25 3.4     # 先构建，生成 build/gen/R.java
./verify/run.sh       # 208 项 seam 测试，跑真实的 Net/Booker 代码
./verify/oracle.sh    # 重新生成期望值（用站点自己的 crypto-js）
```

桌面 JVM 上直接跑生产代码：用真的 `org.json`，加一个 `android.util.Base64` 替身
（`verify/android/util/Base64.java`），所以测的是同一份 `Net.java` / `Booker.java`。

### seam 与各自的独立 oracle

| Seam | Oracle（**不是**用被测代码算期望值） |
|---|---|
| `Net.hmacSignature` / `unwrapKey` | 站点自己的 `crypto-js.min.js`，在 node 里跑 bundle 原文 |
| `Net.Jar` / `tokenFromUrl` | 真实服务器抓到的 `Set-Cookie` 原文 + RFC 6265 语义 |
| `Booker.parseFreeSeats` / `parseRooms` / `buildings` / `floors` / `pickDate` / `parseLastMakeId` | 手写 fixture，**字段名逐个抄自 bundle 的 Vue 模板**，期望值手算 |
| `Booker.parseNow` / `stateText` / `signPlan`（手动签到调不调接口） | 手写 fixture（同一套 `my.table1.status*` 状态名），决策表手算 |

### 这一步抓到的三个真 bug

都是"形状猜错"，读写代码本身看不出问题，但线上必然失败：

1. **流程少一步** —— `buildingFloorDate` 只到"层"，座位区要另外调
   `findRoomDuration/{venueId}/{date}`，它在 `data.pageList` 里。把馆 id 当座位区 id
   传，服务端不认。
2. **`freeSeatIdsDuration` 的 `data` 是 map 不是数组** —— 座位图用
   `t[i.seat.id]` 取值、`Object.keys(o).length` 判空。原实现只处理 `JSONArray`，
   于是永远返回 0 个空座。
3. **`freeBook` 返回里没有可取消的 id** —— `orderObj` 只有
   `message/makeDateStr/makeBeginStr/makeEndStr/location/seatLabel`；
   取消要用的 id 在 `/user/lastMake` 的列表里（`id` + `status`）。

### 变异测试

把签名里的 `"seat::"` 前缀去掉 → 精确红在那 2 条签名测试；复原 → 全绿。
这证明测试不是恒真的空测试。

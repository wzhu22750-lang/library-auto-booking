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

## 验证（verify/）

```bash
./build.sh 21 3.0     # 先构建，生成 build/gen/R.java
./verify/run.sh       # 64 项 seam 测试，跑真实的 Net/Booker 代码
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

// 抓真实的"现在能预约的座位和时间段"。
// 你只需要在打开的浏览器里完成一次统一身份认证（验证码必须由人填），
// 之后 4 步 API 全由脚本完成 —— 用的是页面自己的 CryptoJS 和你的会话。
const PW = '/opt/homebrew/lib/node_modules/@playwright/mcp/node_modules/playwright';
const { chromium } = require(PW);
const fs = require('fs');
const EXE = process.env.HOME + '/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing';

const BEGIN = 10 * 60;   // 10:00
const END = 12 * 60;     // 12:00

(async () => {
  const browser = await chromium.launch({ headless: false, executablePath: EXE, slowMo: 30 });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 } });
  const page = await ctx.newPage();

  page.on('console', m => { const t = m.text(); if (t.startsWith('__RECON__')) console.log(t.slice(9)); });

  console.log('[1] 打开座位预约系统…');
  await page.goto('https://zwlib.ruc.edu.cn/jsq-v/', { waitUntil: 'domcontentloaded', timeout: 60000 });

  console.log('[2] 等待你完成统一身份认证（最多 10 分钟）…');
  try {
    await page.waitForFunction(
      () => !!(window.sessionStorage && sessionStorage.getItem('jsq_p-token')),
      null, { timeout: 600000, polling: 500 });
  } catch (e) {
    console.log('!! 超时：没等到登录。脚本退出。');
    await browser.close();
    process.exit(2);
  }
  const host = new URL(page.url()).host;
  console.log('[3] 已登录，host =', host, ' 开始抓取…');
  await page.waitForTimeout(1500);

  const report = await page.evaluate(async ({ BEGIN, END }) => {
    const API = 'https://zwlib.ruc.edu.cn/jsq';
    const token = sessionStorage.getItem('jsq_p-token');
    const sys = JSON.parse(sessionStorage.getItem('jsq_p-systemInfo') || '{}');

    // 用站点自己的 CryptoJS 做签名（和它 axios 拦截器逐字一致）
    let key = null;
    if (Number(sys.hmac) === 1 && sys.hmacKey) {
      key = window.CryptoJS.AES.decrypt(
        sys.hmacKey,
        window.CryptoJS.enc.Utf8.parse('server_date_time'),
        { iv: window.CryptoJS.enc.Utf8.parse('client_date_time'),
          mode: window.CryptoJS.mode.CBC, padding: window.CryptoJS.pad.Pkcs7 }
      ).toString(window.CryptoJS.enc.Utf8);
    }

    async function post(path, body) {
      const rid = crypto.randomUUID();
      const ts = Date.now();
      const h = {
        'Content-Type': 'application/json;charset=UTF-8',
        'loginType': 'PC',
        'token': token,
        'X-request-id': rid,
        'X-request-date': String(ts),
      };
      if (key) h['X-hmac-request-key'] = window.CryptoJS.HmacSHA256(
        `seat::${rid}::${ts}::POST`, key).toString();
      const r = await fetch(API + path, {
        method: 'POST', headers: h, body: JSON.stringify(body || {}), credentials: 'same-origin'
      });
      const text = await r.text();
      try { return JSON.parse(text); } catch (e) { return { __raw: text.slice(0, 300), __status: r.status }; }
    }

    const out = { signed: !!key, steps: {}, raw: {} };

    // ---- 步骤 1：馆 + 可约日期
    const venues = await post('/static/frontApi/res/buildingFloorDate');
    out.raw.venues = venues;
    out.steps.venues = { status: venues.status, msg: venues.message };
    const buildings = (venues.data && venues.data.buildings) || [];
    const dates = (venues.data && venues.data.dates) || [];
    out.buildings = buildings.map(b => ({
      id: b.id, name: b.name, seTime: b.seTime,
      floors: (b.floors || []).map(f => ({ id: f.id, name: f.name }))
    }));
    out.dates = dates;

    // ---- 步骤 2/3：每个馆 × 每个日期 × 时段 → 座位区 + 空座
    out.plan = [];
    for (const b of buildings) {
      for (const date of dates) {
        const roomsResp = await post(`/static/frontApi/res/findRoomDuration/${b.id}/${date}`,
          { beginMinute: BEGIN, endMinute: END, floorId: 0, minMinute: 0, currentPage: 1,
            pageSize: 50, power: false, roomType: false, sortField: '', sortType: '', windows: false });
        if (!out.raw.rooms) out.raw.rooms = roomsResp;
        const d = roomsResp.data || {};
        const pageList = d.pageList || [];
        const entry = { venue: b.name, venueId: b.id, date, rooms: [], freeSeatSample: null };
        for (const r of pageList) {
          entry.rooms.push({
            id: r.id, name: r.name, seatFree: r.seatFree, seatTotal: r.seatTotal,
            building: r.buildingName, floor: r.floorName
          });
        }
        // 真实空座（取第一个还有座的座位区）
        const first = pageList.find(r => Number(r.seatFree) > 0) || pageList[0];
        if (first) {
          const fs2 = await post(`/static/frontApi/res/freeSeatIdsDuration/${first.id}/${date}`,
            { beginMinute: BEGIN, endMinute: END, minMinute: 0 });
          if (!out.raw.freeSeats) out.raw.freeSeats = fs2;
          const data = fs2.data;
          entry.freeSeatRoom = first.name;
          entry.freeSeatRoomId = first.id;
          entry.freeSeatCount = (data && typeof data === 'object') ? Object.keys(data).length : 0;
          entry.freeSeatSample = (data && typeof data === 'object')
            ? Object.keys(data).slice(0, 6) : [];
          entry.freeSeatValueSample = (data && typeof data === 'object' && entry.freeSeatSample.length)
            ? data[entry.freeSeatSample[0]] : null;
        }
        out.plan.push(entry);
      }
    }
    out.tokenPrefix = token.slice(0, 8) + '…';
    return out;
  }, { BEGIN, END });

  fs.writeFileSync(__dirname + '/recon-out.json', JSON.stringify(report, null, 2));
  console.log('\n================ 抓取结果 ================');
  console.log('签名:', report.signed ? '已启用' : '未启用', ' token:', report.tokenPrefix);
  console.log('步骤1 buildingFloorDate:', JSON.stringify(report.steps.venues));
  console.log('\n馆:');
  for (const b of report.buildings) {
    console.log(`  ${b.id}  ${b.name}  (${b.seTime})  楼层: ` +
      b.floors.map(f => `${f.id}=${f.name}`).join(', '));
  }
  console.log('\n可预约日期:', JSON.stringify(report.dates));
  for (const p of report.plan) {
    console.log(`\n--- ${p.venue} / ${p.date} / 10:00-12:00 ---`);
    if (!p.rooms.length) { console.log('  (没有座位区)'); continue; }
    for (const r of p.rooms) {
      console.log(`  ${String(r.id).padEnd(22)} ${String(r.name).padEnd(14)} ` +
        `空座 ${String(r.seatFree).padEnd(5)} / 共 ${r.seatTotal}   ${r.building}/${r.floor}`);
    }
    console.log(`  → 真实空座: ${p.freeSeatRoom} 共 ${p.freeSeatCount} 个, 例如 ${JSON.stringify(p.freeSeatSample)}`);
    console.log(`     座位值样本: ${JSON.stringify(p.freeSeatValueSample)}`);
  }
  console.log('\n原始响应已存 verify/recon-out.json');

  await page.waitForTimeout(3000);
  await browser.close();
})().catch(e => { console.log('!! 出错:', e); process.exit(1); });

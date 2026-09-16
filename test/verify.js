const PW = '/opt/homebrew/lib/node_modules/@playwright/mcp/node_modules/playwright';
const { chromium } = require(PW);
const fs = require('fs');

const ZW = fs.readFileSync('../assets/zw.js', 'utf8');

// Emulates the Android side: addJavascriptInterface(ZWNative) + evaluateJavascript push.
const stub = (state, seed) => `
  window.__EV = [];
  window.ZWNative = {
    getState: function () { return ${JSON.stringify(JSON.stringify(state))}; },
    getSeed: function () { return ${JSON.stringify(seed || '')}; },
    onEvent: function (j) { try { window.__EV.push(JSON.parse(j)); } catch (e) {} },
    toast: function () {}
  };
`;

function report(name, ok, extra) {
  console.log((ok ? '  PASS  ' : '  FAIL  ') + name + (extra ? '  → ' + extra : ''));
  return ok;
}

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.HOME + '/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing' });
  let failures = 0;

  /* ---------------- Test 1: CAS login page ---------------- */
  console.log('\n=== 测试 1：CAS 登录页（cas.ruc.edu.cn） ===');
  {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await ctx.newPage();
    await page.addInitScript(stub({ auto: true, hasCreds: true, user: '20230001' }, ''));
    await page.addInitScript(ZW);

    const url = 'https://cas.ruc.edu.cn/cas/login?service=https%3A%2F%2Fzwlib.ruc.edu.cn%2Frem%2Fstatic%2Fsso%2FwebOAuthRed';
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
    await page.waitForTimeout(2500);

    const evs = await page.evaluate(() => window.__EV.map(e => e.t));
    if (!report('脚本在 CAS 页运行并请求凭据', evs.includes('casNeedCreds'), JSON.stringify(evs))) failures++;

    // 模拟 native 通过 evaluateJavascript 下发凭据
    await page.evaluate(() => window.__ZW.fillCas('20230001', 'MyPassw0rd!'));
    await page.waitForTimeout(800);

    const r = await page.evaluate(() => {
      const vis = el => el && el.getClientRects().length > 0;
      const boxes = [...document.querySelectorAll('input[name=rememberMe]')];
      const un = [...document.querySelectorAll('#username, input[name=username]')].filter(vis);
      const pw = [...document.querySelectorAll('#passwordShow')].filter(vis);
      const cap = [...document.querySelectorAll('#authcode')].filter(vis);
      return {
        total: boxes.length,
        checked: boxes.filter(b => b.checked).length,
        username: un.length ? un[0].value : null,
        password: pw.length ? pw[0].value : null,
        passwordType: pw.length ? pw[0].type : null,
        captchaVisible: cap.length > 0,
        focus: document.activeElement ? document.activeElement.id : ''
      };
    });
    console.log('  状态:', JSON.stringify(r));
    if (!report('「7天内自动登录」已自动勾选', r.total > 0 && r.checked === r.total, r.checked + '/' + r.total)) failures++;
    if (!report('学工号已填入', r.username === '20230001', String(r.username))) failures++;
    if (!report('密码已填入真实输入框', r.password === 'MyPassw0rd!', String(r.password))) failures++;
    if (!report('验证码保持人工输入（不自动提交）', r.captchaVisible === true && r.focus === 'authcode', 'focus=' + r.focus)) failures++;

    await page.screenshot({ path: '/tmp/cas_filled.png' });

    // 提交时抓取凭据回传（模拟用户点登录）；拦住真实提交以免跳转打断断言
    await page.evaluate(() => {
      document.addEventListener('submit', e => e.preventDefault(), true);
      const b = [...document.querySelectorAll('#passbutton, input[type=submit]')]
        .filter(e => e.getClientRects().length)[0];
      if (b) { b.click(); }
    });
    await page.waitForTimeout(400);
    const credsEv = await page.evaluate(() => window.__EV.filter(e => e.t === 'creds'));
    if (!report('点击登录时把凭据回传（供下次预填）', credsEv.length > 0,
        JSON.stringify(credsEv[0] || {}))) failures++;
    await ctx.close();
  }

  /* ---------------- Test 2: seat SPA with a seeded token ---------------- */
  console.log('\n=== 测试 2：座位系统 SPA（zwlib.ruc.edu.cn/jsq-v） ===');
  {
    const sys = await (await fetch('https://zwlib.ruc.edu.cn/jsq/static/public/cg/getSysSet/PC', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}'
    })).json();
    const seed = JSON.stringify({
      token: 'FAKE-EXPIRED-TOKEN',
      loginType: 'login',
      systemInfo: JSON.stringify(sys.data),
      vueConfig: JSON.stringify(sys.data.vueConfig),
      userInfo: JSON.stringify({ name: 't', cycleTimeName: '30天' }),
      currentBook: '{}'
    });

    const ctx = await browser.newContext({ viewport: { width: 420, height: 900 } });
    const page = await ctx.newPage();
    const seen = [];   // 事件日志要跨导航存活，所以走 console 通道
    page.on('console', m => { const t = m.text(); if (t.indexOf('__ZWEV__') === 0) seen.push(t.slice(8)); });

    // stub 像真实 native 一样，收到 casNeedCreds 就把凭据推回去
    await page.addInitScript(`
      window.__EV=[]; window.ZWNative={
        getState: function(){return ${JSON.stringify(JSON.stringify({ auto: true, hasCreds: true, user: '20230001' }))};},
        getSeed: function(){return ${JSON.stringify(seed)};},
        onEvent: function(j){
          var o; try{o=JSON.parse(j);}catch(e){return;}
          window.__EV.push(o); console.log('__ZWEV__' + o.t + (o.v ? ':' + o.v : ''));
          if(o.t==='casNeedCreds' && window.__ZW && window.__ZW.fillCas){
            window.__ZW.fillCas('20230001','MyPassw0rd!');
          }
        },
        toast: function(){}};
    `);
    await page.addInitScript(ZW);
    await page.goto('https://zwlib.ruc.edu.cn/jsq-v/#/main/home', { waitUntil: 'domcontentloaded', timeout: 45000 });

    // (1) seed 生效：SPA 直接进入首页，没有被弹去统一认证
    try {
      await page.waitForFunction(
        () => window.__EV.some(e => e.t === 'seeded') || location.hostname === 'cas.ruc.edu.cn',
        null, { timeout: 8000 });
    } catch (e) {}
    const spaLanded = seen.some(x => x === 'seeded') && seen.some(x => x === 'route:/main/home');
    if (!report('seed 的 token 让 SPA 直接进首页，跳过统一认证跳转', spaLanded, seen.join(' | '))) failures++;

    // (2) token 失效后自动回到统一认证登录页
    try {
      await page.waitForURL(/cas\.ruc\.edu\.cn/, { timeout: 20000 });
      report('token 失效时自动回到统一认证页（不自愈失败）', true, page.url().slice(0, 60));
    } catch (e) {
      report('token 失效时自动回到统一认证页（不自愈失败）', false, page.url().slice(0, 60));
      failures++;
    }

    // (3) 兜底：CAS 页面被预填 + 自动勾选 7 天
    await page.waitForTimeout(3000);
    const f = await page.evaluate(() => {
      const vis = el => el && el.getClientRects().length > 0;
      const boxes = [...document.querySelectorAll('input[name=rememberMe]')];
      const un = [...document.querySelectorAll('#username, input[name=username]')].filter(vis);
      const pw = [...document.querySelectorAll('#passwordShow')].filter(vis);
      return {
        checked: boxes.filter(b => b.checked).length, total: boxes.length,
        u: un.length ? un[0].value : null,
        p: pw.length ? pw[0].value : null,
        focus: document.activeElement ? document.activeElement.id : ''
      };
    });
    console.log('  CAS 兜底状态:', JSON.stringify(f));
    if (!report('兜底：CAS 页学工号/密码已自动预填',
        f.u === '20230001' && f.p === 'MyPassw0rd!', JSON.stringify(f))) failures++;
    if (!report('兜底：CAS 页「7天内自动登录」已勾选',
        f.total > 0 && f.checked === f.total, f.checked + '/' + f.total)) failures++;

    await page.screenshot({ path: '/tmp/spa_dead2.png' });
    await ctx.close();
  }

  await browser.close();
  console.log('\n结果: ' + (failures === 0 ? '全部通过' : failures + ' 项失败'));
  process.exit(failures === 0 ? 0 : 1);
})();

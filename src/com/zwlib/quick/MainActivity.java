package com.zwlib.quick;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.graphics.Typeface;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import java.io.File;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends Activity {

    /** 通用前缀探测：PC 包 jsq_p- / 移动包 jsq_m-，不写死。 */
    private static final String SNAPSHOT_JS = "(function(){try{var o={},i;for(i=0;i<sessionStorage.length;i++){var k=sessionStorage.key(i);var m=k&&k.match(/^(jsq_[a-zA-Z0-9]+-)(.+)$/);if(m){var s=m[2];if(s==='queryParam'||s==='showNotice')continue;var v=sessionStorage.getItem(k);if(v&&v.length<200000)o[s]=v;}}return JSON.stringify(o);}catch(e){return '';}})()";

    private static final String TAG = "ZwlibQuick";

    private static final String SPA = "https://zwlib.ruc.edu.cn/jsq-v/";
    private static final String HOME = SPA + "#/main/home";   // NB: the site has no /main/index route
    private static final String LOGIN_PAGE = SPA + "#/login";
    private static final String PROBE = "https://zwlib.ruc.edu.cn/jsq/static/frontApi/user/getUserInfo";
    private static final String CAS_HOST = "cas.ruc.edu.cn";
    private static final String CAS = "https://zwlib.ruc.edu.cn/rem/static/sso/login"
            + "?redirectUrl=https://zwlib.ruc.edu.cn/jsq-v";
    private static final String HOST_SUFFIX = "ruc.edu.cn";

    private static final int REQUEST_FILE_CHOOSER = 1001;
    private static final int REQUEST_CAMERA_PERMISSION = 1002;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private WebView web;
    private ProgressBar bar;
    private View splash;
    private View gear;

    private SecureStore sec;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String jsAsset = "";
    private String seedJson;              // session snapshot handed to the page, or null
    private volatile String route = "";   // current hash route reported by the page
    private boolean fillDone;             // one autofill attempt per document
    private boolean usedAutofill;         // to phrase the success toast

    private String pendingUser;           // memory only, until the user opted in
    private String pendingPass;
    private long lastBackPress;
    private volatile boolean signing;     // 手动签到在跑：连点不重复调接口
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraImageUri;
    private PermissionRequest pendingWebPermissionRequest;
    private AlertDialog controlCenterDialog;
    private AlertDialog checkInDialog;
    private AlertDialog bookDialog;
    private AlertDialog lastDialog;

    /* ------------------------------------------------------------------ */
    /* lifecycle                                                          */
    /* ------------------------------------------------------------------ */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sec = new SecureStore(this);
        prefs = getSharedPreferences("zw_flags", MODE_PRIVATE);
        jsAsset = readAsset("zw.js");

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(0xFF8C1B22);
            w.setNavigationBarColor(0xFFFFFFFF);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configureWebView();
        CookieManager.getInstance().setAcceptCookie(true);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressDrawable(getResources().getDrawable(R.drawable.progress_brand));
        bar.setVisibility(View.GONE);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        root.addView(bar, barLp);

        root.addView(buildGear(), gearLayoutParams());
        // always reachable: the user is normally signed in, i.e. never on /login
        gear.setAlpha(0.85f);
        makeDraggable(gear);
        restoreGearPos(root);

        splash = buildSplash();
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        if (savedInstanceState != null) {
            seedJson = null;
            web.restoreState(savedInstanceState);
            hideSplash();
        } else {
            startUp();
        }

        maybeAskConsent();
        try {
            Scheduler.apply(this);
        } catch (Throwable ignored) {
        }
        try {
            Scheduler.armWatchByCfg(this);
        } catch (Throwable ignored) {
        }
    }

    private void startUp() {
        final String session = autoOn() ? sec.get("session") : null;
        if (session == null) {
            // nothing remembered yet: behave like a plain browser and let the user log in
            load(HOME);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String token = Json.field(session, "token");
                final String ltype = Json.field(session, "loginType");
                int st = token == null ? Probe.EXPIRED : probe(token);
                final String url;
                if (st != Probe.EXPIRED) {
                    seedJson = session;                       // token still good: straight in
                    url = HOME;
                } else {
                    sec.remove("session");                    // stale, never seed it again
                    seedJson = null;
                    boolean creds = hasCreds();
                    if (creds && "login".equals(ltype)) {
                        url = LOGIN_PAGE;                     // the SPA's own password form
                    } else if ("cas".equals(ltype) || "remLogin".equals(ltype)) {
                        url = CAS;                            // silent CAS round trip when the ticket lives
                    } else {
                        url = HOME;
                    }
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        load(url);
                    }
                });
            }
        }, "zw-probe").start();
    }

    private void load(String url) {
        if (web == null) {
            return;
        }
        web.loadUrl(url);
    }

    /* ------------------------------------------------------------------ */
    /* session probe (native, runs before the WebView is committed)        */
    /* ------------------------------------------------------------------ */

    private static final class Probe {
        static final int VALID = 1;
        static final int EXPIRED = 2;
        static final int UNKNOWN = 3;
    }

    private int probe(String token) {
        // 必须带 cookie：服务端可能把 token 绑在 HTTP session 上
        try {
            Net.Jar jar = Booker.loadJar(sec);
            jar.seed(Booker.AUTH_URL);
            return Booker.probe(token, jar);
        } catch (Throwable t) {
            return Probe.UNKNOWN;
        }
    }

    @SuppressWarnings("unused")
    private int probeLegacy(String token) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(PROBE).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            c.setRequestProperty("loginType", "PC");
            c.setRequestProperty("token", token);
            OutputStream os = c.getOutputStream();
            os.write("{}".getBytes(UTF8));
            os.close();
            if (c.getResponseCode() != 200) {
                return Probe.UNKNOWN;
            }
            String body = read(c.getInputStream());
            if (body.contains("20003") || body.contains("20002")) {
                return Probe.EXPIRED;
            }
            if (body.contains("\"status\" : true") || body.contains("\"status\":true")) {
                return Probe.VALID;
            }
            return Probe.UNKNOWN;
        } catch (Exception e) {
            return Probe.UNKNOWN;                 // network hiccup: give the token a chance
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* document-start injection                                           */
    /* ------------------------------------------------------------------ */

    private WebResourceResponse intercept(WebResourceRequest req) {
        try {
            if (req == null || !req.isForMainFrame() || !"GET".equalsIgnoreCase(req.getMethod())) {
                return null;
            }
            String url = req.getUrl().toString();
            if (!isInjectable(url) || jsAsset.isEmpty()) {
                return null;
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("User-Agent", web.getSettings().getUserAgentString());
            if (c.getResponseCode() != 200) {
                c.disconnect();
                return null;
            }
            String html = read(c.getInputStream());
            c.disconnect();
            if (html.isEmpty()) {
                return null;
            }
            String injected = injectScript(html);
            Log.d(TAG, "injected " + url);
            return new WebResourceResponse("text/html", "utf-8", 200, "OK",
                    new HashMap<String, String>(), new ByteArrayInputStream(injected.getBytes(UTF8)));
        } catch (Throwable t) {
            Log.w(TAG, "intercept failed: " + t);
            return null;                          // always fall back to the real request
        }
    }

    private boolean isInjectable(String url) {
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost() == null ? "" : u.getHost();
            String path = u.getPath() == null ? "" : u.getPath();
            if (CAS_HOST.equals(host)) {
                return path.startsWith("/cas/login");       // login page only, never logout
            }
            if (!host.endsWith(HOST_SUFFIX) || !path.startsWith("/jsq-v")) {
                return false;
            }
            return path.equals("/jsq-v") || path.equals("/jsq-v/")
                    || path.endsWith("/") || path.endsWith(".html");
        } catch (Exception e) {
            return false;
        }
    }

    private String injectScript(String html) {
        String tag = "<script>" + jsAsset + "</script>";
        int i = indexOfIgnoreCase(html, "<head>");
        if (i >= 0) {
            return html.substring(0, i + 6) + tag + html.substring(i + 6);
        }
        i = indexOfIgnoreCase(html, "<head");
        if (i >= 0) {
            int j = html.indexOf('>', i);
            if (j > 0) {
                return html.substring(0, j + 1) + tag + html.substring(j + 1);
            }
        }
        return tag + html;
    }

    private static int indexOfIgnoreCase(String hay, String needle) {
        return hay.toLowerCase().indexOf(needle.toLowerCase());
    }

    /* ------------------------------------------------------------------ */
    /* bridge                                                             */
    /* ------------------------------------------------------------------ */

    private class Bridge {

        /** Session snapshot the page should restore into sessionStorage. */
        @JavascriptInterface
        public String getSeed() {
            return seedJson == null ? "" : seedJson;
        }

        /** Non-secret state (never returns the password). */
        @JavascriptInterface
        public String getState() {
            try {
                JSONObject o = new JSONObject();
                o.put("auto", autoOn());
                o.put("hasCreds", hasCreds());
                o.put("user", sec.get("user") == null ? "" : sec.get("user"));
                o.put("cas", CAS);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void onEvent(String json) {
            try {
                onPageEvent(new JSONObject(json));
            } catch (Exception e) {
                Log.w(TAG, "bad event " + json + " " + e);
            }
        }

        @JavascriptInterface
        public void toast(final String msg) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void onPageEvent(JSONObject o) {
        final String type = o.optString("t");
        Log.d(TAG, "ev " + type + " " + o.optString("v", ""));
        if ("route".equals(type)) {
            route = o.optString("v");
            ui.post(new Runnable() {
                @Override
                public void run() {
                    gear.bringToFront();
                }
            });
            if ("/login".equals(route)) {
                maybeAutofill(400);
            }
            return;
        }
        if ("login".equals(type)) {
            final String session = o.optString("session", "");
            if (session.length() > 2) {
                sec.put("session", session);
            }
            if (autoOn()) {
                if (pendingUser != null && pendingPass != null) {
                    sec.put("user", pendingUser);
                    sec.put("pass", pendingPass);
                }
                if (usedAutofill) {
                    usedAutofill = false;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "已自动登录", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
            CookieManager.getInstance().flush();
            return;
        }
        if ("tokenGone".equals(type)) {
            // 20003: our restored token was refused. Drop it so the next launch does not
            // re-seed a dead session and bounce through the same error.
            sec.remove("session");
            seedJson = null;
            if (autoOn() && hasCreds()) {
                maybeAutofill(1200);      // the page is about to land on /login
            }
            return;
        }
        if ("creds".equals(type)) {
            pendingUser = o.optString("u", null);
            pendingPass = o.optString("p", null);
            if (autoOn() && pendingUser != null && pendingPass != null) {
                sec.put("user", pendingUser);
                sec.put("pass", pendingPass);
            }
            return;
        }
        if ("casNeedCreds".equals(type)) {
            // reaching the CAS form means the remembered seat token is dead
            sec.remove("session");
            seedJson = null;
            pushCasCreds();
            return;
        }
        if ("needCaptcha".equals(type) || "loginFail".equals(type)) {
            final String m = "needCaptcha".equals(type)
                    ? "需要验证码，请手动完成登录" : "自动登录失败，请手动登录";
            ui.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /** Push the remembered 学工号/密码 into the CAS login form (captcha stays manual). */
    private void pushCasCreds() {
        final String u = sec.get("user");
        final String p = sec.get("pass");
        if (u == null || !autoOn()) {
            return;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    web.evaluateJavascript("window.__ZW&&window.__ZW.fillCas("
                            + JSONObject.quote(u) + "," + JSONObject.quote(p == null ? "" : p) + ")", null);
                } catch (Exception e) {
                    Log.w(TAG, "fillCas " + e);
                }
            }
        });
    }

    private void maybeAutofill(int delayMs) {
        if (!autoOn() || !hasCreds() || fillDone || web == null) {
            return;
        }
        final String u = sec.get("user");
        final String p = sec.get("pass");
        if (u == null || p == null) {
            return;
        }
        fillDone = true;
        usedAutofill = true;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                String js = "window.__ZW&&window.__ZW.autofill("
                        + JSONObject.quote(u) + "," + JSONObject.quote(p) + ")";
                try {
                    web.evaluateJavascript(js, null);
                } catch (Exception e) {
                    Log.w(TAG, "autofill " + e);
                }
            }
        }, delayMs);
    }

    /* ------------------------------------------------------------------ */
    /* consent + options                                                  */
    /* ------------------------------------------------------------------ */

    private boolean autoOn() {
        return SecureStore.available() && prefs.getBoolean("auto", false);
    }

    private boolean hasCreds() {
        return sec.get("user") != null && sec.get("pass") != null;
    }

    private void maybeAskConsent() {
        if (prefs.getBoolean("asked", false)) {
            return;
        }
        prefs.edit().putBoolean("asked", true).apply();
        if (!SecureStore.available()) {
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("开启免密登录")
                        .setMessage("开启后：\n\n"
                                + "· 自动勾选统一认证页的「7天内自动登录」，7 天内打开 App 直接进入，不用输密码\n"
                                + "· 记住学工号和密码，7 天到期后自动填好，只需再输一次验证码\n"
                                + "· 座位系统登录态过期时自动续上，不再弹「重新触发统一认证」\n\n"
                                + "密码用系统 Keystore 加密，只存在这台设备上；"
                                + "登录页右下角的 ⚙ 可以随时关闭并清除。")
                        .setCancelable(false)
                        .setPositiveButton("开启", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int i) {
                                prefs.edit().putBoolean("auto", true).apply();
                                Toast.makeText(MainActivity.this,
                                        "首次请手动登录一次，之后就会自动登录", Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("暂不", null)
                        .show();
            }
        }, 600);
    }

    /** 玻璃质感底：渐变 + 浅色描边 + 阴影（真正的背景模糊 Android View 体系做不到） */
    private Drawable glassBg(int radiusDp, int top, int bottom, int stroke) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{top, bottom});
        d.setShape(radiusDp < 0 ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (radiusDp >= 0) {
            d.setCornerRadius(dp(radiusDp));
        }
        d.setStroke(dp(1), stroke);
        return d;
    }

    private Drawable cardBg(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private Drawable ripple(Drawable inner) {
        return new RippleDrawable(ColorStateList.valueOf(0x148C1B22), inner, null);
    }

    private View buildGear() {
        ImageView g = new ImageView(this);
        g.setImageResource(R.drawable.ic_gear);
        g.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        g.setPadding(dp(12), dp(12), dp(12), dp(12));
        g.setBackground(glassBg(-1, 0xF7FFFFFF, 0xE3EFEFF2, 0x4DFFFFFF));
        g.setElevation(dp(8));
        g.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOptions(v);
            }
        });
        gear = g;
        return g;
    }

    /** Floating button: tap to open settings, drag anywhere and it remembers where. */
    private void makeDraggable(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY;
            int startLeft, startTop;
            boolean dragging;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                View parent = (View) view.getParent();
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startLeft = view.getLeft();
                        startTop = view.getTop();
                        dragging = false;
                        view.setAlpha(1f);
                        view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downRawX;
                        float dy = e.getRawY() - downRawY;
                        if (!dragging && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) {
                            dragging = true;
                        }
                        if (!dragging) {
                            return true;
                        }
                        int maxX = Math.max(0, parent.getWidth() - view.getWidth());
                        int maxY = Math.max(0, parent.getHeight() - view.getHeight());
                        int nx = (int) Math.max(0, Math.min(maxX, startLeft + dx));
                        int ny = (int) Math.max(0, Math.min(maxY, startTop + dy));
                        lp.gravity = Gravity.TOP | Gravity.START;
                        lp.leftMargin = nx;
                        lp.topMargin = ny;
                        view.setLayoutParams(lp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.setAlpha(0.92f);
                        view.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
                        if (dragging) {
                            prefs.edit().putInt("gear_x", lp.leftMargin)
                                    .putInt("gear_y", lp.topMargin).apply();
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            view.performClick();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void restoreGearPos(final View root) {
        root.post(new Runnable() {
            @Override
            public void run() {
                int x = prefs.getInt("gear_x", -1);
                int y = prefs.getInt("gear_y", -1);
                if (x < 0 || y < 0 || gear == null) {
                    return;
                }
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) gear.getLayoutParams();
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.leftMargin = Math.max(0, Math.min(x, root.getWidth() - gear.getWidth()));
                lp.topMargin = Math.max(0, Math.min(y, root.getHeight() - gear.getHeight()));
                gear.setLayoutParams(lp);
            }
        });
    }

    private FrameLayout.LayoutParams gearLayoutParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(44), dp(44));
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = dp(14);
        lp.bottomMargin = dp(24);
        return lp;
    }

    private void showOptions(View anchor) {
        withFreshSession(new Runnable() {
            @Override
            public void run() {
                showControlCenter();
            }
        });
    }

    private void showControlCenter() {
        refreshControlCenter();
    }

    private void refreshControlCenter() {
        if (controlCenterDialog != null) {
            try {
                controlCenterDialog.dismiss();
            } catch (Throwable ignored) {
            }
            controlCenterDialog = null;
        }

        final Booker.Cfg cfg = Booker.Cfg.load(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, dp(14), p, dp(16));

        // 1. 顶部 Header：标题 + 登录态指示药丸
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(4), dp(4), dp(12));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView hTitle = new TextView(this);
        hTitle.setText("功能控制中心");
        hTitle.setTextSize(20f);
        hTitle.setTypeface(Typeface.DEFAULT_BOLD);
        hTitle.setTextColor(0xFF1A1A1A);
        titleCol.addView(hTitle);

        TextView hSub = new TextView(this);
        hSub.setText("人大图书馆座位助手");
        hSub.setTextSize(12f);
        hSub.setTextColor(0xFF8A8A8E);
        hSub.setPadding(0, dp(2), 0, 0);
        titleCol.addView(hSub);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final boolean hasSession = sec.get("session") != null;
        TextView statusPill = buildBadgeView(hasSession ? "● 已登录" : "○ 未登录",
                hasSession ? 0xFFE3F5EA : 0xFFECECEE,
                hasSession ? 0xFF12683C : 0xFF8A8A8E);
        statusPill.setClickable(true);
        statusPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                withFreshSession(new Runnable() {
                    @Override
                    public void run() {
                        checkLogin();
                    }
                });
            }
        });
        header.addView(statusPill);
        box.addView(header);

        // 2. 核心模块一：【入馆签到 & 座位守护】（重点单列！）
        String lastWatch = sec.get("ci_last");
        String signStateText;
        int signStateBg;
        int signStateFg;
        if (lastWatch != null && (lastWatch.contains("履约中") || lastWatch.contains("CHECK_IN"))) {
            signStateText = "履约中";
            signStateBg = 0xFFE3F5EA;
            signStateFg = 0xFF12683C;
        } else if (lastWatch != null && (lastWatch.contains("暂离") || lastWatch.contains("AWAY"))) {
            signStateText = "暂离中";
            signStateBg = 0xFFFFF3D6;
            signStateFg = 0xFF8A6D00;
        } else if (lastWatch != null && (lastWatch.contains("未签到") || lastWatch.contains("RESERVE"))) {
            signStateText = "未签到";
            signStateBg = 0xFFFFEBEB;
            signStateFg = 0xFFB00020;
        } else {
            signStateText = "待巡检";
            signStateBg = 0xFFECECEE;
            signStateFg = 0xFF8A8A8E;
        }

        LinearLayout cardCheckIn = new LinearLayout(this);
        cardCheckIn.setOrientation(LinearLayout.VERTICAL);
        cardCheckIn.setBackground(cardBg(0xFFFFFFFF, 0x14000000, 16));
        cardCheckIn.setElevation(dp(2));
        cardCheckIn.setPadding(dp(16), dp(15), dp(16), dp(15));

        LinearLayout ciHead = new LinearLayout(this);
        ciHead.setOrientation(LinearLayout.HORIZONTAL);
        ciHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView ciTitle = new TextView(this);
        ciTitle.setText("📌 入馆签到 & 座位守护");
        ciTitle.setTextSize(16f);
        ciTitle.setTypeface(Typeface.DEFAULT_BOLD);
        ciTitle.setTextColor(0xFF1A1A1A);
        ciHead.addView(ciTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView ciBadge = buildBadgeView(signStateText, signStateBg, signStateFg);
        ciHead.addView(ciBadge);
        cardCheckIn.addView(ciHead);

        TextView ciDesc = new TextView(this);
        ciDesc.setTextSize(13f);
        ciDesc.setTextColor(0xFF4A4A4A);
        ciDesc.setPadding(0, dp(8), 0, 0);
        ciDesc.setText(lastWatch != null && !lastWatch.isEmpty()
                ? lastWatch
                : "尚未巡检，进馆若漏刷闸机可直接点「立即签到」补签");
        cardCheckIn.addView(ciDesc);

        TextView ciTip = new TextView(this);
        ciTip.setTextSize(12f);
        ciTip.setPadding(0, dp(4), 0, dp(12));
        if (cfg.ciEnabled) {
            ciTip.setTextColor(0xFF12683C);
            ciTip.setText("● 自动签到守护中 · " + cfg.ciWindowText()
                    + (cfg.ciDry ? "（试运行只提醒）" : "（每 5 分钟巡检）"));
        } else {
            ciTip.setTextColor(0xFF8A8A8E);
            ciTip.setText("○ 自动签到未开启（每天 " + cfg.ciWindowText() + "）");
        }
        cardCheckIn.addView(ciTip);

        // 签到卡片底部双按钮
        LinearLayout ciBtnRow = new LinearLayout(this);
        ciBtnRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnSignNow = buildButton("⚡ 立即签到", 0xFF8C1B22, 0xFFFFFFFF, true, new Runnable() {
            @Override
            public void run() {
                signNow();
            }
        });
        LinearLayout.LayoutParams lpBtnSign = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f);
        lpBtnSign.rightMargin = dp(8);
        ciBtnRow.addView(btnSignNow, lpBtnSign);

        TextView btnCiDetail = buildButton("守护管理 ›", 0x148C1B22, 0xFF8C1B22, true, new Runnable() {
            @Override
            public void run() {
                showCheckInDialog();
            }
        });
        LinearLayout.LayoutParams lpBtnCiDetail = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        ciBtnRow.addView(btnCiDetail, lpBtnCiDetail);

        cardCheckIn.addView(ciBtnRow);

        LinearLayout.LayoutParams lpCardCi = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpCardCi.topMargin = dp(4);
        box.addView(cardCheckIn, lpCardCi);

        // 3. 核心模块二：【定时预约】
        LinearLayout cardBook = new LinearLayout(this);
        cardBook.setOrientation(LinearLayout.VERTICAL);
        cardBook.setBackground(cardBg(0xFFFFFFFF, 0x14000000, 16));
        cardBook.setElevation(dp(2));
        cardBook.setPadding(dp(16), dp(15), dp(16), dp(15));

        LinearLayout bkHead = new LinearLayout(this);
        bkHead.setOrientation(LinearLayout.HORIZONTAL);
        bkHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView bkTitle = new TextView(this);
        bkTitle.setText("⏰ 定时自动预约");
        bkTitle.setTextSize(16f);
        bkTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bkTitle.setTextColor(0xFF1A1A1A);
        bkHead.addView(bkTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String bkBadgeText;
        int bkBadgeBg;
        int bkBadgeFg;
        if (cfg.enabled) {
            if (cfg.dryRun) {
                bkBadgeText = "试运行 · " + cfg.timeText();
                bkBadgeBg = 0xFFFFF3D6;
                bkBadgeFg = 0xFF8A6D00;
            } else {
                bkBadgeText = "已开启 · " + cfg.timeText();
                bkBadgeBg = 0xFFE3F5EA;
                bkBadgeFg = 0xFF12683C;
            }
        } else {
            bkBadgeText = "未开启";
            bkBadgeBg = 0xFFECECEE;
            bkBadgeFg = 0xFF8A8A8E;
        }
        TextView bkBadge = buildBadgeView(bkBadgeText, bkBadgeBg, bkBadgeFg);
        bkHead.addView(bkBadge);
        cardBook.addView(bkHead);

        String venue = cfg.venueName == null || cfg.venueName.isEmpty() ? "未设置馆" : cfg.venueName;
        String area = cfg.roomName == null || cfg.roomName.isEmpty() ? "自动选区" : cfg.roomName;
        String dateTxt = cfg.dateLabel == null || cfg.dateLabel.isEmpty()
                ? (cfg.dateOffset == 0 ? "今天" : cfg.dateOffset + " 天后") : cfg.dateLabel;
        String seats = cfg.seatPriority == null || cfg.seatPriority.isEmpty()
                ? "座位自动" : "座位 " + cfg.seatPriority;

        TextView bkLine1 = new TextView(this);
        bkLine1.setTextSize(13f);
        bkLine1.setTextColor(0xFF4A4A4A);
        bkLine1.setPadding(0, dp(8), 0, 0);
        bkLine1.setText(venue + "  ·  " + area);
        cardBook.addView(bkLine1);

        TextView bkLine2 = new TextView(this);
        bkLine2.setTextSize(12f);
        bkLine2.setTextColor(0xFF8A8A8E);
        bkLine2.setPadding(0, dp(3), 0, dp(12));
        bkLine2.setText(dateTxt + "  ·  " + cfg.windowText() + "  ·  " + seats);
        cardBook.addView(bkLine2);

        // 预约卡片底部双按钮
        LinearLayout bkBtnRow = new LinearLayout(this);
        bkBtnRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnBkToggle = buildButton(
                cfg.enabled ? "停用预约" : "开启预约",
                cfg.enabled ? 0xFFECECEE : 0xFF8C1B22,
                cfg.enabled ? 0xFF6B6B70 : 0xFFFFFFFF,
                true, new Runnable() {
            @Override
            public void run() {
                if (!cfg.enabled) {
                    if (!cfg.hasWindow()) {
                        Toast.makeText(MainActivity.this, "先设时间段（请点预约配置）", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (cfg.venueId == null || cfg.venueId.isEmpty()) {
                        Toast.makeText(MainActivity.this, "先选馆（请点预约配置）", Toast.LENGTH_LONG).show();
                        return;
                    }
                    cfg.enabled = true;
                    cfg.save(MainActivity.this);
                    Scheduler.apply(MainActivity.this);
                    askNotifPermission();
                    Toast.makeText(MainActivity.this, "已启用：每天 " + cfg.timeText() + " 自动预约", Toast.LENGTH_SHORT).show();
                } else {
                    cfg.enabled = false;
                    cfg.save(MainActivity.this);
                    Scheduler.cancel(MainActivity.this);
                    Toast.makeText(MainActivity.this, "已停用定时预约", Toast.LENGTH_SHORT).show();
                }
                refreshControlCenter();
            }
        });
        LinearLayout.LayoutParams lpBtnBkToggle = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        lpBtnBkToggle.rightMargin = dp(8);
        bkBtnRow.addView(btnBkToggle, lpBtnBkToggle);

        TextView btnBkDetail = buildButton("预约配置 ›", 0xFFF2F2F4, 0xFF1A1A1A, true, new Runnable() {
            @Override
            public void run() {
                withFreshSession(new Runnable() {
                    @Override
                    public void run() {
                        showBookDialog();
                    }
                });
            }
        });
        LinearLayout.LayoutParams lpBtnBkDetail = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        bkBtnRow.addView(btnBkDetail, lpBtnBkDetail);

        cardBook.addView(bkBtnRow);

        LinearLayout.LayoutParams lpCardBk = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpCardBk.topMargin = dp(12);
        box.addView(cardBook, lpCardBk);

        // 4. 快捷工具与系统
        addSection(box, "快捷工具与系统");

        addItem(box, "自动登录", autoOn() ? "已开启 ✓（记住密码与 Token）" : "已关闭", new Runnable() {
            @Override
            public void run() {
                boolean on = !autoOn();
                prefs.edit().putBoolean("auto", on).apply();
                if (!on) {
                    sec.remove("user");
                    sec.remove("pass");
                    sec.remove("session");
                    seedJson = null;
                }
                Toast.makeText(MainActivity.this,
                        on ? "已开启，下次登录会记住密码" : "已关闭并清除已保存信息",
                        Toast.LENGTH_SHORT).show();
                refreshControlCenter();
            }
        });

        addItem(box, "立刻巡检当前座位", "查询服务端最新在馆与预约状态记录", new Runnable() {
            @Override
            public void run() {
                checkSeatNow();
            }
        });

        addItem(box, "预约流程演练（不下单）", "跑通完整选座与预约全流程，验证可用性", new Runnable() {
            @Override
            public void run() {
                withFreshSession(new Runnable() {
                    @Override
                    public void run() {
                        runDryRun();
                    }
                });
            }
        });

        addItem(box, "检查风控状态", "看此刻预约接口是否需要滑块/验证码", new Runnable() {
            @Override
            public void run() {
                withFreshSession(new Runnable() {
                    @Override
                    public void run() {
                        checkRisk();
                    }
                });
            }
        });

        addItem(box, "后台可靠性与权限",
                (!Scheduler.ignoringBatteryOptimizations(this) || !Scheduler.canExactAlarm(this))
                        ? "⚠ 权限不全，点此检查" : "省电白名单 & 精确闹钟权限正常 ✓",
                new Runnable() {
            @Override
            public void run() {
                showReliabilityDialog();
            }
        });

        addItem(box, "清除已保存信息", "清除本机保存的账号密码与会话快照", new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("清除保存信息")
                        .setMessage("确定要清除本机保存的学号、密码和 Session 快照吗？下次需要重新登录。")
                        .setPositiveButton("确定清除", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                sec.remove("user");
                                sec.remove("pass");
                                sec.remove("session");
                                seedJson = null;
                                pendingUser = null;
                                pendingPass = null;
                                Toast.makeText(MainActivity.this, "已清除本机保存的信息", Toast.LENGTH_SHORT).show();
                                refreshControlCenter();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        controlCenterDialog = new AlertDialog.Builder(this)
                .setView(sv)
                .setNegativeButton("关闭", null)
                .create();
        controlCenterDialog.show();
        if (controlCenterDialog.getWindow() != null) {
            controlCenterDialog.getWindow().setBackgroundDrawable(cardBg(0xFFF4F4F6, 0x00000000, 22));
        }
    }

    /* ------------------------------------------------------------------ */
    /* 座位守护与签到（单列专属面板）                                     */
    /* ------------------------------------------------------------------ */

    private void showCheckInDialog() {
        refreshCheckInDialog();
    }

    private void refreshCheckInDialog() {
        if (checkInDialog != null) {
            try {
                checkInDialog.dismiss();
            } catch (Throwable ignored) {
            }
            checkInDialog = null;
        }

        final Booker.Cfg cfg = Booker.Cfg.load(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(14), p, dp(16));

        String lastWatch = sec.get("ci_last");
        String bigStatusTitle;
        int statusColor;
        if (lastWatch != null && (lastWatch.contains("履约中") || lastWatch.contains("CHECK_IN"))) {
            bigStatusTitle = "🟢 当前履约中（已签到）";
            statusColor = 0xFF12683C;
        } else if (lastWatch != null && (lastWatch.contains("暂离") || lastWatch.contains("AWAY"))) {
            bigStatusTitle = "🟠 当前状态为「暂离」";
            statusColor = 0xFF8A6D00;
        } else if (lastWatch != null && (lastWatch.contains("未签到") || lastWatch.contains("RESERVE"))) {
            bigStatusTitle = "🔴 已预约待签到";
            statusColor = 0xFFB00020;
        } else {
            bigStatusTitle = "⚪ 暂无在座预约记录";
            statusColor = 0xFF6B6B70;
        }

        // 顶部在馆状态大卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg(0xFFFFFFFF, 0x14000000, 18));
        card.setElevation(dp(2));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));

        TextView big = new TextView(this);
        big.setText(bigStatusTitle);
        big.setTextSize(19f);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextColor(statusColor);
        card.addView(big);

        TextView l1 = new TextView(this);
        l1.setTextSize(13.5f);
        l1.setTextColor(0xFF4A4A4A);
        l1.setPadding(0, dp(8), 0, 0);
        l1.setText(lastWatch != null && !lastWatch.isEmpty()
                ? lastWatch
                : "尚未巡检（可点下方按钮立刻向服务端查询一次）");
        card.addView(l1);

        // 刷新状态按钮
        LinearLayout refreshRow = new LinearLayout(this);
        refreshRow.setOrientation(LinearLayout.HORIZONTAL);
        refreshRow.setGravity(Gravity.END);
        refreshRow.setPadding(0, dp(10), 0, 0);

        TextView btnRefresh = buildButton("🔄 刷新在馆状态", 0x148C1B22, 0xFF8C1B22, true, new Runnable() {
            @Override
            public void run() {
                checkSeatNow();
            }
        });
        btnRefresh.setTextSize(12.5f);
        btnRefresh.setPadding(dp(12), dp(6), dp(12), dp(6));
        refreshRow.addView(btnRefresh);
        card.addView(refreshRow);

        box.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 核心操作：立即签到大按钮
        addPrimary(box, "立即签到（远程入馆）", 0xFF8C1B22, new Runnable() {
            @Override
            public void run() {
                signNow();
            }
        });

        TextView signTip = new TextView(this);
        signTip.setTextSize(12f);
        signTip.setTextColor(0xFF8A8A8E);
        signTip.setPadding(dp(4), dp(6), dp(4), dp(4));
        signTip.setText("人在座但闸机漏刷？或需要提前入馆？点此直接调用签到接口。点击即真签。");
        box.addView(signTip);

        // 分组：座位守护（暂离自动返回）
        addSection(box, "座位守护（暂离自动返回）");

        addItem(box, "暂离自动返回",
                cfg.ciEnabled ? ("已开启 —— 每天 " + cfg.ciWindowText() + " 巡检") : "已关闭", new Runnable() {
            @Override
            public void run() {
                if (!cfg.ciEnabled) {
                    if (sec.get("session") == null) {
                        Toast.makeText(MainActivity.this, "先回页面登录一次", Toast.LENGTH_LONG).show();
                        return;
                    }
                    cfg.ciEnabled = true;
                    cfg.save(MainActivity.this);
                    askNotifPermission();
                    Scheduler.armWatchByCfg(MainActivity.this);
                    Toast.makeText(MainActivity.this,
                            "已开启自动签到守护：在守护时段（" + cfg.ciWindowText()
                                    + "）内每 5 分钟查一次状态，发现「暂离」就自动调返回接口。"
                                    + (cfg.ciDry ? "\n\n守护试运行还开着 —— 只记录，不会真的调。" : ""),
                            Toast.LENGTH_LONG).show();
                } else {
                    cfg.ciEnabled = false;
                    cfg.save(MainActivity.this);
                    Scheduler.armWatch(MainActivity.this, 0);
                    Toast.makeText(MainActivity.this, "已关闭守护", Toast.LENGTH_SHORT).show();
                }
                refreshCheckInDialog();
            }
        });

        addItem(box, "守护时间段", cfg.ciWindowText(), new Runnable() {
            @Override
            public void run() {
                new android.app.TimePickerDialog(MainActivity.this,
                        new android.app.TimePickerDialog.OnTimeSetListener() {
                            @Override
                            public void onTimeSet(android.widget.TimePicker v, int h, int min) {
                                cfg.ciBeginMinute = h * 60 + min;
                                new android.app.TimePickerDialog(MainActivity.this,
                                        new android.app.TimePickerDialog.OnTimeSetListener() {
                                            @Override
                                            public void onTimeSet(android.widget.TimePicker v2, int h2, int m2) {
                                                if (h2 * 60 + m2 <= cfg.ciBeginMinute) {
                                                    Toast.makeText(MainActivity.this, "结束时间必须晚于开始时间", Toast.LENGTH_LONG).show();
                                                    return;
                                                }
                                                cfg.ciEndMinute = h2 * 60 + m2;
                                                cfg.save(MainActivity.this);
                                                if (cfg.ciEnabled) {
                                                    Scheduler.armWatchByCfg(MainActivity.this);
                                                }
                                                refreshCheckInDialog();
                                            }
                                        }, cfg.ciEndMinute / 60, cfg.ciEndMinute % 60, true).show();
                            }
                        }, cfg.ciBeginMinute / 60, cfg.ciBeginMinute % 60, true).show();
            }
        });

        addItem(box, "守护试运行", cfg.ciDry
                        ? "已开启 —— 只记录不调接口" : "已关闭 —— 发现暂离会真的调返回",
                new Runnable() {
                    @Override
                    public void run() {
                        cfg.ciDry = !cfg.ciDry;
                        cfg.save(MainActivity.this);
                        if (!cfg.ciDry) {
                            Toast.makeText(MainActivity.this,
                                    "已关闭守护试运行：巡检到「暂离」会真的调返回接口",
                                    Toast.LENGTH_LONG).show();
                        }
                        refreshCheckInDialog();
                    }
                });

        // 分组：暂离时限
        addSection(box, "暂离时限（算「座位被释放」用）");

        addItem(box, "平时暂离时限", cfg.ciGraceMin + " 分钟",
                new Runnable() {
                    @Override
                    public void run() {
                        editMinutes(cfg, true);
                    }
                });

        addItem(box, "饭点暂离时限", cfg.ciMealGraceMin + " 分钟",
                new Runnable() {
                    @Override
                    public void run() {
                        editMinutes(cfg, false);
                    }
                });

        addItem(box, "饭点时段", Booker.hhmm(cfg.ciMealStartMin) + " - "
                + Booker.hhmm(cfg.ciMealEndMin), new Runnable() {
            @Override
            public void run() {
                new android.app.TimePickerDialog(MainActivity.this,
                        new android.app.TimePickerDialog.OnTimeSetListener() {
                            @Override
                            public void onTimeSet(android.widget.TimePicker v, int h, int min) {
                                cfg.ciMealStartMin = h * 60 + min;
                                new android.app.TimePickerDialog(MainActivity.this,
                                        new android.app.TimePickerDialog.OnTimeSetListener() {
                                            @Override
                                            public void onTimeSet(android.widget.TimePicker v2,
                                                                  int h2, int m2) {
                                                cfg.ciMealEndMin = h2 * 60 + m2;
                                                cfg.save(MainActivity.this);
                                                refreshCheckInDialog();
                                            }
                                        }, cfg.ciMealEndMin / 60, cfg.ciMealEndMin % 60, true).show();
                            }
                        }, cfg.ciMealStartMin / 60, cfg.ciMealStartMin % 60, true).show();
            }
        });

        // 分组：规则说明
        addSection(box, "规则说明");

        LinearLayout ruleCard = new LinearLayout(this);
        ruleCard.setOrientation(LinearLayout.VERTICAL);
        ruleCard.setBackground(cardBg(0xFFFFFFFF, 0x14000000, 14));
        ruleCard.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView ruleText = new TextView(this);
        ruleText.setTextSize(12.5f);
        ruleText.setTextColor(0xFF6B6B70);
        ruleText.setLineSpacing(dp(3), 1f);
        ruleText.setText("• 图书馆规则：出馆闸机刷卡会记「暂离」；回馆若闸机漏刷，系统会一直记暂离直到时限耗尽释放座位并记早退违约。\n\n"
                + "• 守护机制：在守护时段（独立设置，默认 07:00-22:30）内自动巡检，发现暂离先发通知询问，临近释放前 10 分钟自动调接口替你签到返回。\n\n"
                + "• 人在座位上：若您人已在座，可随时点击上方的「立即签到」直接变为履约中。");
        ruleCard.addView(ruleText);

        LinearLayout.LayoutParams lpRule = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpRule.topMargin = dp(4);
        box.addView(ruleCard, lpRule);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        checkInDialog = new AlertDialog.Builder(this)
                .setTitle("座位守护与签到")
                .setView(sv)
                .setNegativeButton("关闭", null)
                .setNeutralButton("返回控制中心", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showControlCenter();
                    }
                })
                .create();
        checkInDialog.show();
        if (checkInDialog.getWindow() != null) {
            checkInDialog.getWindow().setBackgroundDrawable(cardBg(0xFFF4F4F6, 0x00000000, 22));
        }
    }

    /* ------------------------------------------------------------------ */
    /* 定时预约（专属配置面板）                                           */
    /* ------------------------------------------------------------------ */

    private void askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
        }
    }

    private boolean hasCameraPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestCameraPermission(int requestCode) {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, requestCode);
        }
    }

    private void showBookDialog() {
        refreshBookDialog();
    }

    private void refreshBookDialog() {
        if (bookDialog != null) {
            try {
                bookDialog.dismiss();
            } catch (Throwable ignored) {
            }
            bookDialog = null;
        }
        final Booker.Cfg cfg = Booker.Cfg.load(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(10), p, dp(16));

        String venue = cfg.venueName == null || cfg.venueName.isEmpty() ? "馆未设置" : cfg.venueName;
        String area = cfg.roomName == null || cfg.roomName.isEmpty() ? "自动选区" : cfg.roomName;
        String dateTxt = cfg.dateLabel == null || cfg.dateLabel.isEmpty()
                ? (cfg.dateOffset == 0 ? "今天" : cfg.dateOffset + " 天后") : cfg.dateLabel;
        String seats = cfg.seatPriority == null || cfg.seatPriority.isEmpty()
                ? "座位自动" : "座位 " + cfg.seatPriority;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg(0xFFFFFFFF, 0x14000000, 18));
        card.setElevation(dp(1));
        card.setPadding(dp(18), dp(16), dp(18), dp(18));

        TextView big = new TextView(this);
        big.setText("每天 " + cfg.timeText());
        big.setTextSize(23f);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextColor(0xFF8C1B22);
        card.addView(big);

        TextView l1 = new TextView(this);
        l1.setTextSize(13.5f);
        l1.setTextColor(0xFF6B6B70);
        l1.setPadding(0, dp(7), 0, 0);
        l1.setText(venue + "  ·  " + area);
        card.addView(l1);

        TextView l2 = new TextView(this);
        l2.setTextSize(13.5f);
        l2.setTextColor(0xFF6B6B70);
        l2.setPadding(0, dp(3), 0, 0);
        l2.setText(dateTxt + "  ·  " + cfg.windowText() + "  ·  " + seats);
        card.addView(l2);

        addBadge(card, cfg.dryRun ? "试运行 · 只查询不下单" : "真实预约 · 到点会下单",
                cfg.dryRun ? 0xFFFFF3D6 : 0xFFE3F5EA,
                cfg.dryRun ? 0xFF8A6D00 : 0xFF12683C);
        box.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 问题提示：登录 / 时间段 / 省电白名单，各自一行，不再互相覆盖
        String warn = null;
        if (sec.get("session") == null) {
            warn = "还没在本 App 里登录过：先回页面登录一次，否则拿不到 token，下面全是空的。";
        } else if (!cfg.hasWindow()) {
            warn = "还没设时间段 —— 现在启用也不会执行。";
        }
        if (warn != null) {
            TextView w = new TextView(this);
            w.setTextSize(13f);
            w.setTextColor(0xFFB00020);
            w.setPadding(dp(4), dp(12), dp(4), 0);
            w.setText("\u26a0 " + warn);
            box.addView(w);
        }
        if (!Scheduler.ignoringBatteryOptimizations(this)) {
            TextView w2 = new TextView(this);
            w2.setTextSize(13f);
            w2.setTextColor(0xFFB00020);
            w2.setPadding(dp(4), dp(12), dp(4), 0);
            w2.setText("\u26a0 没进省电白名单：小米/华为/OPPO/vivo 可能到点直接拦掉。"
                    + "下面的「后台可靠性 → 省电白名单」开一下。");
            box.addView(w2);
        }
        if (!Scheduler.canExactAlarm(this)) {
            TextView w3 = new TextView(this);
            w3.setTextSize(13f);
            w3.setTextColor(0xFFB00020);
            w3.setPadding(dp(4), dp(12), dp(4), 0);
            w3.setText("\u26a0 没给「闹钟和提醒」权限：定时预约和座位守护都可能晚几十分钟，"
                    + "守护会赶不上释放前那一刻。下面的「后台可靠性 → 闹钟与提醒权限」开一下。");
            box.addView(w3);
        }

        addPrimary(box, cfg.enabled ? "停用定时预约" : "启用定时预约",
                cfg.enabled ? 0xFF8A8A8E : 0xFF8C1B22, new Runnable() {
            @Override
            public void run() {
                if (!cfg.enabled) {
                    if (!cfg.hasWindow()) {
                        Toast.makeText(MainActivity.this, "先设时间段", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (cfg.venueId == null || cfg.venueId.isEmpty()) {
                        Toast.makeText(MainActivity.this, "先选馆", Toast.LENGTH_LONG).show();
                        return;
                    }
                    cfg.enabled = true;
                    cfg.save(MainActivity.this);
                    Scheduler.apply(MainActivity.this);
                    askNotifPermission();
                    StringBuilder m = new StringBuilder("已启用：每天 ")
                            .append(cfg.timeText()).append(" 自动预约");
                    if (Scheduler.willFireTomorrow(cfg.minuteOfDay)) {
                        m.append("\n\n\u26a0 今天这个点已经过了，第一次触发是【明天 ")
                                .append(cfg.timeText()).append("】");
                    } else {
                        m.append("（今天就会生效）");
                    }
                    if (cfg.dryRun) {
                        m.append("\n\n\u26a0 试运行还开着 —— 到点只会查询，不会真下单");
                    }
                    Toast.makeText(MainActivity.this, m.toString(), Toast.LENGTH_LONG).show();
                } else {
                    cfg.enabled = false;
                    cfg.save(MainActivity.this);
                    Scheduler.cancel(MainActivity.this);
                    Toast.makeText(MainActivity.this, "已停用定时预约", Toast.LENGTH_SHORT).show();
                }
                refreshBookDialog();
            }
        });

        addSection(box, "预约配置");

        addItem(box, "设置触发时间", cfg.timeText(), new Runnable() {
            @Override
            public void run() {
                new TimePickerDialog(MainActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(android.widget.TimePicker v, int h, int min) {
                        cfg.minuteOfDay = h * 60 + min;
                        cfg.save(MainActivity.this);
                        Scheduler.apply(MainActivity.this);
                        refreshBookDialog();
                    }
                }, cfg.minuteOfDay / 60, cfg.minuteOfDay % 60, true).show();
            }
        });

        addItem(box, "选择馆", cfg.venueName == null || cfg.venueName.isEmpty()
                ? "未设置" : cfg.venueName, new Runnable() {
            @Override
            public void run() {
                pickVenue(cfg);
            }
        });

        addItem(box, "选择座位区", cfg.roomName == null || cfg.roomName.isEmpty()
                ? "自动（挑空座最多的区）" : cfg.roomName, new Runnable() {
            @Override
            public void run() {
                pickArea(cfg);
            }
        });

        addItem(box, "设置时间段", cfg.windowText(), new Runnable() {
                    @Override
                    public void run() {
                        new TimePickerDialog(MainActivity.this, new TimePickerDialog.OnTimeSetListener() {
                            @Override
                            public void onTimeSet(android.widget.TimePicker v, int h, int min) {
                                cfg.beginMinute = h * 60 + min;
                                new TimePickerDialog(MainActivity.this, new TimePickerDialog.OnTimeSetListener() {
                                    @Override
                                    public void onTimeSet(android.widget.TimePicker v2, int h2, int m2) {
                                        cfg.endMinute = h2 * 60 + m2;
                                        cfg.save(MainActivity.this);
                                        refreshBookDialog();
                                    }
                                }, cfg.endMinute / 60, cfg.endMinute % 60, true).show();
                            }
                        }, cfg.beginMinute / 60, cfg.beginMinute % 60, true).show();
                    }
                });

        addItem(box, "选择日期", cfg.dateLabel == null || cfg.dateLabel.isEmpty()
                ? "未选（默认可约列表第 1 个）" : cfg.dateLabel, new Runnable() {
            @Override
            public void run() {
                pickDateUi(cfg);
            }
        });

        addItem(box, "座位优先顺序", cfg.seatPriority == null || cfg.seatPriority.isEmpty()
                ? "自动（区内 id 最小）" : cfg.seatPriority, new Runnable() {
            @Override
            public void run() {
                editSeatPriority(cfg);
            }
        });

        addSection(box, "执行与演练");

        addItem(box, "试运行（不下单）", cfg.dryRun ? "已开启 —— 只查询" : "已关闭 —— 会真预约",
                new Runnable() {
                    @Override
                    public void run() {
                        cfg.dryRun = !cfg.dryRun;
                        cfg.save(MainActivity.this);
                        if (!cfg.dryRun) {
                            Toast.makeText(MainActivity.this,
                                    "已关闭试运行：到点会真的下单，记得签到", Toast.LENGTH_LONG).show();
                        }
                        refreshBookDialog();
                    }
                });

        addItem(box, "立即演练一次", "跑一遍完整流程，但不下单", new Runnable() {
            @Override
            public void run() {
                runDryRun();
            }
        });

        addItem(box, "真实试约一次（会真的下单）", "按上面的馆/区/座位/时段真实预约，成功后手动取消",
                new Runnable() {
            @Override
            public void run() {
                runRealOnce();
            }
        });

        addSection(box, "后台可靠性");

        addItem(box, "省电白名单", Scheduler.ignoringBatteryOptimizations(this)
                ? "已加入 ✓" : "未加入 —— 点这里开启（7:30 能不能准时响全靠它）", new Runnable() {
            @Override
            public void run() {
                askBatteryWhitelist();
            }
        });

        addItem(box, "闹钟与提醒权限", Scheduler.canExactAlarm(this)
                ? "已授权 ✓" : "未授权 —— 守护会迟到，点这里去开", new Runnable() {
            @Override
            public void run() {
                askExactAlarmPermission();
            }
        });

        addItem(box, "2 分钟后测试闹钟", "只查询不下单：验证到点会不会准时响", new Runnable() {
            @Override
            public void run() {
                if (!cfg.enabled) {
                    Toast.makeText(MainActivity.this,
                            "先启用定时预约，测试才有意义（测的是闹钟链路）",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Scheduler.applyTest(MainActivity.this, 120);
                Toast.makeText(MainActivity.this,
                        "已排：2 分钟后响一次。可以锁屏/退到桌面，等通知。",
                        Toast.LENGTH_LONG).show();
            }
        });

        addSection(box, "诊断");

        addItem(box, "检查风控状态", "看此刻到底要不要验证码", new Runnable() {
            @Override
            public void run() {
                checkRisk();
            }
        });

        // 底部提示卡片：签到已单列
        LinearLayout tipCard = new LinearLayout(this);
        tipCard.setOrientation(LinearLayout.VERTICAL);
        tipCard.setBackground(cardBg(0xFFFFFFFF, 0x14000000, 14));
        tipCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView tipText = new TextView(this);
        tipText.setTextSize(12.5f);
        tipText.setTextColor(0xFF6B6B70);
        tipText.setText("💡 签到与座位守护已单列至专属面板，可从「控制中心 → 签到与守护」进入进行管理和远程签到。");
        tipCard.addView(tipText);

        LinearLayout.LayoutParams lpTip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpTip.topMargin = dp(14);
        box.addView(tipCard, lpTip);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        bookDialog = new AlertDialog.Builder(this)
                .setTitle("定时预约")
                .setView(sv)
                .setNegativeButton("关闭", null)
                .setNeutralButton("返回控制中心", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showControlCenter();
                    }
                })
                .create();
        bookDialog.show();
        if (bookDialog.getWindow() != null) {
            bookDialog.getWindow().setBackgroundDrawable(cardBg(0xFFF4F4F6, 0x00000000, 22));
        }
    }

    private void showReliabilityDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(10), p, dp(14));

        addItem(box, "省电白名单", Scheduler.ignoringBatteryOptimizations(this)
                ? "已加入 ✓" : "未加入 —— 点这里开启（准时唤醒必备）", new Runnable() {
            @Override
            public void run() {
                askBatteryWhitelist();
            }
        });

        addItem(box, "闹钟与提醒权限", Scheduler.canExactAlarm(this)
                ? "已授权 ✓" : "未授权 —— 闹钟会迟到，点这里去开", new Runnable() {
            @Override
            public void run() {
                askExactAlarmPermission();
            }
        });

        addItem(box, "2 分钟后测试闹钟", "只查询不下单：验证到点会不会准时响", new Runnable() {
            @Override
            public void run() {
                Booker.Cfg cfg = Booker.Cfg.load(MainActivity.this);
                if (!cfg.enabled) {
                    Toast.makeText(MainActivity.this,
                            "先启用定时预约，测试才有意义（测的是闹钟链路）",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Scheduler.applyTest(MainActivity.this, 120);
                Toast.makeText(MainActivity.this,
                        "已排：2 分钟后响一次。可以锁屏/退到桌面，等通知。",
                        Toast.LENGTH_LONG).show();
            }
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("后台可靠性")
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    private TextView buildBadgeView(String text, int bgColor, int fgColor) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(11.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(fgColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        b.setPadding(dp(10), dp(3), dp(10), dp(3));
        return b;
    }

    private TextView buildButton(String text, int bgFill, int textColor, boolean bold, final Runnable action) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14f);
        if (bold) {
            b.setTypeface(Typeface.DEFAULT_BOLD);
        }
        b.setTextColor(textColor);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(11), dp(12), dp(11));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgFill);
        bg.setCornerRadius(dp(12));
        b.setBackground(rippleOn(bg, (textColor == 0xFFFFFFFF ? 0x33FFFFFF : 0x148C1B22)));
        b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return b;
    }

    /** 分组小标题 */
    private void addSection(LinearLayout parent, String title) {
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextSize(12f);
        h.setTextColor(0xFF9A9A9E);
        h.setPadding(dp(6), dp(18), 0, dp(6));
        parent.addView(h);
    }

    private Drawable rippleOn(Drawable inner, int color) {
        return new RippleDrawable(ColorStateList.valueOf(color), inner, null);
    }

    /**
     * 卡片行：短的右侧显示值，长的自动换到标题下面一行 —— 手机上比挤在右边好读得多。
     */
    private void addItem(LinearLayout parent, String title, String sub, final Runnable action) {
        boolean stacked = sub != null && sub.length() > 22;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(ripple(cardBg(0xFFFFFFFF, 0x14000000, 14)));
        row.setClickable(true);
        row.setPadding(dp(16), dp(13), dp(14), dp(13));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(0xFF1A1A1A);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!stacked) {
            TextView v = new TextView(this);
            v.setText(sub);
            v.setTextSize(13f);
            v.setTextColor(0xFF8A8A8E);
            v.setGravity(Gravity.END);
            v.setMaxLines(1);
            v.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.leftMargin = dp(10);
            head.addView(v, vlp);
        }

        TextView chev = new TextView(this);
        chev.setText("\u203a");
        chev.setTextSize(20f);
        chev.setTextColor(0xFFC7C7CC);
        chev.setPadding(dp(8), 0, 0, 0);
        head.addView(chev);
        row.addView(head);

        if (stacked) {
            TextView v = new TextView(this);
            v.setText(sub);
            v.setTextSize(12.5f);
            v.setTextColor(0xFF8A8A8E);
            v.setPadding(0, dp(3), dp(16), 0);
            row.addView(v);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(row, lp);
    }

    /** 主操作按钮：实心品牌色 + 白色粗体 + 按压涟漪 */
    private void addPrimary(LinearLayout parent, String text, int fill, final Runnable action) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(15), 0, dp(15));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(15));
        b.setBackground(rippleOn(bg, 0x33FFFFFF));
        b.setClickable(true);
        b.setElevation(dp(2));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        parent.addView(b, lp);
    }

    /** 小圆角标签（试运行 / 真实预约） */
    private void addBadge(LinearLayout parent, String text, int bgColor, int fgColor) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(11.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(fgColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        b.setPadding(dp(11), dp(4), dp(11), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(b, lp);
    }

    interface SessionCb {
        void ready(String token, Net.Jar jar, StringBuilder log);
    }

    /** Runs on a worker thread: cookie jar + signer + a live token, then hands over. */
    private void withSession(final SessionCb cb) {
        Toast.makeText(this, "正在读取…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder log = new StringBuilder();
                try {
                    Net.Jar jar = Booker.loadJar(sec);
                    Booker.primeJar(jar, true);
                    Booker.installSigner(sec.get("session"), jar, log);
                    String token = Booker.ensureToken(MainActivity.this, sec, jar,
                            sec.get("session"), log);
                    cb.ready(token, jar, log);
                } catch (Throwable t) {
                    log.append("异常: ").append(t);
                    cb.ready(null, null, log);
                }
            }
        }, "zw-session").start();
    }

    private void showList(String title, String[] labels, DialogInterface.OnClickListener pick,
                          final SceneCb back) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, pick)
                .setNegativeButton("取消", null)
                .setNeutralButton(back == null ? "关闭" : "返回设置", null)
                .create()
                .show();
    }

    interface SceneCb {
        void run();
    }

    private void pickVenue(final Booker.Cfg cfg) {
        withSession(new SessionCb() {
            @Override
            public void ready(String token, Net.Jar jar, StringBuilder log) {
                if (token == null) {
                    final String m = Booker.state(sec) + log;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showCopyable("读不到登录态", m);
                        }
                    });
                    return;
                }
                final org.json.JSONObject v = Booker.venues(token, jar, log);
                final java.util.List<Booker.Item> list = Booker.buildings(v);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (list.isEmpty()) {
                            showCopyable("取馆列表失败", log.toString());
                            return;
                        }
                        final String[] labels = new String[list.size()];
                        for (int i = 0; i < list.size(); i++) {
                            labels[i] = list.get(i).label;
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("选择馆")
                                .setItems(labels, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        cfg.venueId = list.get(which).id;
                                        cfg.venueName = list.get(which).label;
                                        cfg.roomId = "";          // 换馆后区必须重选
                                        cfg.roomName = "";
                                        cfg.save(MainActivity.this);
                                        pickArea(cfg);            // 接着选座位区
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        });
    }

    private void pickArea(final Booker.Cfg cfg) {
        if (cfg.venueId == null || cfg.venueId.isEmpty()) {
            Toast.makeText(this, "先选一个馆", Toast.LENGTH_SHORT).show();
            return;
        }
        withSession(new SessionCb() {
            @Override
            public void ready(String token, Net.Jar jar, StringBuilder log) {
                if (token == null) {
                    final String m = Booker.state(sec) + log;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showCopyable("读不到登录态", m);
                        }
                    });
                    return;
                }
                org.json.JSONObject v = Booker.venues(token, jar, log);
                List<String> dates = Booker.bookableDates(v);
                final String date = Booker.resolveDate(dates, Booker.today(), cfg.dateOffset) != null
                        ? Booker.resolveDate(dates, Booker.today(), cfg.dateOffset)
                        : Booker.today();
                // 座位区列表与时间窗无关，未设时段时用开放时段兜底
                int b = cfg.hasWindow() ? cfg.beginMinute : 8 * 60;
                int e = cfg.hasWindow() ? cfg.endMinute : 22 * 60;
                final java.util.List<Booker.Item> rooms =
                        Booker.rooms(token, jar, cfg.venueId, date, b, e, cfg.floorId, log);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (rooms.isEmpty()) {
                            showCopyable("取座位区失败", log.toString());
                            return;
                        }
                        final String[] labels = new String[rooms.size() + 1];
                        labels[0] = "自动（挑空座最多的区）";
                        for (int i = 0; i < rooms.size(); i++) {
                            Booker.Item r = rooms.get(i);
                            labels[i + 1] = r.label
                                    + (r.free >= 0 ? "   空座 " + r.free : "");
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("选择座位区 · " + cfg.venueName + " · " + date)
                                .setItems(labels, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        if (which == 0) {
                                            cfg.roomId = "";
                                            cfg.roomName = "";
                                        } else {
                                            cfg.roomId = rooms.get(which - 1).id;
                                            cfg.roomName = rooms.get(which - 1).label;
                                        }
                                        cfg.save(MainActivity.this);
                                        refreshBookDialog();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        });
    }

    private void showCopyable(String title, final String body) {
        lastDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制日志", null)
                .setNegativeButton("控制中心", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showControlCenter();
                    }
                })
                .create();
        lastDialog.show();
        final AlertDialog shown = lastDialog;
        shown.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("zwlib", body));
                    Toast.makeText(MainActivity.this, "日志已复制", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "复制失败", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private String cookieNames(String url) {
        try {
            String c = CookieManager.getInstance().getCookie(url);
            if (c == null || c.isEmpty()) {
                return "(无)";
            }
            StringBuilder b = new StringBuilder();
            for (String part : c.split(";")) {
                int eq = part.indexOf('=');
                String n = (eq > 0 ? part.substring(0, eq) : part).trim();
                if (n.isEmpty()) {
                    continue;
                }
                if (b.length() > 0) {
                    b.append(", ");
                }
                b.append(n);
            }
            return b.toString();
        } catch (Throwable t) {
            return "(读取异常)";
        }
    }

    /** App 版本 + 已保存状态 + 双侧 cookie 名 + 页面实际内容 */
    private void checkLogin() {
        final StringBuilder sb = new StringBuilder();
        sb.append("App 版本: ").append(versionName()).append('\n');
        sb.append("已保存: ").append(Booker.state(sec));
        sb.append("cookie 名: zwlib=[").append(cookieNames(Booker.AUTH_URL)).append("]")
                .append("  cas=[").append(cookieNames(Booker.CAS_URL)).append("]\n\n");
        if (web == null) {
            showCopyable("登录状态", sb.toString());
            return;
        }
        final String js = "(function(){try{var a=[],i,t=null,p=null;"
                + "for(i=0;i<sessionStorage.length;i++){var k=sessionStorage.key(i);a.push(k);"
                + "if(!t&&/-token$/.test(k)){t=sessionStorage.getItem(k);p=k;}}"
                + "var sc=[].map.call(document.querySelectorAll('script[src]'),function(s){"
                + "return s.src.replace(location.origin,'');});"
                + "return JSON.stringify({url:location.href.slice(0,110),prefix:p||'?',"
                + "token:t?('有 len='+t.length):'无',keys:a.join(', '),scripts:sc.join(' ')});"
                + "}catch(e){return JSON.stringify({url:location.href.slice(0,110),prefix:'?',"
                + "token:'读取异常',keys:String(e),scripts:''});}})()";
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                String json = unquote(v);
                String body = sb.toString();
                if (json != null) {
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(json);
                        body = body + "页面 URL: " + o.optString("url", "?") + "\n"
                                + "页面里的 token: " + o.optString("token", "?") + "\n"
                                + "命中前缀: " + o.optString("prefix", "?") + "\n"
                                + "页面 sessionStorage 键: " + o.optString("keys", "(空)") + "\n"
                                + "页面脚本: " + o.optString("scripts", "?") + "\n";
                    } catch (Exception e) {
                        body = body + "解析失败: " + json + "\n";
                    }
                } else {
                    body = body + "页面读取失败（evaluateJavascript 返回 null）\n";
                }
                showCopyable("登录状态", body);
            }
        });
    }

    private void pickDateUi(final Booker.Cfg cfg) {
        withSession(new SessionCb() {
            @Override
            public void ready(String token, Net.Jar jar, StringBuilder log) {
                if (token == null) {
                    final String m = Booker.state(sec) + log;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showCopyable("读不到登录态", m);
                        }
                    });
                    return;
                }
                final java.util.List<String> dates =
                        Booker.bookableDates(Booker.venues(token, jar, log));
                final String today = Booker.today();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        final String[] labels = new String[2];
                        for (int off = 0; off < 2; off++) {
                            String d = Booker.resolveDate(dates, today, off);
                            labels[off] = (off == 0 ? "今天" : "明天") + "   "
                                    + Booker.addDays(today, off)
                                    + (d != null ? "   现在可约" : "   还不可约");
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("预约哪一天（可约列表: " + dates + "）")
                                .setItems(labels, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        cfg.dateOffset = which;
                                        cfg.dateLabel = Booker.addDays(today, which);
                                        cfg.save(MainActivity.this);
                                        refreshBookDialog();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        });
    }

    private void askBatteryWhitelist() {
        if (Scheduler.ignoringBatteryOptimizations(this)) {
            Toast.makeText(this, "已经在省电白名单里了", Toast.LENGTH_SHORT).show();
        } else {
            try {
                Intent i = new Intent(
                        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t2) {
                    Toast.makeText(this, "请到 设置→电池→应用 里手动允许后台运行",
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /** Android 12+ 可以收回精确闹钟权限；收回后守护只能按不精确闹钟跑，可能晚几十分钟。 */
    private void askExactAlarmPermission() {
        if (Scheduler.canExactAlarm(this)) {
            Toast.makeText(this, "已经授权了", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT < 31) {
            Toast.makeText(this, "这个版本不需要单独授权", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t2) {
                Toast.makeText(this, "请到 设置→应用→本应用→闹钟和提醒 里手动打开",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void checkRisk() {
        withSession(new SessionCb() {
            @Override
            public void ready(String token, Net.Jar jar, StringBuilder log) {
                String text;
                try {
                    text = Booker.risk(token, jar).text;
                } catch (Throwable t) {
                    text = "查询失败: " + t;
                }
                final String out = text;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("当前风控状态")
                                .setMessage(out)
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            }
        });
    }

    /** 立刻巡检一次：只读，不改守护的任何状态；试运行开着就只记录不调接口。 */
    private void checkSeatNow() {
        Toast.makeText(this, "正在巡检…", Toast.LENGTH_SHORT).show();
        final Booker.Cfg cfg = Booker.Cfg.load(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Booker.Tick t =
                        Booker.watchTick(MainActivity.this, sec, cfg, true, Booker.WATCH_MANUAL);
                sec.put("ci_last", t.line);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showCopyable("签到状态", t.detail);
                        if (checkInDialog != null && checkInDialog.isShowing()) {
                            refreshCheckInDialog();
                        }
                        if (controlCenterDialog != null && controlCenterDialog.isShowing()) {
                            refreshControlCenter();
                        }
                    }
                });
            }
        }, "zw-watch-now").start();
    }

    /**
     * 手动签到：点一下就把当前有效预约签掉（不走守护开关与试运行 —— 用户点了就是要真签）。
     * 结果写回「签到状态」那一行；签成了就把过期的「要我帮你签到吗」通知撤掉。
     */
    private void signNow() {
        if (signing) {
            Toast.makeText(this, "上一次还在跑…", Toast.LENGTH_SHORT).show();
            return;
        }
        signing = true;
        Toast.makeText(this, "正在签到…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Booker.Sign s = Booker.signNow(MainActivity.this, sec, true);
                    sec.put("ci_last", s.line);
                    if (s.ok) {
                        WatchReceiver.cancelAsk(MainActivity.this);
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showCopyable(s.title, s.detail);
                            if (checkInDialog != null && checkInDialog.isShowing()) {
                                refreshCheckInDialog();
                            }
                            if (controlCenterDialog != null && controlCenterDialog.isShowing()) {
                                refreshControlCenter();
                            }
                        }
                    });
                } finally {
                    signing = false;
                }
            }
        }, "zw-sign-now").start();
    }

    /** 暂离时限（分钟）：平时 / 饭点。超时后座位会被释放。 */
    private void editMinutes(final Booker.Cfg cfg, final boolean normal) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(normal ? cfg.ciGraceMin : cfg.ciMealGraceMin));
        input.setSelection(input.getText().length());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);
        TextView hint = new TextView(this);
        hint.setTextSize(13f);
        hint.setText(normal ? "平时离座多久算超时（分钟）。图书馆规矩是 60。"
                : "饭点离座多久算超时（分钟）。图书馆规矩是 120。");
        box.addView(hint);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(normal ? "平时暂离时限" : "饭点暂离时限")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            int m = Integer.parseInt(input.getText().toString().trim());
                            if (m < 5 || m > 300) {
                                Toast.makeText(MainActivity.this, "填 5~300 分钟",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            if (normal) {
                                cfg.ciGraceMin = m;
                            } else {
                                cfg.ciMealGraceMin = m;
                            }
                            cfg.save(MainActivity.this);
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "数字看不懂", Toast.LENGTH_SHORT).show();
                        }
                        if (checkInDialog != null && checkInDialog.isShowing()) {
                            refreshCheckInDialog();
                        } else if (bookDialog != null && bookDialog.isShowing()) {
                            refreshBookDialog();
                        }
                        if (controlCenterDialog != null && controlCenterDialog.isShowing()) {
                            refreshControlCenter();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editSeatPriority(final Booker.Cfg cfg) {
        if (cfg.roomId == null || cfg.roomId.isEmpty()) {
            Toast.makeText(this, "先选「座位区」——座位是按区编号的", Toast.LENGTH_LONG).show();
            return;
        }
        if (!cfg.hasWindow()) {
            Toast.makeText(this, "先设「时间段」——空座和时段有关", Toast.LENGTH_LONG).show();
            return;
        }
        withSession(new SessionCb() {
            @Override
            public void ready(String token, Net.Jar jar, StringBuilder log) {
                if (token == null) {
                    final String m = Booker.state(sec) + log;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showCopyable("读不到登录态", m);
                        }
                    });
                    return;
                }
                org.json.JSONObject v = Booker.venues(token, jar, log);
                final String date = Booker.resolveDate(Booker.bookableDates(v), Booker.today(),
                        cfg.dateOffset) != null
                        ? Booker.resolveDate(Booker.bookableDates(v), Booker.today(), cfg.dateOffset)
                        : Booker.today();
                final java.util.List<Booker.Seat> seats = Booker.freeSeats(token, jar, cfg.roomId,
                        date, cfg.beginMinute, cfg.endMinute, log);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seats.isEmpty()) {
                            showCopyable("该区在这个时段没有空座", log.toString());
                            return;
                        }
                        StringBuilder ref = new StringBuilder();
                        for (int i = 0; i < seats.size() && i < 60; i++) {
                            String lb = seats.get(i).label == null ? seats.get(i).id
                                    : seats.get(i).label;
                            if (ref.length() > 0) {
                                ref.append("  ");
                            }
                            ref.append(lb);
                        }
                        if (seats.size() > 60) {
                            ref.append(" …共 ").append(seats.size()).append(" 个");
                        }
                        android.widget.LinearLayout box = new android.widget.LinearLayout(
                                MainActivity.this);
                        box.setOrientation(android.widget.LinearLayout.VERTICAL);
                        int p = dp(20);
                        box.setPadding(p, dp(8), p, 0);
                        android.widget.TextView hint = new android.widget.TextView(MainActivity.this);
                        hint.setTextSize(13f);
                        hint.setText("按优先顺序填座位号，逗号隔开。\n"
                                + "系统取第一个当前有空的；留空 = 区内 id 最小。\n\n"
                                + cfg.roomName + " · " + date + " · " + cfg.windowText()
                                + " 当前空座：\n" + ref);
                        box.addView(hint);
                        final android.widget.EditText input =
                                new android.widget.EditText(MainActivity.this);
                        input.setHint("例如 122,124,126");
                        input.setText(cfg.seatPriority == null ? "" : cfg.seatPriority);
                        input.setSelection(input.getText().length());
                        box.addView(input);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("座位优先顺序")
                                .setView(box)
                                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        cfg.seatPriority = input.getText().toString().trim();
                                        cfg.save(MainActivity.this);
                                        refreshBookDialog();
                                    }
                                })
                                .setNeutralButton("清空", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        cfg.seatPriority = "";
                                        cfg.save(MainActivity.this);
                                        refreshBookDialog();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        });
    }

    private void runRealOnce() {
        final Booker.Cfg cfg = Booker.Cfg.load(this);
        if (!cfg.hasWindow() || cfg.venueId == null || cfg.venueId.isEmpty()
                || cfg.roomId == null || cfg.roomId.isEmpty()) {
            Toast.makeText(this, "真实试约需要先定好：馆 / 座位区 / 时间段", Toast.LENGTH_LONG).show();
            return;
        }
        String seat = cfg.seatPriority == null || cfg.seatPriority.isEmpty()
                ? "（区内 id 最小）" : cfg.seatPriority + "（按顺序取第一个有空的）";
        String day = cfg.dateLabel == null || cfg.dateLabel.isEmpty()
                ? (cfg.dateOffset == 0 ? "今天" : cfg.dateOffset + " 天后") : cfg.dateLabel;
        new AlertDialog.Builder(this)
                .setTitle("真实下单 —— 会在你账号上占座")
                .setMessage("即将真实预约：\n\n"
                        + "馆：" + cfg.venueName + "\n"
                        + "座位区：" + cfg.roomName + "\n"
                        + "座位：" + seat + "\n"
                        + "日期：" + day + "\n"
                        + "时段：" + cfg.windowText() + "\n\n"
                        + "成功后会立刻在你账号上产生一条有效预约。\n\n"
                        + "这个 App 不会替你取消 —— 请自己到页面「我的」里取消"
                        + "（每天最多取消 2 次）。")
                .setPositiveButton("确认下单", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doReal(cfg);
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    private void doReal(final Booker.Cfg cfg) {
        Toast.makeText(this, "正在真实下单…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Booker.Outcome out =
                        Booker.run(MainActivity.this, sec, cfg, Booker.MODE_REAL, true);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showRealResult(out);
                    }
                });
            }
        }, "zw-real").start();
    }

    private void showRealResult(final Booker.Outcome out) {
        if (!out.ok || out.dry) {
            showCopyable(out.title, out.detail);
            return;
        }
        showCopyable("✅ 真实预约成功", out.detail
                + (out.bookingId == null || out.bookingId.isEmpty()
                        ? "" : "\n\n预约 id: " + out.bookingId)
                + "\n\n这个 App 不会替你取消 —— "
                + "请自己到页面「我的」里取消，或按时去签到。");
    }

    private void runDryRun() {
        Toast.makeText(this, "正在演练…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Booker.Cfg cfg = Booker.Cfg.load(MainActivity.this);
                final Booker.Outcome out =
                        Booker.run(MainActivity.this, sec, cfg, Booker.MODE_DRY, true);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showCopyable(out.ok ? "演练通过（未下单）" : out.title,
                                out.detail == null ? "" : out.detail);
                    }
                });
            }
        }, "zw-dryrun").start();
    }

    private View buildSplash() {
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0xFFF7F5F5);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(30), dp(28), dp(30), dp(26));
        card.setBackground(cardBg(0xFFFFFFFF, 0x14000000, 20));
        card.setElevation(dp(2));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(56), dp(56));
        card.addView(icon, ilp);

        TextView title = new TextView(this);
        title.setText("图书馆座位");
        title.setTextSize(17f);
        title.setTextColor(0xFF1A1A1A);
        title.setPadding(0, dp(14), 0, dp(4));
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText("正在恢复登录状态…");
        sub.setTextSize(13f);
        sub.setTextColor(0xFF8A8A8E);
        card.addView(sub);

        ProgressBar pb = new ProgressBar(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(26), dp(26));
        plp.topMargin = dp(16);
        card.addView(pb, plp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        box.addView(card, clp);
        return box;
    }

    void captureSession() {
        if (web == null) {
            return;
        }
        try {
            web.evaluateJavascript(SNAPSHOT_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String v) {
                    saveSnapshot(v);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "captureSession " + t);
        }
    }

    /**
     * 按下按钮的这一刻才去读 sessionStorage —— onPageFinished 对停在首页的用户不会再触发。
     * 1.2s 兜底，保证 Runnable 一定只跑一次。
     */
    void withFreshSession(final Runnable runnable) {
        if (web == null) {
            runnable.run();
            return;
        }
        final boolean[] done = {false};
        final Runnable once = new Runnable() {
            @Override
            public void run() {
                if (done[0]) {
                    return;
                }
                done[0] = true;
                runnable.run();
            }
        };
        ui.postDelayed(once, 1200L);
        try {
            web.evaluateJavascript(SNAPSHOT_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String v) {
                    saveSnapshot(v);
                    ui.post(once);
                }
            });
        } catch (Throwable t) {
            ui.post(once);
        }
    }

    static String unquote(String v) {
        if (v == null || "null".equals(v) || v.length() < 2) {
            return null;
        }
        try {
            return new JSONArray("[" + v + "]").optString(0, null);
        } catch (Exception e) {
            return null;
        }
    }

    void saveSnapshot(String raw) {
        try {
            String o = unquote(raw);
            if (o != null && o.length() >= 3 && !"{}".equals(o)
                    && Booker.sessionToken(o) != null) {
                sec.put("session", o);
                Log.d(TAG, "session captured " + o.length() + "B");
            }
        } catch (Throwable t) {
            Log.w(TAG, "saveSnapshot " + t);
        }
    }

    private void hideSplash() {
        if (splash != null && splash.getVisibility() == View.VISIBLE) {
            splash.setVisibility(View.GONE);
        }
    }

    /* ------------------------------------------------------------------ */
    /* WebView plumbing                                                   */
    /* ------------------------------------------------------------------ */

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @SuppressLint({"SetJavaScriptEnabled"})
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setGeolocationEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        }

        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "ZWNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse r = intercept(request);
                return r != null ? r : super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                bar.setVisibility(View.VISIBLE);
                fillDone = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                bar.setVisibility(View.GONE);
                hideSplash();
                captureSession();
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        captureSession();   // again once the SPA has exchanged ?token=
                    }
                }, 2500);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                Log.w(TAG, "ssl: " + error.getPrimaryError());
                handler.proceed();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    hideSplash();
                    bar.setVisibility(View.GONE);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                bar.setProgress(newProgress);
                bar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, false);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                if (Build.VERSION.SDK_INT >= 21) {
                    boolean needsCamera = false;
                    for (String res : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                            needsCamera = true;
                            break;
                        }
                    }
                    if (needsCamera && !hasCameraPermission()) {
                        pendingWebPermissionRequest = request;
                        requestCameraPermission(REQUEST_CAMERA_PERMISSION);
                    } else {
                        request.grant(request.getResources());
                    }
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    Intent chooserIntent = params.createIntent();
                    chooserIntent.addCategory(Intent.CATEGORY_OPENABLE);

                    Intent captureIntent = createCameraCaptureIntent();
                    Intent targetIntent;
                    if (captureIntent != null) {
                        Intent[] extraIntents = new Intent[]{captureIntent};
                        targetIntent = Intent.createChooser(chooserIntent, "选择或拍照");
                        targetIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents);
                    } else {
                        targetIntent = chooserIntent;
                    }

                    startActivityForResult(targetIntent, REQUEST_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    cameraImageUri = null;
                    return false;
                }
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                try {
                    if (url.startsWith("blob:") || url.startsWith("data:")) {
                        openExternal(url);
                        return;
                    }
                    String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                    r.setMimeType(mimeType);
                    r.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                    r.addRequestHeader("User-Agent", userAgent);
                    r.setTitle(name);
                    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(r);
                        Toast.makeText(MainActivity.this, "开始下载", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    openExternal(url);
                }
            }
        });
    }

    private boolean handleUrl(String url) {
        if (url == null) {
            return false;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            Uri u = Uri.parse(url);
            String host = u.getHost() == null ? "" : u.getHost();
            if (host.endsWith(HOST_SUFFIX)) {
                return false;                     // campus + SSO pages stay in-app
            }
            openExternal(url);
            return true;
        }
        openExternal(url);
        return true;
    }

    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private Intent createCameraCaptureIntent() {
        try {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (intent.resolveActivity(getPackageManager()) == null) {
                return null;
            }
            File cacheDir = getExternalCacheDir();
            if (cacheDir == null) {
                cacheDir = getCacheDir();
            }
            File photoFile = new File(cacheDir, "upload_camera_" + System.currentTimeMillis() + ".jpg");
            cameraImageUri = QuickFileProvider.getUriForFile(this, "com.zwlib.quick.fileprovider", photoFile);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return intent;
        } catch (Throwable t) {
            cameraImageUri = null;
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (fileCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK) {
                    if (data != null && data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    } else if (data != null && data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        results = new Uri[n];
                        for (int i = 0; i < n; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (cameraImageUri != null) {
                        results = new Uri[]{cameraImageUri};
                    }
                }
                fileCallback.onReceiveValue(results);
                fileCallback = null;
                cameraImageUri = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingWebPermissionRequest != null && Build.VERSION.SDK_INT >= 21) {
                    pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
                }
            } else {
                if (pendingWebPermissionRequest != null && Build.VERSION.SDK_INT >= 21) {
                    pendingWebPermissionRequest.deny();
                }
                Toast.makeText(this, "相机权限被拒绝", Toast.LENGTH_SHORT).show();
            }
            pendingWebPermissionRequest = null;
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getData() != null) {
            load(intent.getData().toString());
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (web.canGoBack()) {
                web.goBack();
                return true;
            }
            long now = System.currentTimeMillis();
            if (now - lastBackPress < 2000) {
                finish();
            } else {
                lastBackPress = now;
                Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    private final Runnable poke = new Runnable() {
        @Override
        public void run() {
            captureSession();
            ui.postDelayed(this, 15000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(poke);
        ui.postDelayed(poke, 4000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poke);
        CookieManager.getInstance().flush();
        captureSession();
        try {
            // the 07:01 run has no WebView, so keep a copy of the SSO cookies it needs
            Net.Jar jar = Booker.loadJar(sec);
            jar.seed(Booker.AUTH_URL);
            jar.seed("https://cas.ruc.edu.cn");
            Booker.saveJar(sec, jar);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.setVisibility(View.GONE);
            web.destroy();
        }
        super.onDestroy();
    }

    /* ------------------------------------------------------------------ */
    /* misc                                                               */
    /* ------------------------------------------------------------------ */

    private String readAsset(String name) {
        InputStream in = null;
        try {
            in = getAssets().open(name);
            return read(in);
        } catch (Exception e) {
            return "";
        } finally {
            close(in);
        }
    }

    private static String read(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 4 * 1024 * 1024) {
                    break;
                }
            }
            return new String(bos.toByteArray(), UTF8);
        } catch (Exception e) {
            return "";
        } finally {
            close(in);
        }
    }

    private static void close(InputStream in) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** Minimal helper so we do not need a JSON dependency for two lookups. */
    private static final class Json {
        static String field(String json, String key) {
            try {
                Object v = new JSONObject(json).opt(key);
                return v == null || v == JSONObject.NULL ? null : String.valueOf(v);
            } catch (Exception e) {
                return null;
            }
        }
    }

    @SuppressWarnings("unused")
    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }
}

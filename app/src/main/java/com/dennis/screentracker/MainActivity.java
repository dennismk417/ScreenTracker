package com.dennis.screentracker;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    // ================= Background charge watcher =================
    /** Runs every ~15 min: logs battery level and records unplug / plug-in moments as "cycles". */
    public static class ChargeWatchJob extends JobService {
        static final String PREFS = "st2";

        static void schedule(Context c) {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            js.cancel(1); js.cancel(3); // old jobs from earlier versions
            if (js.getPendingJob(4) != null) return;
            arm(c);
        }

        /** One-shot job that can only run while charging. Android stops it the moment the cable is
         *  removed, which is how we catch the exact unplug time. Never runs on battery. */
        static void arm(Context c) {
            c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(4, new ComponentName(c, ChargeWatchJob.class))
                    .setRequiresCharging(true).setPersisted(true).build());
        }

        static JSONObject newCycle(long t, int lvl, boolean partial) throws Exception {
            return new JSONObject().put("u", t).put("ul", lvl).put("p", 0L).put("pl", -1).put("s", partial);
        }

        static synchronized void check(Context c) { update(c, false); }
        static synchronized void markUnplugged(Context c) { update(c, true); }

        static void update(Context c, boolean forceNewCycle) {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return;
            boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
            int batT = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1); // tenths of °C
            int cpuT = cpuTemp();
            long now = System.currentTimeMillis();
            SharedPreferences p = c.getSharedPreferences(PREFS, 0);
            try {
                JSONArray cycles = new JSONArray(p.getString("cycles", "[]"));
                JSONArray samples = new JSONArray(p.getString("samples", "[]"));
                boolean known = p.contains("plugged");
                boolean was = p.getBoolean("plugged", plugged);
                JSONObject open = null;
                for (int i = cycles.length() - 1; i >= 0; i--)
                    if (cycles.getJSONObject(i).getLong("p") == 0) { open = cycles.getJSONObject(i); break; }

                if (forceNewCycle) {
                    if (open != null) { open.put("p", now); open.put("pl", lvl); }
                    cycles.put(newCycle(now, lvl, false));
                    plugged = false;
                } else if (!known && !plugged && cycles.length() == 0) {
                    cycles.put(newCycle(now, lvl, true));           // first run: start tracking now
                } else if (was && !plugged) {
                    // Unplugged: the last time we saw it charging is the unplug time (within ~15 min).
                    long lpT = p.getLong("lastPlugT", 0);
                    // Level can only fall after unplugging, so the start level is at least the current one.
                    cycles.put(newCycle(lpT > 0 ? lpT : now, Math.max(lvl, lpT > 0 ? p.getInt("lastPlugLvl", lvl) : lvl), false));
                } else if (!was && plugged) {
                    if (open != null) { open.put("p", now); open.put("pl", lvl); }   // just plugged in
                } else if (was && plugged && known) {
                    // Still charging, but battery is lower than last check: it was unplugged and
                    // re-plugged while the app wasn't open. Record that missed cycle.
                    long lpT = p.getLong("lastPlugT", 0);
                    int lpL = p.getInt("lastPlugLvl", lvl);
                    if (lpT > 0 && lvl <= lpL - 2) cycles.put(newCycle(lpT, lpL, false).put("p", now).put("pl", lvl));
                }

                long lastT = samples.length() > 0 ? samples.getJSONArray(samples.length() - 1).getLong(0) : 0;
                if (now - lastT > 60000) samples.put(new JSONArray().put(now).put(lvl).put(plugged ? 1 : 0).put(batT).put(cpuT));

                long cutoff = now - 8 * DAY;
                JSONArray keepC = new JSONArray();
                for (int i = 0; i < cycles.length(); i++) {
                    JSONObject o = cycles.getJSONObject(i);
                    if (o.getLong("p") == 0 || o.getLong("p") > cutoff) keepC.put(o);
                }
                JSONArray keepS = new JSONArray();
                for (int i = 0; i < samples.length(); i++)
                    if (samples.getJSONArray(i).getLong(0) > cutoff) keepS.put(samples.getJSONArray(i));

                SharedPreferences.Editor ed = p.edit();
                if (plugged) ed.putLong("lastPlugT", now).putInt("lastPlugLvl", lvl);
                ed.putBoolean("plugged", plugged).putInt("lastLvl", lvl)
                        .putString("cycles", keepC.toString()).putString("samples", keepS.toString()).apply();
            } catch (Exception ignored) { }
        }

        /** Highest CPU/SoC thermal zone in tenths of °C, or -1 if the phone doesn't allow reading it. */
        static int cpuTemp() {
            int best = -1;
            File[] zones = new File("/sys/class/thermal").listFiles();
            if (zones == null) return -1;
            for (File z : zones) {
                if (!z.getName().startsWith("thermal_zone")) continue;
                try {
                    String type = readLine(new File(z, "type")).toLowerCase(Locale.US);
                    if (!(type.contains("cpu") || type.contains("soc") || type.contains("tsens"))) continue;
                    long v = Long.parseLong(readLine(new File(z, "temp")).trim());
                    v = v > 1000 ? v / 100 : v * 10;
                    if (v > 0 && v < 1500 && v > best) best = (int) v;
                } catch (Exception ignored) { }
            }
            return best;
        }

        static String readLine(File f) throws Exception {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) { String l = r.readLine(); return l == null ? "" : l; }
        }

        BroadcastReceiver rx;
        long lastRx;

        @Override public boolean onStartJob(JobParameters params) {
            check(this);
            // Stay awake while charging (costs nothing - the charger powers it) and follow the battery level,
            // so the last level before unplugging is exact.
            rx = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent i) {
                    long now = System.currentTimeMillis();
                    if (now - lastRx < 20000) return;
                    lastRx = now;
                    check(ctx);
                }
            };
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(rx, f);
            return true;
        }

        @Override public boolean onStopJob(JobParameters params) {
            // Called the instant the charger is removed (or after Android's ~10 min job limit).
            try { if (rx != null) unregisterReceiver(rx); } catch (Exception ignored) { }
            rx = null;
            check(this);
            arm(this);   // re-arm: starts again right away if still charging, otherwise waits for next plug-in
            return false;
        }
    }

    // ================= Data model =================
    static final long DAY = 24L * 3600 * 1000;
    static final int BG = 0xFF000000, CARD = 0xFF1C1C1E, TXT = 0xFFFFFFFF, SUB = 0xFF8E8E93,
            BLUE = 0xFF3B82F6, LBLUE = 0xFF7DD3FC, DBLUE = 0xFF1E40AF, GREEN = 0xFF22C55E, GREY = 0xFF48484A, SEL = 0xFF3A3A3C;

    static class Stats { long on, off; Map<String, Long> apps = new HashMap<>(), bg = new HashMap<>(); }
    static final double BG_WEIGHT = 0.3; // background work assumed ~30% as costly as on-screen use

    static class Temp {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, n;
        long sum;
        void add(int v) { if (v <= 0) return; min = Math.min(min, v); max = Math.max(max, v); sum += v; n++; }
    }

    static class Seg {
        Temp cpu = new Temp(), bat = new Temp();
        long from, to, uAt, pAt;
        int startLvl = -1, endLvl = -1, used = -1;
        boolean est, partial;
        Stats st = new Stats();
    }

    interface OnPick { void pick(int i); }

    BarChart hourChart, weekChart;
    HourChart lvlChart, stackChart;

    static <T extends View> T detach(T v) {
        if (v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).removeView(v);
        return v;
    }

    LinearLayout root;
    int tab = 0, selDay = 6, selHour = 23;
    boolean loading, reload, chargingNow;
    int curLvl = -1;
    Seg cur, lastDone, dayTotal;
    List<Seg> daySegs = new ArrayList<>();
    List<long[]> chartPts = new ArrayList<>();
    long[] sinceOn = new long[0], sinceOff = new long[0], sinceStarts = new long[0];
    int[] sinceLvl = new int[0];
    long[] dayOn = new long[7], dayStarts = new long[7], hourOn = new long[24], hourStarts = new long[25];
    Seg hourSeg;
    final Map<String, Drawable> iconCache = java.util.Collections.synchronizedMap(new HashMap<>());
    final Map<String, String> labelCache = java.util.Collections.synchronizedMap(new HashMap<>());

    // ================= Lifecycle =================
    ScrollView[] tabScroll = new ScrollView[3];
    LinearLayout[] tabRoot = new LinearLayout[3];
    int[] dataVer = new int[3], builtVer = {-1, -1, -1};
    final java.util.concurrent.ExecutorService iconLoader = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        page = new LinearLayout(this);              // fixed header: title + tabs
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(dp(16), dp(48), dp(16), 0);
        page.setClipChildren(false);
        TextView t = new TextView(this);
        t.setText("Battery");
        t.setTextSize(26);
        t.setTextColor(TXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), 0, 0, dp(14));
        page.addView(t);
        buildTabs();
        android.widget.FrameLayout host = new android.widget.FrameLayout(this);
        page.addView(host, new LinearLayout.LayoutParams(-1, 0, 1));
        for (int i = 0; i < 3; i++) {             // each tab keeps its own page and scroll position
            ScrollView sv = new ScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            sv.setClipChildren(false);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setClipChildren(false);
            r.setPadding(0, 0, 0, dp(56));
            sv.addView(r);
            sv.setVisibility(i == tab ? View.VISIBLE : View.GONE);
            host.addView(sv, new android.widget.FrameLayout.LayoutParams(-1, -1));
            tabScroll[i] = sv;
            tabRoot[i] = r;
        }
        root = tabRoot[tab];
        setContentView(page);
        ChargeWatchJob.schedule(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!hasUsageAccess()) { buildNoAccess(); return; }
        tabFrame.setVisibility(View.VISIBLE);
        if (tabRoot[tab].getChildCount() == 0) text(tabRoot[tab], "Loading…", 14, SUB, false);
        load();
    }

    boolean hasUsageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    int reloadTab = -1;
    boolean reloadDetails;

    void load() { load(-1); }

    /** onlyTab = -1 computes all three tabs (so switching tabs is instant); otherwise just that tab. */
    void load(int onlyTab) { load(onlyTab, false); }

    /** Computes in the background. Each tab is drawn as soon as its own numbers are ready, the visible tab
     *  first (Since charge at launch). detailsOnly = a bar was picked: only the part below the chart changes. */
    void load(int onlyTab, boolean detailsOnly) {
        if (loading) {   // remember what to redo once the current run finishes
            if (reload && !(reloadDetails && detailsOnly && reloadTab == onlyTab)) { reloadTab = -1; reloadDetails = false; }
            else { reloadTab = onlyTab; reloadDetails = detailsOnly; }
            reload = true;
            return;
        }
        loading = true;
        final int d = selDay, hsel = selHour, first = tab;
        new Thread(() -> {
            int[] order = {first, (first + 1) % 3, (first + 2) % 3};
            for (int t : order) {
                if (onlyTab >= 0 && t != onlyTab) continue;
                try { computeModel(t, d, hsel); } catch (Exception ignored) { }
                final int tt = t;
                runOnUiThread(() -> {
                    dataVer[tt]++;
                    if (detailsOnly && builtVer[tt] >= 0) { rebuildDetails(tt); builtVer[tt] = dataVer[tt]; }
                    else if (tt == tab) buildTab(tt);
                    else page.postDelayed(() -> { if (tt != tab && builtVer[tt] != dataVer[tt]) buildTab(tt); }, 600);
                });
            }
            runOnUiThread(() -> {
                loading = false;
                if (reload) { reload = false; load(reloadTab, reloadDetails); }
            });
        }).start();
    }

    // ================= Computation =================
    void computeModel(int t, int d, int hsel) throws Exception {
        ChargeWatchJob.check(this);
        SharedPreferences p = getSharedPreferences(ChargeWatchJob.PREFS, 0);
        long now = System.currentTimeMillis();
        chargingNow = p.getBoolean("plugged", false);
        curLvl = p.getInt("lastLvl", -1);
        JSONArray cycles = new JSONArray(p.getString("cycles", "[]"));
        JSONArray samples = new JSONArray(p.getString("samples", "[]"));

        Seg c0 = null, done = null;
        for (int i = cycles.length() - 1; i >= 0; i--) {
            JSONObject c = cycles.getJSONObject(i);
            if (c.getLong("p") == 0) { if (c0 == null && !chargingNow) c0 = cycleSeg(c, now, 0, Long.MAX_VALUE); }
            else if (done == null) done = cycleSeg(c, now, 0, Long.MAX_VALUE);
        }

        long today = startOfDay(now);
        long[] starts = new long[7];
        for (int i = 0; i < 7; i++) starts[i] = addDays(today, i - 6);

        List<long[]> pts = new ArrayList<>();
        long[] on = new long[7];
        Seg total = null;
        List<Seg> segs = new ArrayList<>();

        if (t == 0) {
            Seg s = c0 != null ? c0 : done;
            if (s != null) {
                s.st = compute(s.from, s.to);
                pts.add(new long[]{s.from, s.startLvl});
                for (int i = 0; i < samples.length(); i++) {
                    JSONArray a = samples.getJSONArray(i);
                    if (a.getLong(0) > s.from && a.getLong(0) < s.to) pts.add(new long[]{a.getLong(0), a.getInt(1)});
                }
                if (s.endLvl >= 0) pts.add(new long[]{s.to, s.endLvl});
                temps(s, samples);
                // one bucket per clock hour, from the unplug hour to now
                long h0 = s.from - s.from % 3600000L;
                int n = (int) Math.max(1, (s.to - h0 + 3599999) / 3600000L);
                long[] b = new long[n + 1];
                b[0] = s.from;
                for (int i = 1; i < n; i++) b[i] = h0 + i * 3600000L;
                b[n] = s.to;
                Stats[] hs = computeMulti(b);
                long[] on2 = new long[n], off2 = new long[n], st2 = new long[n];
                int[] lv = new int[n];
                for (int i = 0; i < n; i++) {
                    on2[i] = hs[i].on; off2[i] = hs[i].off; st2[i] = h0 + i * 3600000L;
                    lv[i] = levelAt(pts, b[i + 1]);
                }
                runOnUiThread(() -> { sinceOn = on2; sinceOff = off2; sinceStarts = st2; sinceLvl = lv; });
            }
        } else if (t == 1) {
            long[] hb = new long[25];
            long hourNow = now - now % 3600000L; // start of current hour
            for (int i = 0; i <= 23; i++) hb[i] = hourNow - (23 - i) * 3600000L;
            hb[24] = now;
            Stats[] hs = computeMulti(hb);
            long[] hon = new long[24];
            for (int i = 0; i < 24; i++) hon[i] = hs[i].on;
            Seg hseg = new Seg();
            hseg.from = hb[hsel]; hseg.to = hb[hsel + 1];
            hseg.st = hs[hsel];
            int used = 0; boolean any = false;
            for (int i = 0; i < cycles.length(); i++) {
                Seg cs = cycleSeg(cycles.getJSONObject(i), now, hseg.from, hseg.to);
                if (cs != null && cs.used >= 0) { used += cs.used; any = true; if (cs.est) hseg.est = true; }
            }
            hseg.used = any ? used : -1;
            temps(hseg, samples);
            runOnUiThread(() -> { hourOn = hon; hourStarts = hb; hourSeg = hseg; });
        } else {
            for (int i = 0; i < 7; i++) on[i] = compute(starts[i], i == 6 ? now : starts[i + 1]).on;
            long ds = starts[d], de = d == 6 ? now : starts[d + 1];
            for (int i = 0; i < cycles.length(); i++) {
                Seg s = cycleSeg(cycles.getJSONObject(i), now, ds, de);
                if (s != null) segs.add(s);
            }
            total = new Seg();
            total.from = ds; total.to = de;
            total.st = compute(ds, de);
            temps(total, samples);
            for (Seg s : segs) temps(s, samples);
            int used = 0; boolean any = false;
            for (Seg s : segs) if (s.used >= 0) { used += s.used; any = true; if (s.est) total.est = true; }
            total.used = any ? used : -1;
            if (segs.size() >= 2) for (Seg s : segs) s.st = compute(s.from, s.to);
            else segs.clear();
        }

        final Seg fc0 = c0, fdone = done, ftotal = total;
        runOnUiThread(() -> {
            cur = fc0; lastDone = fdone; dayStarts = starts;
            if (t == 0) chartPts = pts;
            if (t == 2) { dayTotal = ftotal; daySegs = segs; dayOn = on; }
        });
    }

    /** Battery % at time t, straight-line between the readings we have. */
    static int levelAt(List<long[]> pts, long t) {
        long[] prev = null;
        for (long[] p : pts) {
            if (p[0] >= t) {
                if (prev == null || p[0] == prev[0]) return (int) p[1];
                return (int) Math.round(prev[1] + (p[1] - prev[1]) * (double) (t - prev[0]) / (p[0] - prev[0]));
            }
            prev = p;
        }
        return prev == null ? -1 : (int) prev[1];
    }

    static void temps(Seg s, JSONArray samples) throws Exception {
        for (int i = 0; i < samples.length(); i++) {
            JSONArray a = samples.getJSONArray(i);
            long t = a.getLong(0);
            if (t < s.from || t > s.to || a.length() < 5) continue;
            s.bat.add(a.getInt(3));
            s.cpu.add(a.getInt(4));
        }
    }

    Seg cycleSeg(JSONObject c, long now, long lo, long hi) throws Exception {
        long u = c.getLong("u"), pt = c.getLong("p");
        long end = pt == 0 ? now : pt;
        long from = Math.max(u, lo), to = Math.min(end, hi);
        if (to <= from) return null;
        Seg s = new Seg();
        s.from = from; s.to = to; s.uAt = u; s.pAt = pt;
        s.startLvl = c.getInt("ul");
        s.endLvl = pt == 0 ? curLvl : c.getInt("pl");
        s.partial = c.optBoolean("s");
        if (s.endLvl >= 0 && s.startLvl >= s.endLvl) {
            double frac = (double) (to - from) / Math.max(1, end - u);
            s.used = (int) Math.round((s.startLvl - s.endLvl) * frac);
            s.est = frac < 0.999;
        }
        return s;
    }

    Stats compute(long from, long to) { return computeMulti(new long[]{from, to})[0]; }

    /** One pass over Android's activity log, split into consecutive buckets bounds[i]..bounds[i+1]. */
    Stats[] computeMulti(long[] bounds) {
        int nb = bounds.length - 1;
        long from = bounds[0], to = bounds[nb];
        Stats[] out = new Stats[nb];
        for (int i = 0; i < nb; i++) out[i] = new Stats();
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        // Look back 6 h before the window so apps that were already open at the start are counted;
        // all time is clipped to [from, to].
        UsageEvents ev = usm.queryEvents(from - 6 * 3600000L, to);
        UsageEvents.Event e = new UsageEvents.Event();
        Boolean on = null;
        long last = from - 6 * 3600000L;
        // Every app currently visible. Several can be at once (floating window / split screen).
        // pkg -> set of its open screens, and pkg -> when it became visible.
        Map<String, Set<String>> openCls = new HashMap<>();
        Map<String, Long> visibleSince = new HashMap<>();
        Map<String, Long> fgs = new HashMap<>();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            long t = e.getTimeStamp();
            int type = e.getEventType();
            String pkg = e.getPackageName(), cls = e.getClassName();
            if (type == UsageEvents.Event.SCREEN_INTERACTIVE || type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                boolean isOn = type == UsageEvents.Event.SCREEN_INTERACTIVE;
                if (on == null) on = !isOn;
                for (int i = 0; i < nb; i++) { long sp = clip(last, t, bounds[i], bounds[i + 1]); if (on) out[i].on += sp; else out[i].off += sp; }
                on = isOn; last = t;
                if (!isOn) {   // screen off: nothing is visible any more
                    for (Map.Entry<String, Long> v : visibleSince.entrySet()) addAppB(out, bounds, false, v.getKey(), v.getValue(), t);
                    visibleSince.clear();
                    openCls.clear();
                }
            } else if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                Set<String> set = openCls.get(pkg);
                if (set == null) { set = new HashSet<>(); openCls.put(pkg, set); }
                set.add(String.valueOf(cls));
                if (!visibleSince.containsKey(pkg)) visibleSince.put(pkg, t);
            } else if (type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                // Close only that screen. The app keeps counting while any of its screens is still showing
                // (e.g. a game switching screens, or a floating window while another app is full screen).
                Set<String> set = openCls.get(pkg);
                if (set != null) {
                    set.remove(String.valueOf(cls));
                    if (set.isEmpty()) {
                        openCls.remove(pkg);
                        Long st = visibleSince.remove(pkg);
                        if (st != null) addAppB(out, bounds, false, pkg, st, t);
                    }
                }
            } else if (type == UsageEvents.Event.FOREGROUND_SERVICE_START) {
                fgs.put(pkg + "|" + cls, t);
            } else if (type == UsageEvents.Event.FOREGROUND_SERVICE_STOP) {
                Long st = fgs.remove(pkg + "|" + cls);
                if (st != null) addAppB(out, bounds, true, pkg, st, t);
            }
        }
        if (on == null) on = getSystemService(PowerManager.class).isInteractive();
        for (int i = 0; i < nb; i++) { long sp = clip(last, to, bounds[i], bounds[i + 1]); if (on) out[i].on += sp; else out[i].off += sp; }
        if (on) for (Map.Entry<String, Long> v : visibleSince.entrySet()) addAppB(out, bounds, false, v.getKey(), v.getValue(), to);
        for (Map.Entry<String, Long> o : fgs.entrySet())
            addAppB(out, bounds, true, o.getKey().substring(0, o.getKey().indexOf('|')), o.getValue(), to);
        // Screen-on time no app claimed = home screen / lock screen / notification shade.
        // ColorOS often doesn't log the launcher when you swipe home, so give it that leftover time
        // (this is what the phone's own Battery page counts as "System Launcher").
        String home = launcherPkg();
        for (Stats st : out) {
            long claimed = 0;
            for (long v : st.apps.values()) claimed += v;
            long rest = st.on - claimed;
            if (home != null && rest > 0) st.apps.merge(home, rest, Long::sum);
            st.apps.remove(getPackageName());
            st.bg.remove(getPackageName());
        }
        return out;
    }

    String homePkg;

    String launcherPkg() {
        if (homePkg != null) return homePkg;
        try {
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName))
                homePkg = ri.activityInfo.packageName;
        } catch (Exception ignored) { }
        return homePkg;
    }

    static long clip(long a, long b, long from, long to) { return Math.max(0, Math.min(b, to) - Math.max(a, from)); }

    static void addAppB(Stats[] out, long[] bounds, boolean bg, String pkg, long a, long b) {
        for (int i = 0; i < out.length; i++) addApp(bg ? out[i].bg : out[i].apps, pkg, a, b, bounds[i], bounds[i + 1]);
    }

    static void addApp(Map<String, Long> m, String pkg, long a, long b, long from, long to) {
        long d = clip(a, b, from, to);
        if (d > 0) m.merge(pkg, d, Long::sum);
    }

    // ================= UI =================
    void buildNoAccess() {
        tabFrame.setVisibility(View.GONE);
        for (int i = 0; i < 3; i++) tabScroll[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        tab = 0;
        root = tabRoot[0];
        root.removeAllViews();
        builtVer[0] = -1;
        LinearLayout c = card(root);
        text(c, "This app needs \"Usage access\" to read screen and app activity.\n\nTap the button, find this app, and turn it on. "
                + "If it says the setting is restricted: Settings > Apps > this app > ⋮ > Allow restricted settings.", 15, TXT, false);
        button(c, "Grant usage access", v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
    }

    void build() { buildTab(tab); }

    /** (Re)builds one tab's page. Doesn't touch which page is showing. */
    void buildTab(int t) {
        if (!hasUsageAccess()) { buildNoAccess(); return; }
        tabFrame.setVisibility(View.VISIBLE);
        LinearLayout keep = root;
        root = tabRoot[t];
        root.removeAllViews();
        if (dataVer[t] == 0) text(root, "Loading…", 14, SUB, false);
        else if (t == 0) buildSince(); else if (t == 1) buildHours(); else buildWeek();
        LinearLayout f = card(root);
        button(f, "Refresh", v -> load());
        button(f, "Mark \"just unplugged\" now", v -> { ChargeWatchJob.markUnplugged(this); load(); });
        text(f, "To save battery, the app only wakes up while charging. Unplug time and level are caught the moment you unplug; on battery, temperatures and the battery graph are sampled only when you open the app. Allow background activity for this app in "
                + "Settings > Battery for best results. Per-app battery % is an estimate: Android doesn't let apps read real per-app "
                + "battery use, so the battery drop is split by each app's on-screen time plus 30% weight for background (foreground-service) time. Includes system apps. Pure background work without a notification (e.g. cell standby) can't be seen by apps.", 12, SUB, false);
        builtVer[t] = dataVer[t];
        root = tabRoot[tab];
    }

    /** Runs a builder with `root` pointed at another container. */
    void into(LinearLayout box, Runnable r) {
        LinearLayout keep = root;
        root = box;
        try { r.run(); } finally { root = keep; }
    }

    /** After a bar is picked: refill only the boxes under the chart and fade them in. */
    void rebuildDetails(int t) {
        LinearLayout a = t == 1 ? hourStatsBox : weekStatsBox, b = t == 1 ? hourAppsBox : weekAppsBox;
        if (a == null || b == null) { buildTab(t); return; }
        a.removeAllViews();
        b.removeAllViews();
        if (t == 1) { into(a, this::hourStats); into(b, this::hourApps); }
        else { into(a, this::weekStats); into(b, this::weekApps); }
        for (View v : new View[]{a, b}) { v.setAlpha(0.35f); v.animate().alpha(1f).setDuration(220).setInterpolator(EASE).start(); }
    }

    LinearLayout page;
    android.widget.FrameLayout tabFrame;
    View tabPill;
    TextView[] tabLabels;
    static final android.view.animation.Interpolator EASE = new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);

    /** Tab bar with a highlight that slides between tabs (built once, never rebuilt). */
    void buildTabs() {
        tabFrame = new android.widget.FrameLayout(this);
        tabFrame.setBackground(round(CARD, 26));
        tabFrame.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabPill = new View(this);
        tabPill.setBackground(round(SEL, 22));
        tabFrame.addView(tabPill, new android.widget.FrameLayout.LayoutParams(0, dp(44)));   // exact size, set from the labels below
        LinearLayout row = new LinearLayout(this);
        String[] names = {"Since charge", "24 hours", "7 days"};
        tabLabels = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(15);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(11), 0, dp(11));
            t.setTextColor(i == tab ? TXT : SUB);
            t.setOnClickListener(v -> switchTab(idx));
            tabLabels[i] = t;
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        tabFrame.addView(row, new android.widget.FrameLayout.LayoutParams(-1, -2));
        tabFrame.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            int w = (r - l - dp(8)) / tabLabels.length, h = row.getHeight();
            android.view.ViewGroup.LayoutParams p = tabPill.getLayoutParams();
            if (p.width != w || (h > 0 && p.height != h)) { p.width = w; if (h > 0) p.height = h; tabPill.setLayoutParams(p); }
            tabPill.setTranslationX(tab * w);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        page.addView(tabFrame, lp);
    }

    void switchTab(int idx) {
        if (idx == tab) return;
        int old = tab;
        tab = idx;
        int w = tabPill.getWidth();
        tabPill.animate().translationX(idx * w).setDuration(320).setInterpolator(EASE).start();
        for (int i = 0; i < tabLabels.length; i++) {
            final TextView t = tabLabels[i];
            int from = t.getCurrentTextColor(), to = i == idx ? TXT : SUB;
            if (from == to) continue;
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofArgb(from, to);
            va.setDuration(260);
            va.addUpdateListener(a -> t.setTextColor((int) a.getAnimatedValue()));
            va.start();
        }
        // content: both pages are already built - cross-fade them (no rebuild, no blank frame)
        if (builtVer[idx] != dataVer[idx]) buildTab(idx);
        root = tabRoot[idx];
        final ScrollView out = tabScroll[old], in = tabScroll[idx];
        final float dir = idx > old ? 1 : -1;
        out.animate().cancel();
        in.animate().cancel();
        in.setAlpha(0f);
        in.setTranslationX(dir * dp(28));
        in.setVisibility(View.VISIBLE);
        in.animate().alpha(1f).translationX(0).setDuration(300).setInterpolator(EASE).start();
        out.animate().alpha(0f).translationX(-dir * dp(28)).setDuration(220).setInterpolator(EASE).withEndAction(() -> {
            if (tabScroll[tab] != out) { out.setVisibility(View.GONE); out.setAlpha(1f); out.setTranslationX(0); }
        }).start();
    }

    void buildSince() {
        Seg s = cur != null ? cur : lastDone;
        LinearLayout c = card(root);
        if (s == null) {
            text(c, chargingNow ? "Charging now" + (curLvl >= 0 ? " · " + curLvl + "%" : "")
                    : "No unplug recorded yet. Unplug your charger (or tap \"Mark just unplugged\") and come back.", 15, TXT, false);
            return;
        }
        // Header: "Last charged to 90%"  /  "5 h 25 m ago"   ● Charging now
        TextView h = new TextView(this);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("Last charged to ");
        int st0 = sb.length();
        sb.append(s.startLvl + "%");
        sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st0, sb.length(), 0);
        sb.setSpan(new android.text.style.RelativeSizeSpan(1.15f), st0, sb.length(), 0);
        h.setText(sb);
        h.setTextSize(22);
        h.setTextColor(TXT);
        c.addView(h);
        LinearLayout sub = new LinearLayout(this);
        sub.setGravity(Gravity.CENTER_VERTICAL);
        TextView ago = new TextView(this);
        ago.setText(fmtHM((cur != null ? System.currentTimeMillis() : s.pAt) - s.uAt) + " ago" + (s.partial ? " (tracking started)" : ""));
        ago.setTextSize(15);
        ago.setTextColor(SUB);
        sub.addView(ago, new LinearLayout.LayoutParams(0, -2, 1));
        TextView leg = new TextView(this);
        leg.setText(chargingNow ? "● Charging now" : "Now " + curLvl + "%");
        leg.setTextSize(14);
        leg.setTextColor(chargingNow ? GREEN : SUB);
        sub.addView(leg);
        sub.setPadding(0, dp(4), 0, dp(8));
        c.addView(sub);

        int slots = Math.max(12, sinceOn.length);
        String[] labels = new String[slots];
        Calendar k = Calendar.getInstance();
        for (int i = 0; i < slots; i++) {
            labels[i] = "";
            if (i == sinceOn.length - 1) labels[i] = "Now";
            else if (i < sinceStarts.length) {
                k.setTimeInMillis(sinceStarts[i]);
                int hr = k.get(Calendar.HOUR_OF_DAY);
                if (hr % 4 == 0 || i == 0) labels[i] = String.valueOf(hr);
            }
        }
        // Battery level per hour
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(170));
        String[] rng = new String[sinceOn.length], lv = new String[sinceOn.length], onv = new String[sinceOn.length], offr = new String[sinceOn.length];
        SimpleDateFormat hf2 = new SimpleDateFormat("HH:mm", Locale.US);
        for (int i = 0; i < sinceOn.length; i++) {
            long a0 = Math.max(sinceStarts[i], s.from), a1 = i == sinceOn.length - 1 ? -1 : sinceStarts[i] + 3600000L;
            rng[i] = hf2.format(new Date(a0)) + "–" + (a1 < 0 ? "now" : hf2.format(new Date(a1)));
            lv[i] = i < sinceLvl.length && sinceLvl[i] >= 0 ? sinceLvl[i] + "%" : "—";
            onv[i] = fmtHM(sinceOn[i]) + " on";
            offr[i] = rng[i] + "  ·  " + fmtHM(sinceOff[i]) + " off";
        }
        if (lvlChart == null) lvlChart = new HourChart(this);
        lvlChart.setData(slots, labels, sinceLvl, null, null, lv, rng);
        lvlChart.dimViews = new View[]{h, sub};
        c.addView(detach(lvlChart), lp);

        // Big totals
        LinearLayout tot = new LinearLayout(this);
        tot.setPadding(0, dp(22), 0, dp(4));
        View bOn = bigStat(s.st.on, "Screen on", BLUE, false);
        tot.addView(bOn, new LinearLayout.LayoutParams(0, -2, 1));
        tot.addView(bigStat(s.st.off, "Screen off", LBLUE, true), new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(tot);
        // Screen on / off per hour, stacked
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, dp(170));
        lp2.topMargin = dp(10);
        if (stackChart == null) stackChart = new HourChart(this);
        stackChart.setData(slots, labels, null, sinceOn, sinceOff, onv, offr);
        stackChart.dimViews = new View[]{tot};
        c.addView(detach(stackChart), lp2);

        // Extra detail the phone's own page doesn't give
        View div = new View(this);
        div.setBackgroundColor(GREY);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        dl.topMargin = dp(16);
        c.addView(div, dl);
        String used = s.used >= 0 ? "Battery used " + s.used + "%  (" + s.startLvl + "% → " + s.endLvl + "%)" : "Battery used —";
        text(c, used, 15, TXT, true).setPadding(0, dp(12), 0, 0);
        if (s.used > 0)
            text(c, "1% battery ≈ " + fmtS(s.st.on / s.used) + " of screen-on time  ·  ≈ " + fmtS((s.to - s.from) / s.used) + " of total time", 13, SUB, false);
        tempRow(c, "CPU temp", s.cpu, 0xFFF97316);
        tempRow(c, "Battery temp", s.bat, 0xFFEAB308);

        appsCard(s, "Battery usage by app", null, false);
    }

    LinearLayout bigStat(long ms, String label, int dot, boolean alignRight) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(alignRight ? Gravity.END : Gravity.START);
        TextView v = new TextView(this);
        String txt = fmtHM(ms);
        android.text.SpannableString sp = new android.text.SpannableString(txt);
        for (int i = 0; i < txt.length(); i++)
            if (Character.isLetter(txt.charAt(i))) sp.setSpan(new android.text.style.RelativeSizeSpan(0.7f), i, i + 1, 0);
        sp.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, txt.length(), 0);
        v.setText(sp);
        v.setTextSize(30);
        v.setTextColor(TXT);
        TextView l = new TextView(this);
        android.text.SpannableString ls = new android.text.SpannableString("● " + label);
        ls.setSpan(new android.text.style.ForegroundColorSpan(dot), 0, 1, 0);
        l.setText(ls);
        l.setTextSize(15);
        l.setTextColor(SUB);
        col.addView(v);
        col.addView(l);
        return col;
    }

    /** "2 h 26 m" / "24 m" / "<1 m" */
    static String fmtHM(long ms) {
        long m = ms / 60000;
        if (m < 1) return "<1 m";
        if (m >= 1440) return (m / 1440) + " d " + (m % 1440 / 60) + " h";
        return m >= 60 ? (m / 60) + " h " + (m % 60) + " m" : m + " m";
    }

    void buildHours() {
        if (hourSeg == null) return;
        LinearLayout c = card(root);
        TextView hint = text(c, "Screen on per hour — tap or slide along the bars", 13, SUB, false);
        String[] labels = new String[24];
        SimpleDateFormat f = new SimpleDateFormat("HH", Locale.US);
        for (int i = 0; i < 24; i++) {
            Calendar k = Calendar.getInstance();
            k.setTimeInMillis(hourStarts[i]);
            labels[i] = i == 23 ? "Now" : (k.get(Calendar.HOUR_OF_DAY) % 4 == 0 && i <= 20 ? f.format(new Date(hourStarts[i])) : "");
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(190));
        lp.topMargin = dp(44);   // room for the value card above the bars
        String[] tips = new String[24], vals = new String[24];
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.US);
        for (int i = 0; i < 24; i++) {
            tips[i] = tf.format(new Date(hourStarts[i])) + "–" + (i == 23 ? "now" : tf.format(new Date(hourStarts[i + 1])));
            vals[i] = fmtHM(hourOn[i]);
        }
        if (hourChart == null) hourChart = new BarChart(this, i -> { selHour = i; load(1, true); });
        hourChart.setData(hourOn, labels, vals, tips, selHour, 3600000L);
        hourChart.dimViews = new View[]{hint};
        c.addView(detach(hourChart), lp);
        hourStatsBox = box(c);
        hourAppsBox = box(root);
        into(hourStatsBox, this::hourStats);
        into(hourAppsBox, this::hourApps);
    }

    LinearLayout hourStatsBox, hourAppsBox, weekStatsBox, weekAppsBox;

    LinearLayout box(LinearLayout parent) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setClipChildren(false);
        parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
        return b;
    }

    String hourRange() {
        SimpleDateFormat hf = new SimpleDateFormat("HH:mm", Locale.US);
        return hf.format(new Date(hourStarts[selHour])) + "–" + (selHour == 23 ? "now" : hf.format(new Date(hourStarts[selHour + 1])));
    }
    void hourStats() {
        text(root, hourRange(), 18, TXT, true).setPadding(0, dp(14), 0, 0);
        statsRow(root, hourSeg);
    }
    void hourApps() { appsCard(hourSeg, "Battery usage by app (" + hourRange() + ")", null, false); }

    void buildWeek() {
        if (dayTotal == null) return;
        LinearLayout c = card(root);
        TextView hint = text(c, "Screen on per day — tap or slide along the bars", 13, SUB, false);
        String[] labels = new String[7];
        SimpleDateFormat f = new SimpleDateFormat("d/M", Locale.getDefault());
        for (int i = 0; i < 7; i++) labels[i] = i == 6 ? "Today" : f.format(new Date(dayStarts[i]));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(190));
        lp.topMargin = dp(44);   // room for the value card above the bars
        String[] tips = new String[7];
        SimpleDateFormat df = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
        long mx = 0;
        for (int i = 0; i < 7; i++) { tips[i] = df.format(new Date(dayStarts[i])); mx = Math.max(mx, dayOn[i]); }
        long hrs = Math.max(2, (mx + 3599999) / 3600000);
        if (hrs % 2 == 1) hrs++;                       // even number of hours so the middle line is whole
        String[] vals = new String[7];
        for (int i = 0; i < 7; i++) vals[i] = fmtHM(dayOn[i]);
        if (weekChart == null) weekChart = new BarChart(this, i -> { selDay = i; load(2, true); });
        weekChart.setData(dayOn, labels, vals, tips, selDay, hrs * 3600000L);
        weekChart.dimViews = new View[]{hint};
        c.addView(detach(weekChart), lp);
        weekStatsBox = box(c);
        weekAppsBox = box(root);
        into(weekStatsBox, this::weekStats);
        into(weekAppsBox, this::weekApps);
    }

    void weekStats() {
        String day = selDay == 6 ? "Today" : new SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(new Date(dayStarts[selDay]));
        text(root, day, 18, TXT, true).setPadding(0, dp(14), 0, 0);
        statsRow(root, dayTotal);
    }

    void weekApps() {
        if (daySegs.size() >= 2) {
            section(daySegs.size() + " battery cycles on this day");
            int n = 1;
            for (Seg s : daySegs) {
                String head = "Cycle " + n++;
                String sub = "Unplugged " + hm(s.uAt) + " (" + s.startLvl + "%)"
                        + (s.pAt != 0 ? " → plugged in " + hm(s.pAt) + " (" + s.endLvl + "%)" : " → still on battery")
                        + (s.uAt < s.from || (s.pAt != 0 && s.pAt > s.to) ? "\nShowing only the part within this day" : "");
                appsCard(s, head, sub, true);
            }
        } else {
            appsCard(dayTotal, "Battery usage by app", null, false);
        }
    }

    void statsRow(LinearLayout parent, Seg s) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(12), 0, dp(4));
        String bat = s.used >= 0 ? (s.est ? "≈" : "") + s.used + "%" : "—";
        statCell(row, "Screen on", fmt(s.st.on), BLUE);
        statCell(row, "Screen off", fmt(s.st.off), DBLUE);
        statCell(row, "Battery used", bat, GREEN);
        parent.addView(row);
        if (s.used > 0) {
            String per = "1% battery ≈ " + fmtS(s.st.on / s.used) + " of screen-on time  ·  ≈ "
                    + fmtS((s.to - s.from) / s.used) + " of total time";
            TextView t = text(parent, per, 13, TXT, false);
            t.setPadding(0, dp(4), 0, dp(2));
        }
        tempRow(parent, "CPU temp", s.cpu, 0xFFF97316);
        tempRow(parent, "Battery temp", s.bat, 0xFFEAB308);
    }

    void tempRow(LinearLayout parent, String name, Temp t, int dot) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(8), 0, dp(4));
        if (t.n == 0) {
            text(parent, "● " + name + ": " + (name.startsWith("CPU") ? "not readable on this phone / no samples yet" : "no samples yet"), 12, SUB, false);
            return;
        }
        text(parent, "● " + name, 12, SUB, false).setPadding(0, dp(8), 0, 0);
        row.setPadding(0, 0, 0, dp(4));
        statCell(row, "Min", c(t.min), dot);
        statCell(row, "Avg", c((int) (t.sum / t.n)), dot);
        statCell(row, "Max", c(t.max), dot);
        parent.addView(row);
    }

    static String c(int tenths) { return String.format(Locale.US, "%.1f°C", tenths / 10.0); }

    void statCell(LinearLayout row, String label, String value, int dot) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText("● " + label);
        l.setTextSize(12);
        l.setTextColor(SUB);
        android.text.SpannableString sp = new android.text.SpannableString(l.getText());
        sp.setSpan(new android.text.style.ForegroundColorSpan(dot), 0, 1, 0);
        l.setText(sp);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(19);
        v.setTextColor(TXT);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(l);
        col.addView(v);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
    }

    void appsCard(Seg s, String header, String sub, boolean stats) {
        LinearLayout c = card(root);
        text(c, header, 16, TXT, true);
        if (sub != null) text(c, sub, 13, SUB, false);
        if (stats) statsRow(c, s);
        Map<String, Double> weight = new HashMap<>();
        for (Map.Entry<String, Long> e : s.st.apps.entrySet()) weight.merge(e.getKey(), (double) e.getValue(), Double::sum);
        for (Map.Entry<String, Long> e : s.st.bg.entrySet()) weight.merge(e.getKey(), e.getValue() * BG_WEIGHT, Double::sum);
        List<Map.Entry<String, Double>> apps = new ArrayList<>(weight.entrySet());
        apps.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        double total = 0;
        for (Map.Entry<String, Double> e : apps) total += e.getValue();
        List<Map.Entry<String, Double>> shown = new ArrayList<>();
        for (Map.Entry<String, Double> e : apps) {
            if (e.getValue() < 30000 * BG_WEIGHT || shown.size() >= 60) break;
            shown.add(e);
        }
        final double tot = total;
        final int FIRST = 15;
        for (int i = 0; i < Math.min(FIRST, shown.size()); i++) {
            String k = shown.get(i).getKey();
            appRow(c, k, s.st.apps.getOrDefault(k, 0L), s.st.bg.getOrDefault(k, 0L), shown.get(i).getValue(), tot, s.used);
        }
        if (shown.size() > FIRST) {
            TextView more = new TextView(this);
            more.setText("Show all " + shown.size() + " apps");
            more.setTextSize(15);
            more.setTextColor(BLUE);
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, dp(12), 0, dp(4));
            more.setOnClickListener(v -> {
                c.removeView(more);
                for (int i = FIRST; i < shown.size(); i++) {
                    String k = shown.get(i).getKey();
                    appRow(c, k, s.st.apps.getOrDefault(k, 0L), s.st.bg.getOrDefault(k, 0L), shown.get(i).getValue(), tot, s.used);
                    View row = c.getChildAt(c.getChildCount() - 1);
                    row.setAlpha(0f);
                    row.animate().alpha(1f).setStartDelay(Math.min(12, i - FIRST) * 18L).setDuration(200).start();
                }
            });
            c.addView(more);
        }
        if (shown.isEmpty()) text(c, "No app usage recorded.", 14, SUB, false);
    }

    void appRow(LinearLayout parent, String pkg, long ms, long bgMs, double w, double total, int used) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        ImageView iv = new ImageView(this);
        Drawable cached = iconCache.get(pkg);
        if (cached != null) iv.setImageDrawable(cached);
        else if (!iconCache.containsKey(pkg)) {
            iconLoader.execute(() -> {
                Drawable dr = icon(pkg);
                if (dr != null) iv.post(() -> { iv.setImageDrawable(dr); iv.setAlpha(0f); iv.animate().alpha(1f).setDuration(150).start(); });
            });
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(40), dp(40));
        ilp.rightMargin = dp(14);
        row.addView(iv, ilp);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(label(pkg));
        name.setTextSize(16);
        name.setTextColor(TXT);
        String time = ms > 0 ? fmt(ms) + " on screen" : "";
        if (bgMs >= 60000) time += (time.isEmpty() ? "" : " · ") + fmt(bgMs) + " background";
        if (time.isEmpty()) time = "<1 min";
        double share = total > 0 ? 100.0 * w / total : 0;
        TextView info = new TextView(this);
        info.setText(time);
        info.setTextSize(13);
        info.setTextColor(SUB);
        TextView info2 = new TextView(this);
        String l2 = String.format(Locale.US, "%.1f%% of the battery drain", share);
        double appPct = used >= 0 && total > 0 ? used * w / total : -1;
        if (appPct >= 0.05) l2 += "  ·  1% ≈ " + fmtS((long) ((ms + bgMs) / appPct)) + " of use";
        info2.setText(l2);
        info2.setTextSize(12);
        info2.setTextColor(SUB);
        col.addView(name);
        col.addView(info);
        col.addView(info2);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        // Right side: the one number that matters - how much of the battery this app used
        LinearLayout rc = new LinearLayout(this);
        rc.setOrientation(LinearLayout.VERTICAL);
        rc.setGravity(Gravity.END);
        TextView big = new TextView(this);
        big.setText(appPct >= 0 ? String.format(Locale.US, appPct < 10 ? "%.1f%%" : "%.0f%%", appPct)
                : String.format(Locale.US, "%.0f%%", share));
        big.setTextSize(18);
        big.setTextColor(TXT);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.END);
        TextView unit = new TextView(this);
        unit.setText(appPct >= 0 ? "of battery" : "of use");
        unit.setTextSize(11);
        unit.setTextColor(SUB);
        unit.setGravity(Gravity.END);
        rc.addView(big);
        rc.addView(unit);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.leftMargin = dp(10);
        row.addView(rc, rlp);
        parent.addView(row);
    }

    Drawable icon(String pkg) {
        if (iconCache.containsKey(pkg)) return iconCache.get(pkg);
        Drawable d = null;
        try { d = getPackageManager().getApplicationIcon(pkg); } catch (Exception ignored) { }
        iconCache.put(pkg, d);
        return d;
    }

    String label(String pkg) {
        String l = labelCache.get(pkg);
        if (l != null) return l;
        try {
            PackageManager pm = getPackageManager();
            l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ex) { l = pkg; }
        labelCache.put(pkg, l);
        return l;
    }

    // ---- small UI helpers ----
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 18));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setClipChildren(false);
        c.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        parent.addView(c, lp);
        return c;
    }

    TextView text(LinearLayout parent, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(2), 0, dp(2));
        parent.addView(t);
        return t;
    }

    void title(String s) {
        TextView t = text(root, s, 26, TXT, true);
        t.setPadding(dp(4), 0, 0, dp(14));
    }

    void section(String s) {
        TextView t = text(root, s, 13, SUB, false);
        t.setPadding(dp(4), dp(8), 0, dp(8));
    }

    void button(LinearLayout parent, String s, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(15);
        b.setTextColor(TXT);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(12), 0, dp(12));
        b.setBackground(round(SEL, 14));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        parent.addView(b, lp);
    }

    // ---- time helpers ----
    static long startOfDay(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static long addDays(long t, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.add(Calendar.DAY_OF_YEAR, days);
        return c.getTimeInMillis();
    }

    static String fmt(long ms) {
        long m = ms / 60000, h = m / 60;
        if (m < 1) return "<1 min";
        if (h >= 24) return (h / 24) + "d " + (h % 24) + "h";
        return h > 0 ? h + "h " + (m % 60) + "min" : m + " min";
    }

    static String fmtS(long ms) {
        if (ms >= 3600000L) return fmt(ms);
        long m = ms / 60000, sec = (ms / 1000) % 60;
        return m > 0 ? m + " min" + (m < 10 && sec > 0 ? " " + sec + " s" : "") : sec + " s";
    }

    static String hm(long t) { return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(t)); }
    static String when(long t) { return new SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(new Date(t)); }

    // ================= Charts =================
    static class LineChart extends View {
        final List<long[]> pts;
        final long from, to;
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;

        LineChart(Context c, List<long[]> pts, long from, long to) {
            super(c);
            this.pts = pts; this.from = from; this.to = to;
            d = c.getResources().getDisplayMetrics().density;
            line.setColor(GREEN); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(2.5f * d);
            line.setStrokeJoin(Paint.Join.ROUND);
            grid.setColor(GREY); grid.setStrokeWidth(d);
            txt.setColor(SUB); txt.setTextSize(11 * d);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float right = w - 38 * d, top = 8 * d, bottom = h - 20 * d;
            for (int pct : new int[]{0, 50, 100}) {
                float y = bottom - (bottom - top) * pct / 100f;
                cv.drawLine(0, y, right, y, grid);
                cv.drawText(pct + "%", right + 6 * d, y + 4 * d, txt);
            }
            if (pts.size() >= 2) {
                Path path = new Path();
                float span = Math.max(1, to - from);
                for (int i = 0; i < pts.size(); i++) {
                    long[] p = pts.get(i);
                    float x = right * ((p[0] - from) / span);
                    float y = bottom - (bottom - top) * p[1] / 100f;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                cv.drawPath(path, line);
            }
            // Hourly time axis: a tick + faint line every hour, labels every hour if they fit
            // (otherwise every 2/3/4/6/12 h so they don't overlap).
            long span = Math.max(1, to - from);
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(from);
            cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() < from) cal.add(Calendar.HOUR_OF_DAY, 1);
            float hourPx = right * 3600000f / span;
            float labelW = txt.measureText("12am") + 8 * d;
            int step = 1;
            for (int st : new int[]{1, 2, 3, 4, 6, 12, 24}) { step = st; if (hourPx * st >= labelW) break; }
            Paint tick = new Paint(grid);
            tick.setAlpha(70);
            SimpleDateFormat lf = new SimpleDateFormat("ha", Locale.US);
            if (cal.getTimeInMillis() > to) { // less than an hour on battery: show start and end time
                cv.drawText(hm(from), 0, h - 4 * d, txt);
                String e = hm(to);
                cv.drawText(e, right - txt.measureText(e), h - 4 * d, txt);
            }
            while (cal.getTimeInMillis() <= to) {
                long t = cal.getTimeInMillis();
                float x = right * ((t - from) / (float) span);
                cv.drawLine(x, top, x, bottom, tick);
                int hr = cal.get(Calendar.HOUR_OF_DAY);
                if (hr % step == 0) {
                    String l = lf.format(new Date(t)).toLowerCase(Locale.US);
                    float lw = txt.measureText(l);
                    float lx = Math.max(0, Math.min(right - lw, x - lw / 2));
                    cv.drawText(l, lx, h - 4 * d, txt);
                }
                cal.add(Calendar.HOUR_OF_DAY, 1);
            }
        }
    }

    /** Shared touch + value-card behaviour for both chart types, styled like the phone's Battery page:
     *  press or slide along the bars, the touched bar is highlighted and a rounded card with a big
     *  value and its time range follows the finger, joined to the bar by a thin line. */
    static abstract class TouchChart extends View {
        final float d;
        final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(Paint.ANTI_ALIAS_FLAG),
                bar = new Paint(Paint.ANTI_ALIAS_FLAG), cardP = new Paint(Paint.ANTI_ALIAS_FLAG),
                big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        String[] labels = new String[0], tipVal = new String[0], tipRange = new String[0];
        int slots = 1, sel = -1;
        float tipAlpha = 0f, focus = 0f, tipX = -1;        // animated
        boolean persistent;                                // keep a selection after the finger lifts
        android.animation.ValueAnimator aTip, aFocus, aX;
        float downX, downY;
        boolean dragging;
        final int slop;
        final Runnable hide = () -> fadeTip(0f);
        View[] dimViews = new View[0];               // text the card floats over; dimmed while it shows

        TouchChart(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
            txt.setTextSize(12.5f * d);
            txt.setColor(SUB);
            grid.setColor(0xFF3A3A3C);
            grid.setStrokeWidth(d);
            cardP.setColor(0xF2343438);
            cardP.setShadowLayer(14 * d, 0, 4 * d, 0x80000000);
            big.setColor(TXT);
            big.setTextSize(27 * d);
            big.setTypeface(Typeface.DEFAULT_BOLD);
            small.setColor(0xFFD1D1D6);
            small.setTextSize(15 * d);
            line.setColor(0x99FFFFFF);
            line.setStrokeWidth(d);
        }

        float right() { return getWidth() - 62 * d; }
        float slotW() { return right() / slots; }
        float cx(int i) { return i * slotW() + slotW() / 2; }
        abstract int count();
        abstract float barTop(int i);                      // y of the top of bar i

        void anim(android.animation.ValueAnimator old, float from, float to, long ms, java.util.function.Consumer<Float> set, int which) {
            if (old != null) old.cancel();
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(from, to);
            va.setDuration(ms);
            va.setInterpolator(EASE);
            va.addUpdateListener(a -> { set.accept((float) a.getAnimatedValue()); invalidate(); });
            va.start();
            if (which == 0) {
                aTip = va;
                va.addUpdateListener(a -> { for (View dv : dimViews) if (dv != null) dv.setAlpha(1f - 0.75f * tipAlpha); });
            } else if (which == 1) aFocus = va; else aX = va;
        }
        void fadeTip(float to) {
            anim(aTip, tipAlpha, to, to > 0 ? 160 : 260, v -> tipAlpha = v, 0);
            if (!persistent) anim(aFocus, focus, to, to > 0 ? 160 : 300, v -> focus = v, 1);
        }
        void select(int i, boolean animate) {
            i = Math.max(0, Math.min(count() - 1, i));
            if (i == sel && tipX >= 0) return;
            sel = i;
            float target = cx(i);
            if (!animate || tipX < 0) { tipX = target; invalidate(); }
            else anim(aX, tipX, target, 140, v -> tipX = v, 2);
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        }
        int indexAt(float x) { return (int) (x / slotW()); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (count() == 0) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    removeCallbacks(hide);
                    downX = e.getX(); downY = e.getY(); dragging = false;
                    if (tipX < 0 && sel >= 0) tipX = cx(sel);
                    select(indexAt(e.getX()), true);
                    fadeTip(1f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(e.getX() - downX) > slop && Math.abs(e.getX() - downX) > Math.abs(e.getY() - downY)) {
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);   // horizontal slide = scrub, not scroll
                    }
                    if (dragging) select(indexAt(e.getX()), true);
                    return true;
                case MotionEvent.ACTION_UP:
                    performClick();
                    onPicked(sel);
                    postDelayed(hide, 1400);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    postDelayed(hide, 200);
                    return true;
            }
            return true;
        }
        void onPicked(int i) { }
        @Override public boolean performClick() { return super.performClick(); }

        static int mix(int a, int b, float t) {
            return android.graphics.Color.argb(255,
                    (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t),
                    (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t),
                    (int) ((a & 255) + ((b & 255) - (a & 255)) * t));
        }

        void drawAxis(Canvas cv, String[] ax, float top, float bottom) {
            float w = getWidth();
            for (int k = 0; k <= 2; k++) {
                float y = bottom - (bottom - top) * k / 2f;
                cv.drawLine(0, y, right(), y, grid);
                cv.drawText(ax[k], w - txt.measureText(ax[k]), y + 4.5f * d, txt);
            }
        }
        void drawLabels(Canvas cv) {
            float h = getHeight(), slot = slotW(), nextLeft = Float.MAX_VALUE;
            for (int i = slots - 1; i >= 0; i--) {
                if (i >= labels.length || labels[i] == null || labels[i].isEmpty()) continue;
                float lw = txt.measureText(labels[i]);
                float lx = Math.max(0, Math.min(right() - lw, i * slot + (slot - lw) / 2));
                if (lx + lw + 8 * d > nextLeft) continue;
                cv.drawText(labels[i], lx, h - 5 * d, txt);
                nextLeft = lx;
            }
        }
        /** Value card above the chart (it may float over the text above it), thin line + dot down to the bar. */
        void drawTip(Canvas cv, float plotTop) {
            if (tipAlpha <= 0.01f || sel < 0 || sel >= tipVal.length) return;
            int al = (int) (255 * tipAlpha);
            String v = tipVal[sel], r = sel < tipRange.length ? tipRange[sel] : "";
            float pad = 14 * d, cw = Math.max(big.measureText(v), small.measureText(r)) + 2 * pad, ch = 74 * d;
            float lift = (1 - tipAlpha) * 6 * d;
            float cardBottom = plotTop - 10 * d + lift;
            float bx = Math.max(-4 * d, Math.min(right() - cw + 4 * d, tipX - cw / 2));
            RectF rc = new RectF(bx, cardBottom - ch, bx + cw, cardBottom);
            float by = barTop(sel);
            line.setAlpha((int) (150 * tipAlpha));
            cv.drawLine(tipX, cardBottom, tipX, by - 3 * d, line);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(BLUE); dot.setAlpha(al);
            cv.drawCircle(tipX, by - 3 * d, 3.2f * d, dot);
            cardP.setAlpha((int) (242 * tipAlpha));
            cv.drawRoundRect(rc, 14 * d, 14 * d, cardP);
            big.setAlpha(al); small.setAlpha(al);
            cv.drawText(v, rc.left + pad, rc.top + 34 * d, big);
            cv.drawText(r, rc.left + pad, rc.top + 60 * d, small);
        }
    }

    /** Screen-on per hour/day (24 hours & 7 days tabs). Selection stays and drives the details below. */
    static class BarChart extends TouchChart {
        long[] v = new long[0];
        long axisMax = 1;
        final OnPick cb;
        int picked = -1;

        BarChart(Context c, OnPick cb) { super(c); this.cb = cb; persistent = true; focus = 1f; }

        void setData(long[] v, String[] labels, String[] vals, String[] range, int sel, long axisMax) {
            this.v = v; this.labels = labels; this.tipVal = vals; this.tipRange = range;
            this.slots = v.length; this.axisMax = Math.max(1, axisMax);
            if (this.sel != sel || tipX < 0) { this.sel = sel; tipX = -1; }
            picked = sel;
            invalidate();
        }
        static String axis(long ms) { return ms < 3600000L ? (ms / 60000) + " min" : (ms / 3600000L) + " h"; }
        float top() { return 14 * d; }
        float bottom() { return getHeight() - 26 * d; }
        @Override int count() { return v.length; }
        @Override float barTop(int i) {
            float bh = Math.min(1f, v[i] / (float) axisMax) * (bottom() - top());
            return bottom() - Math.max(bh, v[i] > 0 ? 2 * d : 0);
        }
        @Override void onPicked(int i) { if (i != picked && cb != null) { picked = i; cb.pick(i); } }

        @Override protected void onDraw(Canvas cv) {
            if (tipX < 0 && sel >= 0 && getWidth() > 0) tipX = cx(sel);
            drawAxis(cv, new String[]{axis(0), axis(axisMax / 2), axis(axisMax)}, top(), bottom());
            float slot = slotW(), bw = Math.max(3 * d, Math.min(slot * 0.5f, 12 * d));
            for (int i = 0; i < v.length; i++) {
                float t = barTop(i), x = cx(i);
                if (bottom() - t <= 0) continue;
                bar.setColor(i == sel ? BLUE : GREY);
                cv.drawRoundRect(new RectF(x - bw / 2, t, x + bw / 2, bottom()), bw / 2, bw / 2, bar);
            }
            drawLabels(cv);
            drawTip(cv, top());
        }
    }

    /** Since-charge charts: battery level per hour (lvl) or stacked screen on/off minutes (on/off).
     *  Bars are all coloured; while touching, the touched bar stays coloured and the rest turn grey. */
    static class HourChart extends TouchChart {
        int[] lvl;
        long[] on, off;

        HourChart(Context c) { super(c); persistent = false; }

        void setData(int slots, String[] labels, int[] lvl, long[] on, long[] off, String[] vals, String[] range) {
            this.slots = slots; this.labels = labels; this.lvl = lvl; this.on = on; this.off = off;
            this.tipVal = vals; this.tipRange = range;
            if (sel >= count()) sel = -1;
            invalidate();
        }
        float top() { return 10 * d; }
        float bottom() { return getHeight() - 26 * d; }
        @Override int count() { return lvl != null ? lvl.length : (on != null ? on.length : 0); }
        @Override float barTop(int i) {
            float H = bottom() - top();
            if (lvl != null) return bottom() - H * Math.max(0, lvl[i]) / 100f;
            return Math.max(top(), bottom() - H * Math.min(1f, (on[i] + off[i]) / 3600000f));
        }

        @Override protected void onDraw(Canvas cv) {
            boolean level = lvl != null;
            drawAxis(cv, level ? new String[]{"0%", "50%", "100%"} : new String[]{"0 min", "30 min", "60 min"}, top(), bottom());
            float slot = slotW(), bw = Math.min(slot * 0.5f, 12 * d), r = 3 * d, H = bottom() - top();
            for (int i = 0; i < count(); i++) {
                float x = cx(i), l = x - bw / 2, rr = x + bw / 2;
                float g = i == sel ? 0f : focus;           // how grey this bar is
                if (level) {
                    if (lvl[i] < 0) continue;
                    float y = barTop(i);
                    bar.setShader(new android.graphics.LinearGradient(0, y, 0, bottom(), mix(BLUE, GREY, g), mix(0xFF6EC1F5, GREY, g),
                            android.graphics.Shader.TileMode.CLAMP));
                    cv.drawRoundRect(new RectF(l, y, rr, bottom()), r, r, bar);
                    bar.setShader(null);
                } else {
                    float yOn = bottom() - H * Math.min(1f, on[i] / 3600000f);
                    float yOff = Math.max(top(), yOn - H * Math.min(1f, off[i] / 3600000f));
                    if (off[i] > 0) { bar.setColor(mix(LBLUE, 0xFF5A5A5E, g)); cv.drawRoundRect(new RectF(l, yOff, rr, yOn + r), r, r, bar); }
                    if (on[i] > 0) {
                        bar.setColor(mix(BLUE, GREY, g));
                        cv.drawRoundRect(new RectF(l, yOn, rr, bottom()), r, r, bar);
                        if (off[i] > 0) cv.drawRect(l, yOn, rr, yOn + r, bar);
                    }
                }
            }
            drawLabels(cv);
            drawTip(cv, top());
        }
    }
}

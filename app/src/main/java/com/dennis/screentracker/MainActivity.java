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
            BLUE = 0xFF3B82F6, DBLUE = 0xFF1E40AF, GREEN = 0xFF22C55E, GREY = 0xFF48484A, SEL = 0xFF3A3A3C;

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

    LinearLayout root;
    int tab = 0, selDay = 6;
    boolean loading, reload, chargingNow;
    int curLvl = -1;
    Seg cur, lastDone, dayTotal;
    List<Seg> daySegs = new ArrayList<>();
    List<long[]> chartPts = new ArrayList<>();
    long[] dayOn = new long[7], dayStarts = new long[7];
    final Map<String, Drawable> iconCache = new HashMap<>();
    final Map<String, String> labelCache = new HashMap<>();

    // ================= Lifecycle =================
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(48), dp(16), dp(56));
        sv.addView(root);
        setContentView(sv);
        ChargeWatchJob.schedule(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!hasUsageAccess()) { buildNoAccess(); return; }
        if (root.getChildCount() == 0) { title("Battery"); text(root, "Loading…", 14, SUB, false); }
        load();
    }

    boolean hasUsageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    void load() {
        if (loading) { reload = true; return; }
        loading = true;
        final int t = tab, d = selDay;
        new Thread(() -> {
            try { computeModel(t, d); } catch (Exception ignored) { }
            runOnUiThread(() -> {
                loading = false;
                if (reload) { reload = false; load(); return; }
                build();
            });
        }).start();
    }

    // ================= Computation =================
    void computeModel(int t, int d) throws Exception {
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
            }
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
            cur = fc0; lastDone = fdone; dayTotal = ftotal;
            chartPts = pts; dayStarts = starts; daySegs = segs;
            if (t == 1) dayOn = on;
        });
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

    Stats compute(long from, long to) {
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        UsageEvents ev = usm.queryEvents(from, to);
        UsageEvents.Event e = new UsageEvents.Event();
        Stats s = new Stats();
        Boolean on = null;
        long last = from;
        Map<String, Long> openSince = new HashMap<>(), fgs = new HashMap<>();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            long t = e.getTimeStamp();
            int type = e.getEventType();
            if (type == UsageEvents.Event.SCREEN_INTERACTIVE || type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                boolean isOn = type == UsageEvents.Event.SCREEN_INTERACTIVE;
                if (on == null) on = !isOn;
                if (on) s.on += t - last; else s.off += t - last;
                on = isOn; last = t;
                if (!isOn) closeAll(openSince, s, t);
            } else if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                closeAll(openSince, s, t);
                openSince.put(e.getPackageName(), t);
            } else if (type == UsageEvents.Event.FOREGROUND_SERVICE_START) {
                fgs.put(e.getPackageName() + "|" + e.getClassName(), t);
            } else if (type == UsageEvents.Event.FOREGROUND_SERVICE_STOP) {
                Long st = fgs.remove(e.getPackageName() + "|" + e.getClassName());
                if (st != null) s.bg.merge(e.getPackageName(), t - st, Long::sum);
            } else if (type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                Long st = openSince.remove(e.getPackageName());
                if (st != null) s.apps.merge(e.getPackageName(), t - st, Long::sum);
            }
        }
        if (on == null) on = getSystemService(PowerManager.class).isInteractive();
        if (on) s.on += to - last; else s.off += to - last;
        closeAll(openSince, s, to);
        for (Map.Entry<String, Long> o : fgs.entrySet())
            s.bg.merge(o.getKey().substring(0, o.getKey().indexOf('|')), to - o.getValue(), Long::sum);
        s.apps.remove(getPackageName());
        s.bg.remove(getPackageName());
        return s;
    }

    static void closeAll(Map<String, Long> open, Stats s, long t) {
        for (Map.Entry<String, Long> o : open.entrySet()) s.apps.merge(o.getKey(), t - o.getValue(), Long::sum);
        open.clear();
    }

    // ================= UI =================
    void buildNoAccess() {
        root.removeAllViews();
        title("Battery");
        LinearLayout c = card(root);
        text(c, "This app needs \"Usage access\" to read screen and app activity.\n\nTap the button, find this app, and turn it on. "
                + "If it says the setting is restricted: Settings > Apps > this app > ⋮ > Allow restricted settings.", 15, TXT, false);
        button(c, "Grant usage access", v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
    }

    void build() {
        if (!hasUsageAccess()) { buildNoAccess(); return; }
        root.removeAllViews();
        title("Battery");
        tabs();
        if (tab == 0) buildSince(); else buildWeek();
        LinearLayout f = card(root);
        button(f, "Refresh", v -> load());
        button(f, "Mark \"just unplugged\" now", v -> { ChargeWatchJob.markUnplugged(this); load(); });
        text(f, "To save battery, the app only wakes up while charging (about every 15 min). Unplug time can be up to 15 min off; plug-in is detected within minutes. On battery, temperatures and the graph are sampled only when you open the app. Allow background activity for this app in "
                + "Settings > Battery for best results. Per-app battery % is an estimate: Android doesn't let apps read real per-app "
                + "battery use, so the cycle's battery drop is split by each app's on-screen time plus 30% weight for background (foreground-service) time. Includes system apps. Pure background work without a notification (e.g. cell standby) can't be seen by apps.", 12, SUB, false);
    }

    void tabs() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(round(CARD, 24));
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] names = {"Since last charge", "Last 7 days"};
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(15);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(10), 0, dp(10));
            t.setTextColor(i == tab ? TXT : SUB);
            if (i == tab) t.setBackground(round(SEL, 20));
            t.setOnClickListener(v -> { if (tab != idx) { tab = idx; load(); } });
            bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        root.addView(bar, lp);
    }

    void buildSince() {
        LinearLayout c = card(root);
        if (chargingNow) text(c, "⚡ Charging now" + (curLvl >= 0 ? " · " + curLvl + "%" : ""), 15, GREEN, true);
        Seg s = cur != null ? cur : lastDone;
        if (s == null) {
            text(c, "No unplug recorded yet. Unplug your charger (or tap \"Mark just unplugged\") and come back.", 14, SUB, false);
            return;
        }
        text(c, cur != null ? "Used since last charge" : "Last time on battery", 13, SUB, false);
        text(c, fmt(s.to - s.from), 28, TXT, true);
        text(c, "Unplugged " + when(s.uAt) + " at " + s.startLvl + "%" + (s.partial ? " (tracking started)" : ""), 13, SUB, false);
        if (s.pAt != 0) text(c, "Plugged in " + when(s.pAt) + " at " + s.endLvl + "%", 13, SUB, false);
        else if (curLvl >= 0) text(c, "Now " + curLvl + "%", 13, SUB, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(150));
        lp.topMargin = dp(12);
        c.addView(new LineChart(this, chartPts, s.from, s.to), lp);
        statsRow(c, s);
        appsCard(s, "Battery usage by app", null, false);
    }

    void buildWeek() {
        if (dayTotal == null) return;
        LinearLayout c = card(root);
        text(c, "Screen on per day — tap a day", 13, SUB, false);
        String[] labels = new String[7];
        SimpleDateFormat f = new SimpleDateFormat("d/M", Locale.getDefault());
        for (int i = 0; i < 7; i++) labels[i] = i == 6 ? "Today" : f.format(new Date(dayStarts[i]));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(190));
        lp.topMargin = dp(8);
        c.addView(new BarChart(this, dayOn, labels, selDay, i -> { selDay = i; load(); }), lp);
        String day = selDay == 6 ? "Today" : new SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(new Date(dayStarts[selDay]));
        text(c, day, 18, TXT, true).setPadding(0, dp(14), 0, 0);
        statsRow(c, dayTotal);
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
        statCell(row, name + " min", c(t.min), dot);
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
        int n = 0;
        for (Map.Entry<String, Double> e : apps) {
            if (e.getValue() < 30000 * BG_WEIGHT || n >= 60) break;
            n++;
            String k = e.getKey();
            appRow(c, k, s.st.apps.getOrDefault(k, 0L), s.st.bg.getOrDefault(k, 0L), e.getValue(), total, s.used);
        }
        if (n == 0) text(c, "No app usage recorded.", 14, SUB, false);
    }

    void appRow(LinearLayout parent, String pkg, long ms, long bgMs, double w, double total, int used) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        ImageView iv = new ImageView(this);
        Drawable icon = icon(pkg);
        if (icon != null) iv.setImageDrawable(icon);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(40), dp(40));
        ilp.rightMargin = dp(14);
        row.addView(iv, ilp);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(label(pkg));
        name.setTextSize(16);
        name.setTextColor(TXT);
        TextView info = new TextView(this);
        String pct;
        if (used >= 0 && total > 0) pct = String.format(Locale.US, "≈%.1f%%", used * w / total);
        else pct = String.format(Locale.US, "%.1f%% of usage", total > 0 ? 100.0 * w / total : 0);
        String time = ms > 0 ? fmt(ms) + " on screen" : "";
        if (bgMs >= 60000) time += (time.isEmpty() ? "" : " · ") + fmt(bgMs) + " background";
        if (time.isEmpty()) time = "<1 min";
        info.setText(pct + "  |  " + time);
        info.setTextSize(13);
        info.setTextColor(SUB);
        col.addView(name);
        col.addView(info);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
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

    static class BarChart extends View {
        final long[] v;
        final String[] labels;
        final int sel;
        final OnPick cb;
        final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint();
        final float d;

        BarChart(Context c, long[] v, String[] labels, int sel, OnPick cb) {
            super(c);
            this.v = v; this.labels = labels; this.sel = sel; this.cb = cb;
            d = c.getResources().getDisplayMetrics().density;
            txt.setTextSize(11 * d);
            grid.setColor(GREY); grid.setStrokeWidth(d);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float right = w - 30 * d, top = 22 * d, bottom = h - 22 * d;
            long max = 1;
            for (long x : v) max = Math.max(max, x);
            long maxH = Math.max(1, (max + 3599999) / 3600000);
            float slot = right / v.length;
            txt.setColor(SUB);
            cv.drawLine(0, bottom, right, bottom, grid);
            cv.drawText(maxH + "h", right + 6 * d, top + 4 * d, txt);
            cv.drawText("0h", right + 6 * d, bottom + 4 * d, txt);
            for (int i = 0; i < v.length; i++) {
                float bh = (bottom - top) * v[i] / (float) (maxH * 3600000L);
                RectF r = new RectF(i * slot + slot * 0.22f, bottom - bh, (i + 1) * slot - slot * 0.22f, bottom);
                bar.setColor(i == sel ? BLUE : GREY);
                cv.drawRoundRect(r, 4 * d, 4 * d, bar);
                txt.setColor(i == sel ? TXT : SUB);
                float lw = txt.measureText(labels[i]);
                cv.drawText(labels[i], i * slot + (slot - lw) / 2, h - 4 * d, txt);
                if (i == sel) {
                    String s = fmt(v[i]);
                    float sw = txt.measureText(s);
                    float x = Math.max(0, Math.min(right - sw, i * slot + (slot - sw) / 2));
                    cv.drawText(s, x, bottom - bh - 6 * d, txt);
                }
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float slot = (getWidth() - 30 * d) / v.length;
                int i = (int) (e.getX() / slot);
                if (i >= 0 && i < v.length && cb != null) cb.pick(i);
                performClick();
            }
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }
    }
}

package com.dennis.screentracker;

import android.app.*;
import android.app.job.*;
import android.app.usage.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {

    /** Runs every ~15 min and records the moment the phone is unplugged. */
    public static class ChargeWatchJob extends JobService {
        static final String PREFS = "st";
        static void schedule(Context c) {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js.getPendingJob(1) != null) return;
            js.schedule(new JobInfo.Builder(1, new ComponentName(c, ChargeWatchJob.class))
                    .setPeriodic(15 * 60 * 1000L).setPersisted(true).build());
        }
        static void check(Context c) {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return;
            boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
            SharedPreferences p = c.getSharedPreferences(PREFS, 0);
            boolean wasPlugged = p.getBoolean("plugged", plugged);
            SharedPreferences.Editor e = p.edit().putBoolean("plugged", plugged);
            if (plugged) e.putInt("lastLevel", lvl);
            if (wasPlugged && !plugged) e.putLong("unplugAt", System.currentTimeMillis()).putInt("unplugLevel", p.getInt("lastLevel", lvl));
            e.apply();
        }
        @Override public boolean onStartJob(JobParameters p) { check(this); return false; }
        @Override public boolean onStopJob(JobParameters p) { return true; }
    }

    LinearLayout root;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(48), pad, pad);
        sv.addView(root);
        setContentView(sv);
        ChargeWatchJob.schedule(this);
    }

    @Override protected void onResume() { super.onResume(); ChargeWatchJob.check(this); render(); }

    boolean hasUsageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    void render() {
        root.removeAllViews();
        title("Screen Tracker");
        if (!hasUsageAccess()) {
            text("This app needs \"Usage access\" to read screen and app activity.\n\nTap the button, find Screen Tracker, and turn it on.", 16);
            button("Grant usage access", v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            return;
        }
        SharedPreferences p = getSharedPreferences(ChargeWatchJob.PREFS, 0);
        long now = System.currentTimeMillis();
        long since = p.getLong("unplugAt", 0);
        boolean charging = p.getBoolean("plugged", false);
        String sinceLabel;
        if (since == 0) { since = startOfDay(now); sinceLabel = "No unplug recorded yet - showing today"; }
        else sinceLabel = new SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(new Date(since))
                + "  (" + p.getInt("unplugLevel", 0) + "%)";

        section("Last charged");
        big(charging ? "Charging now" : sinceLabel);
        if (!charging && p.getLong("unplugAt", 0) != 0) text(fmt(now - since) + " ago", 14);

        Stats s = compute(since, now);
        section("Since last charge");
        row("Screen on", fmt(s.screenOn));
        row("Screen off", fmt(s.screenOff));

        section("Most active apps");
        List<Map.Entry<String, Long>> apps = new ArrayList<>(s.apps.entrySet());
        apps.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        int n = 0;
        for (Map.Entry<String, Long> e : apps) {
            if (e.getValue() < 60000 || n++ >= 10) break;
            row(label(e.getKey()), fmt(e.getValue()));
        }
        if (n == 0) text("Not enough data yet.", 14);

        section("");
        button("Refresh", v -> render());
        button("Mark \"just unplugged\" now", v -> {
            p.edit().putLong("unplugAt", System.currentTimeMillis()).putBoolean("plugged", false).apply();
            render();
        });
        text("Unplug time is checked about every 15 minutes in the background, so it can be up to 15 min off. "
                + "For best results, set Settings > Battery > Screen Tracker to \"Allow background activity\".", 12);
    }

    static class Stats { long screenOn, screenOff; Map<String, Long> apps = new HashMap<>(); }

    Stats compute(long from, long to) {
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        UsageEvents ev = usm.queryEvents(from, to);
        UsageEvents.Event e = new UsageEvents.Event();
        Stats s = new Stats();
        Boolean on = null; long last = from;
        Map<String, Long> openSince = new HashMap<>();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            long t = e.getTimeStamp();
            int type = e.getEventType();
            if (type == UsageEvents.Event.SCREEN_INTERACTIVE || type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                boolean isOn = type == UsageEvents.Event.SCREEN_INTERACTIVE;
                if (on == null) on = !isOn;
                if (on) s.screenOn += t - last; else s.screenOff += t - last;
                on = isOn; last = t;
                if (!isOn) closeAll(openSince, s, t);
            } else if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                closeAll(openSince, s, t);
                openSince.put(e.getPackageName(), t);
            } else if (type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                Long st = openSince.remove(e.getPackageName());
                if (st != null) s.apps.merge(e.getPackageName(), t - st, Long::sum);
            }
        }
        boolean nowOn = getSystemService(PowerManager.class).isInteractive();
        if (on == null) on = nowOn;
        if (on) s.screenOn += to - last; else s.screenOff += to - last;
        closeAll(openSince, s, to);
        s.apps.remove(getPackageName());
        return s;
    }

    void closeAll(Map<String, Long> open, Stats s, long t) {
        for (Map.Entry<String, Long> o : open.entrySet()) s.apps.merge(o.getKey(), t - o.getValue(), Long::sum);
        open.clear();
    }

    String label(String pkg) {
        try { PackageManager pm = getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception ex) { return pkg; }
    }

    static long startOfDay(long now) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static String fmt(long ms) {
        long m = ms / 60000, h = m / 60;
        if (h >= 24) return (h / 24) + "d " + (h % 24) + "h";
        return h > 0 ? h + "h " + (m % 60) + "m" : m + "m";
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    TextView text(String s, int sp) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setPadding(0, dp(4), 0, dp(4)); root.addView(t); return t; }
    void title(String s) { TextView t = text(s, 28); t.setTypeface(Typeface.DEFAULT_BOLD); }
    void big(String s) { TextView t = text(s, 20); t.setTypeface(Typeface.DEFAULT_BOLD); }
    void section(String s) { TextView t = text(s.toUpperCase(), 13); t.setPadding(0, dp(22), 0, dp(4)); t.setAlpha(0.6f); }
    void row(String l, String r) {
        LinearLayout ll = new LinearLayout(this); ll.setPadding(0, dp(6), 0, dp(6));
        TextView a = new TextView(this); a.setText(l); a.setTextSize(16);
        TextView b = new TextView(this); b.setText(r); b.setTextSize(16); b.setTypeface(Typeface.DEFAULT_BOLD);
        ll.addView(a, new LinearLayout.LayoutParams(0, -2, 1)); ll.addView(b);
        root.addView(ll);
    }
    void button(String s, View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setOnClickListener(l); root.addView(b); }
}

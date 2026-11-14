package com.fastcar.home;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** GitHub Releases'дан янги версияни текширади, юклайди ва ўрнатишни бошлайди. */
final class Updater {
    static final String API = "https://api.github.com/repos/anvarkimov/fast-car-launcher/releases/latest";
    static final String ACTION = "com.fastcar.home.INSTALL_DONE";

    static class Info {
        int code;
        String tag;
        String url;
    }

    private Updater() { }

    static int currentCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static HttpURLConnection open(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "FastLauncher");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        return c;
    }

    /** Охирги релиз ҳақида маълумот; топилмаса null. */
    static Info fetchLatest() throws Exception {
        HttpURLConnection c = open(API);
        if (c.getResponseCode() != 200) return null;
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        JSONObject j = new JSONObject(sb.toString());
        Info i = new Info();
        i.tag = j.getString("tag_name");
        i.code = Integer.parseInt(i.tag.replaceAll("[^0-9]", ""));
        JSONArray assets = j.getJSONArray("assets");
        for (int k = 0; k < assets.length(); k++) {
            JSONObject a = assets.getJSONObject(k);
            if (a.getString("name").endsWith(".apk")) {
                i.url = a.getString("browser_download_url");
                break;
            }
        }
        return i.url == null ? null : i;
    }

    /** APK'ни юклаб, тўғридан-тўғри PackageInstaller сессиясига ёзади. */
    static void downloadAndInstall(Context ctx, String url) throws Exception {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Accept", "application/octet-stream");
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());

        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            InputStream in = c.getInputStream();
            OutputStream out = s.openWrite("update", 0, -1);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            s.fsync(out);
            out.close();
            in.close();

            Intent i = new Intent(ctx, InstallReceiver.class).setAction(ACTION);
            PendingIntent pend = PendingIntent.getBroadcast(ctx, id, i,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
    }
}

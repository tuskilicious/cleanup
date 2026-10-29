package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Continue watching row's data: the system's Watch Next table (docs/watch-next.md), read once per Home
 * resume off the main thread. Needs READ_TV_LISTINGS (build-and-install grants it); without it the query
 * throws and the row stays hidden.
 *
 * Posters on the TV (content://, android.resource://, file://) and https:// ones on the apps' own CDNs, which is
 * where most apps put them. Web posters are fetched once into the cache directory, only for the cards on screen,
 * and the card shows the app's banner until its poster arrives. Plain http:// is never fetched.
 */
final class ContinueWatching {
    static final String TAG = "TvHomeContinue";
    /** Rows older than this are dropped: some apps leave them for months (docs/watch-next.md, TCL C6K). */
    private static final long MAX_AGE_MS = 60L * 24 * 60 * 60 * 1000;
    /** A "continue" row this far through is finished in all but name. */
    private static final double DONE_FRACTION = 0.95;
    static final int MAX_ITEMS = 10;
    /** Poster decode width; a card is 160dp wide, 2x on this box. */
    private static final int POSTER_PX = 360;

    static final class Item {
        final String pkg;
        final String title;
        final String subtitle;
        final String intentUri;
        final float progress;      // 0..1, or -1 when the app gives no position
        final String posterUri;    // null: the app's banner
        /** From the app's own preview channel, not Watch Next: used only when the app has no Watch Next rows. */
        final boolean channel;
        /** Music: a track, album, artist, playlist or station, so the row says "Continue listening". */
        final boolean audio;
        Bitmap poster;             // filled in by loadPoster, off the main thread

        Item(String pkg, String title, String subtitle, String intentUri, float progress, String posterUri) {
            this(pkg, title, subtitle, intentUri, progress, posterUri, false, false);
        }

        Item(String pkg, String title, String subtitle, String intentUri, float progress, String posterUri,
             boolean channel, boolean audio) {
            this.pkg = pkg;
            this.title = title;
            this.subtitle = subtitle;
            this.intentUri = intentUri;
            this.progress = progress;
            this.posterUri = posterUri;
            this.channel = channel;
            this.audio = audio;
        }
    }

    private ContinueWatching() {
    }

    /** Runs on a background thread. Never throws: any failure is one log line and an empty list. */
    static List<Item> load(Context context) {
        List<Item> items = new ArrayList<>();
        ContentResolver cr = context.getContentResolver();
        PackageManager pm = context.getPackageManager();
        long now = System.currentTimeMillis();
        Map<String, Integer> perPackage = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        List<long[]> order = new ArrayList<>();   // {engagement, index into items}
        // No selection: a caller without ACCESS_ALL_EPG_DATA gets a SecurityException when it passes one.
        try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) {
                Log.w(TAG, "Watch Next query returned no cursor (no TV provider?)");
                return items;
            }
            while (c.moveToNext()) {
                String pkg = str(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                if (pkg == null) continue;
                perPackage.merge(pkg, 1, Integer::sum);
                if (lng(c, TvContract.WatchNextPrograms.COLUMN_BROWSABLE, 1) == 0) continue;
                long engaged = lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, 0);
                if (engaged > 0 && now - engaged > MAX_AGE_MS) continue;
                String title = str(c, TvContract.WatchNextPrograms.COLUMN_TITLE);
                if (title == null || title.isEmpty()) continue;
                if (!seen.add(pkg + '\n' + title)) continue;   // one card per show
                if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) continue;

                long pos = lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 0);
                long dur = lng(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, 0);
                float progress = pos > 0 && dur > 0 ? (float) Math.min(1.0, (double) pos / dur) : -1f;
                int type = (int) lng(c, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, -1);
                if (type == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE && progress >= DONE_FRACTION) continue;

                String poster = str(c, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
                if (poster == null) poster = str(c, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
                Item item = new Item(pkg, title, subtitle(c), str(c, TvContract.WatchNextPrograms.COLUMN_INTENT_URI),
                        progress, usablePoster(poster) ? poster : null, false,
                        audioType((int) lng(c, TvContract.WatchNextPrograms.COLUMN_TYPE, -1)));
                item.poster = cached(item.posterUri);
                order.add(new long[]{engaged, items.size()});
                items.add(item);
            }
        } catch (Exception e) {
            // SecurityException without READ_TV_LISTINGS, or a provider that is missing or broken.
            Log.w(TAG, "Watch Next not readable: " + e);
            return new ArrayList<>();
        }
        Log.i(TAG, "Watch Next rows per app: " + perPackage + ", kept: " + items.size());
        Collections.sort(order, (a, b) -> Long.compare(b[0], a[0]));
        List<Item> sorted = new ArrayList<>();
        for (long[] o : order) sorted.add(items.get((int) o[1]));
        sorted.addAll(channelItems(cr, pm));
        return sorted;
    }

    /** Every app's preview-channel programs, in the order the provider gives them: Netflix publishes the shows
     *  you are watching there and Spotify your playlists, artists and radios, with no Watch Next rows at all. */
    private static List<Item> channelItems(ContentResolver cr, PackageManager pm) {
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> perPackage = new TreeMap<>();
        try (Cursor c = cr.query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = str(c, TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME);
                String title = str(c, TvContract.PreviewPrograms.COLUMN_TITLE);
                if (pkg == null || title == null || title.isEmpty()) continue;
                if (lng(c, TvContract.PreviewPrograms.COLUMN_BROWSABLE, 1) == 0) continue;
                String poster = str(c, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI);
                if (poster == null) poster = str(c, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI);
                // A program drawn from the app's own resources is its sign-up card ("Listen for free"), not a title.
                if (poster != null && poster.startsWith("android.resource://" + pkg + "/")) continue;
                if (!seen.add(pkg + '\n' + title)) continue;
                if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) continue;
                long pos = lng(c, TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 0);
                long dur = lng(c, TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, 0);
                Item item = new Item(pkg, title, subtitle(c), str(c, TvContract.PreviewPrograms.COLUMN_INTENT_URI),
                        pos > 0 && dur > 0 ? (float) Math.min(1.0, (double) pos / dur) : -1f,
                        usablePoster(poster) ? poster : null, true,
                        audioType((int) lng(c, TvContract.PreviewPrograms.COLUMN_TYPE, -1)));
                item.poster = cached(item.posterUri);
                items.add(item);
                perPackage.merge(pkg, 1, Integer::sum);
            }
        } catch (Exception e) {
            Log.w(TAG, "preview channels not readable: " + e);
        }
        Log.i(TAG, "Channel programs per app: " + perPackage);
        return items;
    }

    static boolean audioType(int type) {
        return type == TvContract.PreviewPrograms.TYPE_TRACK || type == TvContract.PreviewPrograms.TYPE_ALBUM
                || type == TvContract.PreviewPrograms.TYPE_ARTIST || type == TvContract.PreviewPrograms.TYPE_PLAYLIST
                || type == TvContract.PreviewPrograms.TYPE_STATION;
    }

    /** True when every card is music, so the row reads "Continue listening". */
    static boolean listening(List<Item> shown) {
        if (shown.isEmpty()) return false;
        for (Item item : shown) if (!item.audio) return false;
        return true;
    }

    /** The row for the focused dock app: its Watch Next rows, newest first, else its own preview channel, else
     *  nothing (the row hides). Never another app's rows. */
    static List<Item> forPackage(List<Item> all, String pkg) {
        List<Item> own = pick(all, pkg, false);
        return own.isEmpty() ? pick(all, pkg, true) : own;
    }

    private static List<Item> pick(List<Item> all, String pkg, boolean channel) {
        List<Item> own = new ArrayList<>();
        for (Item item : all) {
            if (own.size() == MAX_ITEMS) break;
            if (item.channel == channel && item.pkg.equals(pkg)) own.add(item);
        }
        return own;
    }

    /** "S2 · E5 · Episode title", or whatever part of it the app filled in. */
    private static String subtitle(Cursor c) {
        StringBuilder s = new StringBuilder();
        String season = str(c, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
        String episode = str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
        String name = str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
        if (season != null && !season.isEmpty()) s.append('S').append(season);
        if (episode != null && !episode.isEmpty()) s.append(s.length() > 0 ? " · " : "").append('E').append(episode);
        if (name != null && !name.isEmpty()) s.append(s.length() > 0 ? " · " : "").append(name);
        return s.toString();
    }

    /** Decoded posters by URI, so Home coming back does not fetch them again: Jellyfin's provider asks its
     *  server for each one, about four seconds a poster on this network. A dozen 360px RGB_565 posters is
     *  about 3 MB. */
    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > MAX_ITEMS + 2;
        }
    };

    /** A poster this row can show: one on the TV, or one on the web over https (never plain http). */
    static boolean usablePoster(String uri) {
        String scheme = scheme(uri);
        return "content".equals(scheme) || "android.resource".equals(scheme) || "file".equals(scheme)
                || "https".equals(scheme);
    }

    private static String scheme(String uri) {
        int colon = uri == null ? -1 : uri.indexOf(':');
        return colon < 0 ? null : uri.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
    }

    private static Bitmap cached(String uri) {
        if (uri == null) return null;
        synchronized (CACHE) {
            return CACHE.get(uri);
        }
    }

    /** Off the main thread, one at a time: fills item.poster and returns true when there is one to show. */
    static boolean loadPoster(Context context, Item item) {
        if (item.posterUri == null || item.poster != null) return false;
        Bitmap b = "https".equals(scheme(item.posterUri))
                ? decodeBytes(webPoster(context, item.posterUri))
                : decode(context.getContentResolver(), item.posterUri);
        if (b == null) return false;
        synchronized (CACHE) {
            CACHE.put(item.posterUri, b);
        }
        item.poster = b;
        return true;
    }

    private static Bitmap decode(ContentResolver cr, String uri) {
        Uri u = Uri.parse(uri);
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(u)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= POSTER_PX) opts.inSampleSize *= 2;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory, posters have no alpha
            try (InputStream in = cr.openInputStream(u)) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "poster not loaded: " + e);
            return null;
        }
    }

    /** Largest web poster fetched; a thumbnail is tens of KB, so anything bigger is not one. */
    private static final int MAX_POSTER_BYTES = 2 * 1024 * 1024;
    /** Web posters kept on disk, oldest dropped first: a few rows' worth across every app. */
    private static final int DISK_POSTERS = 100;

    /** A web poster's bytes, from the disk cache or else fetched once and kept there; null when it cannot be had. */
    private static byte[] webPoster(Context context, String uri) {
        java.io.File dir = new java.io.File(context.getCacheDir(), "posters");
        java.io.File file = new java.io.File(dir, sha1(uri));
        try {
            if (file.length() > 0) {
                file.setLastModified(System.currentTimeMillis());
                return java.nio.file.Files.readAllBytes(file.toPath());
            }
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(uri).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            try {
                if (c.getResponseCode() != 200) return null;
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[16 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        if (out.size() > MAX_POSTER_BYTES) return null;
                    }
                }
                byte[] bytes = out.toByteArray();
                if (dir.isDirectory() || dir.mkdirs()) {
                    java.io.File tmp = new java.io.File(dir, file.getName() + ".tmp");
                    java.nio.file.Files.write(tmp.toPath(), bytes);
                    if (!tmp.renameTo(file)) tmp.delete();
                    trim(dir);
                }
                return bytes;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "poster not fetched: " + e);
            return null;
        }
    }

    private static void trim(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files == null || files.length <= DISK_POSTERS) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - DISK_POSTERS; i++) files[i].delete();
    }

    private static String sha1(String s) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-1").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static Bitmap decodeBytes(byte[] bytes) {
        if (bytes == null) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= POSTER_PX) opts.inSampleSize *= 2;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "poster not decoded: " + e);
            return null;
        }
    }

    /** The intent the app published, or its own launch intent when that is missing or unparseable. */
    static Intent intentFor(Context context, Item item) {
        if (item.intentUri != null) {
            try {
                Intent i = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
                // Only the app that published the row: an intent_uri pointing elsewhere is not followed.
                if (i.getPackage() == null && i.getComponent() == null) i.setPackage(item.pkg);
                String target = i.getComponent() != null ? i.getComponent().getPackageName() : i.getPackage();
                if (item.pkg.equals(target)) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    return i;
                }
            } catch (URISyntaxException e) {
                Log.w(TAG, "bad intent_uri from " + item.pkg + ": " + e);
            }
        }
        PackageManager pm = context.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(item.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(item.pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static String str(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long lng(Cursor c, String column, long fallback) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? fallback : c.getLong(i);
    }
}

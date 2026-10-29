package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContinueWatchingTest {
    private static List<ContinueWatching.Item> rows(String... pkgs) {
        List<ContinueWatching.Item> all = new ArrayList<>();
        for (int i = 0; i < pkgs.length; i++) all.add(new ContinueWatching.Item(pkgs[i], "t" + i, "", null, -1f, null));
        return all;
    }

    private static ContinueWatching.Item channel(String pkg, String title, boolean audio) {
        return new ContinueWatching.Item(pkg, title, "", null, -1f, null, true, audio);
    }

    @Test public void theFocusedAppGetsOnlyItsOwnRowsInOrder() {
        List<ContinueWatching.Item> shown = ContinueWatching.forPackage(rows("yt", "prime", "yt", "prime"), "prime");
        assertEquals(2, shown.size());
        assertEquals("t1", shown.get(0).title);
        assertEquals("t3", shown.get(1).title);
    }

    @Test public void anAppWithNothingGetsAnEmptyRowNotAnotherAppsRows() {
        assertTrue(ContinueWatching.forPackage(rows("yt", "prime"), "netflix").isEmpty());
    }

    @Test public void anAppWithoutWatchNextRowsFallsBackToItsOwnChannel() {
        List<ContinueWatching.Item> all = rows("yt");
        all.add(channel("netflix", "Lupin", false));
        all.add(channel("yt", "a recommendation", false));
        List<ContinueWatching.Item> netflix = ContinueWatching.forPackage(all, "netflix");
        assertEquals(1, netflix.size());
        assertEquals("Lupin", netflix.get(0).title);
        // An app with Watch Next rows keeps them; its channel is not mixed in.
        List<ContinueWatching.Item> yt = ContinueWatching.forPackage(all, "yt");
        assertEquals(1, yt.size());
        assertEquals("t0", yt.get(0).title);
    }

    @Test public void musicRowsAreListeningAndVideoRowsAreNot() {
        assertTrue(ContinueWatching.listening(Arrays.asList(channel("spotify", "Arijit Singh", true))));
        assertFalse(ContinueWatching.listening(Arrays.asList(channel("netflix", "Lupin", false))));
        assertFalse(ContinueWatching.listening(new ArrayList<>()));
    }

    @Test public void rowsAreCappedAtMaxItems() {
        String[] many = new String[ContinueWatching.MAX_ITEMS + 5];
        Arrays.fill(many, "yt");
        assertEquals(ContinueWatching.MAX_ITEMS, ContinueWatching.forPackage(rows(many), "yt").size());
    }

    // Only on-TV posters and https ones; plain http and anything else are never fetched.
    @Test public void postersAreLocalOrHttpsOnly() {
        assertTrue(ContinueWatching.usablePoster("https://i.ytimg.com/vi/x/hqdefault.jpg"));
        assertTrue(ContinueWatching.usablePoster("content://org.jellyfin/poster/1"));
        assertFalse(ContinueWatching.usablePoster("http://example.com/p.jpg"));
        assertFalse(ContinueWatching.usablePoster("ftp://example.com/p.jpg"));
        assertFalse(ContinueWatching.usablePoster(null));
    }
}

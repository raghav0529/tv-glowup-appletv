package cv.sarthakgoel.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The mood deck's titles. Each mood is a filter on what streams in India on the user's apps (TMDB, whose watch data
 * comes from JustWatch), and each title needs a link that opens it in its app (JustWatch's Android TV links).
 * The pool is a JSON file refreshed about weekly, only when the deck is opened, never in the background. Links
 * already known are kept, so a refresh asks JustWatch only about new titles, slowly and up to a limit.
 */
final class DeckPool {
    private static final String TAG = "TvHome.Deck";
    private static final String FILE = "deck_pool.json";
    private static final int REFRESH_DAYS = 7, TITLES_PER_MOOD = 18, MAX_LOOKUPS = 40, SEEN_KEEP = 30;
    private static final String TMDB = "https://api.tmdb.org/3";
    private static final String PROVIDERS = "8|119|2336";   // Netflix, Prime Video, JioHotstar in India
    static final String ANYTHING = "Anything at all";

    static final class Title {
        String id, title, poster, backdrop, app, uri;
        int year;
    }

    static final class Mood {
        String name;
        int color;
        List<Title> titles = new ArrayList<>();
    }

    /** One discover query of a mood: movie or tv, and its filters as name=value pairs. */
    private static final class Query {
        final String kind;
        final String filters;

        Query(String kind, String filters) {
            this.kind = kind;
            this.filters = filters;
        }
    }

    private DeckPool() {
    }

    /** App name in the pool -> its package. */
    static String packageOf(String app) {
        if ("Netflix".equals(app)) return "com.netflix.ninja";
        if ("Prime Video".equals(app)) return "com.amazon.amazonvideo.livingroom";
        if ("JioHotstar".equals(app)) return "in.startv.hotstar";
        return null;
    }

    /** Only links to these places are ever opened, whatever the data says. */
    static boolean allowedUri(String uri) {
        return uri != null && (uri.startsWith("intent://www.netflix.com/")
                || uri.startsWith("intent://app.primevideo.com/")
                || uri.startsWith("intent://content/"));
    }

    private static File file(Context ctx) {
        File dir = Config.dir(ctx);
        return new File(dir != null ? dir : ctx.getFilesDir(), FILE);
    }

    /** The saved moods with "Anything at all" added last; empty if there is no pool yet. */
    static List<Mood> load(Context ctx) {
        List<Mood> moods = new ArrayList<>();
        try {
            JSONArray array = new JSONObject(read(file(ctx))).getJSONArray("moods");
            for (int i = 0; i < array.length(); i++) {
                JSONObject m = array.getJSONObject(i);
                Mood mood = new Mood();
                mood.name = m.getString("name");
                mood.color = Color.parseColor(m.getString("color"));
                JSONArray titles = m.getJSONArray("titles");
                for (int j = 0; j < titles.length(); j++) {
                    JSONObject t = titles.getJSONObject(j);
                    Title title = new Title();
                    title.id = t.getString("id");
                    title.title = t.getString("title");
                    title.year = t.optInt("year");
                    title.poster = t.getString("poster");
                    title.backdrop = t.getString("backdrop");
                    title.app = t.getString("app");
                    title.uri = t.getString("uri");
                    if (allowedUri(title.uri)) mood.titles.add(title);
                }
                if (!mood.titles.isEmpty()) moods.add(mood);
            }
        } catch (IOException | JSONException | IllegalArgumentException e) {
            Log.i(TAG, "no pool: " + e);
            return moods;
        }
        Mood all = new Mood();
        all.name = ANYTHING;
        all.color = 0xFFB8C0CC;
        Set<String> ids = new HashSet<>();
        for (Mood m : moods) for (Title t : m.titles) if (ids.add(t.id)) all.titles.add(t);
        if (!all.titles.isEmpty()) moods.add(all);
        return moods;
    }

    /** Whether the pool is missing or older than a week. */
    static boolean stale(Context ctx) {
        File f = file(ctx);
        return !f.isFile() || System.currentTimeMillis() - f.lastModified() > REFRESH_DAYS * 86_400_000L;
    }

    // ---- what has been picked lately, so the same title does not come straight back ---------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("deck", Context.MODE_PRIVATE);
    }

    static Set<String> seen(Context ctx) {
        String s = prefs(ctx).getString("seen", "");
        return s.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(s.split(",")));
    }

    static void markSeen(Context ctx, String id) {
        String s = prefs(ctx).getString("seen", "");
        List<String> list = s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split(",")));
        list.remove(id);
        list.add(id);
        while (list.size() > SEEN_KEEP) list.remove(0);
        prefs(ctx).edit().putString("seen", String.join(",", list)).apply();
    }

    /** This mood's titles shuffled, with the ones not opened lately first. */
    static List<Title> freshFirst(Context ctx, Mood mood, Random random) {
        Set<String> seen = seen(ctx);
        List<Title> fresh = new ArrayList<>(), opened = new ArrayList<>();
        for (Title t : mood.titles) (seen.contains(t.id) ? opened : fresh).add(t);
        Collections.shuffle(fresh, random);
        Collections.shuffle(opened, random);
        fresh.addAll(opened);
        return fresh;
    }

    // ---- weekly refresh ------------------------------------------------------------------------

    private static Map<String, List<Query>> moodQueries() {
        String since = LocalDate.now().minusDays(60).toString();
        Map<String, List<Query>> q = new LinkedHashMap<>();
        q.put("Family comfort|#F2B84B", Arrays.asList(
                new Query("tv", "with_original_language=hi&with_genres=35|10751&without_genres=16,10762&vote_count.gte=3"),
                new Query("movie", "with_original_language=hi&with_genres=35|10751&without_genres=16,10762&vote_count.gte=50")));
        q.put("Thrillers|#7C8CF8", Arrays.asList(
                new Query("tv", "with_genres=80|9648&vote_count.gte=50"),
                new Query("tv", "with_genres=80|9648&with_original_language=hi&vote_count.gte=3")));
        q.put("Bollywood night|#E5484D", Collections.singletonList(
                new Query("movie", "with_original_language=hi&vote_count.gte=100")));
        q.put("Big action|#FF8A3D", Collections.singletonList(
                new Query("movie", "with_genres=28&vote_count.gte=500")));
        q.put("English series|#3DBE8B", Collections.singletonList(
                new Query("tv", "with_original_language=en&without_genres=16,10762&vote_count.gte=500")));
        q.put("New this month|#C86BFF", Arrays.asList(
                new Query("movie", "primary_release_date.gte=" + since),
                new Query("tv", "first_air_date.gte=" + since)));
        return q;
    }

    /** Rebuilds the pool file. Blocking, slow on purpose: run it on a background thread. Any failure keeps the old file. */
    static void refresh(Context ctx, String tmdbKey) {
        if (tmdbKey == null || tmdbKey.isEmpty()) return;
        Map<String, JSONObject> known = new HashMap<>();
        Map<String, JSONArray> oldTitles = new HashMap<>();
        try {
            JSONArray old = new JSONObject(read(file(ctx))).getJSONArray("moods");
            for (int i = 0; i < old.length(); i++) {
                JSONArray ts = old.getJSONObject(i).getJSONArray("titles");
                oldTitles.put(old.getJSONObject(i).getString("name"), ts);
                for (int j = 0; j < ts.length(); j++) known.put(ts.getJSONObject(j).getString("id"), ts.getJSONObject(j));
            }
        } catch (IOException | JSONException ignored) {
        }
        Random random = new Random();
        int[] lookups = {0};
        try {
            JSONArray moods = new JSONArray();
            for (Map.Entry<String, List<Query>> e : moodQueries().entrySet()) {
                String[] nameColor = e.getKey().split("\\|");
                JSONArray titles = new JSONArray();
                Set<String> ids = new HashSet<>();
                boolean isNew = nameColor[0].startsWith("New");
                for (Query q : e.getValue()) {
                    // A random early page, so the pool changes from week to week; "new" is already short.
                    int page = isNew ? 1 : 1 + random.nextInt(3);
                    String url = TMDB + "/discover/" + q.kind + "?api_key=" + tmdbKey
                            + "&watch_region=IN&with_watch_providers=" + enc(PROVIDERS)
                            + "&with_watch_monetization_types=flatrate&include_adult=false&sort_by=popularity.desc&page=" + page
                            + "&" + q.filters.replace("|", "%7C");
                    JSONArray results = new JSONObject(get(url, null)).getJSONArray("results");
                    for (int i = 0; i < results.length() && titles.length() < TITLES_PER_MOOD; i++) {
                        JSONObject t = title(results.getJSONObject(i), q.kind, known, lookups);
                        if (t != null && ids.add(t.getString("id"))) titles.put(t);
                    }
                }
                // Not enough new links this time: top up from last week's titles of this mood.
                JSONArray before = oldTitles.get(nameColor[0]);
                for (int i = 0; before != null && i < before.length() && titles.length() < TITLES_PER_MOOD; i++) {
                    JSONObject t = before.getJSONObject(i);
                    if (ids.add(t.getString("id"))) titles.put(t);
                }
                moods.put(new JSONObject().put("name", nameColor[0]).put("color", nameColor[1]).put("titles", titles));
            }
            write(file(ctx), new JSONObject().put("fetched", LocalDate.now().toString()).put("moods", moods).toString());
            Log.i(TAG, "pool refreshed, " + lookups[0] + " link lookups");
        } catch (IOException | JSONException e) {
            Log.w(TAG, "refresh failed, keeping the old pool: " + e.getClass().getSimpleName());
        }
    }

    private static JSONObject title(JSONObject x, String kind, Map<String, JSONObject> known, int[] lookups)
            throws JSONException {
        String id = kind + x.getInt("id");
        JSONObject have = known.get(id);
        if (have != null) return have;
        String poster = x.optString("poster_path"), backdrop = x.optString("backdrop_path");
        if (poster.isEmpty() || "null".equals(poster) || backdrop.isEmpty() || "null".equals(backdrop)) return null;
        if (lookups[0] >= MAX_LOOKUPS) return null;
        lookups[0]++;
        String name = x.has("title") ? x.getString("title") : x.getString("name");
        String date = x.has("release_date") ? x.optString("release_date") : x.optString("first_air_date");
        int year = date.length() >= 4 && date.substring(0, 4).matches("\\d{4}") ? Integer.parseInt(date.substring(0, 4)) : 0;
        String[] link = link(name, year);
        if (link == null) return null;
        return new JSONObject().put("id", id).put("title", name).put("year", year).put("poster", poster)
                .put("backdrop", backdrop).put("app", link[0]).put("uri", link[1]);
    }

    /** {app, uri} from JustWatch for this title, or null. Waits a second first, to stay gentle on their service. */
    private static String[] link(String title, int year) {
        try {
            Thread.sleep(1000);
            String query = "query S($title:String!,$country:Country!){popularTitles(country:$country,first:3,filter:{searchQuery:$title})"
                    + "{edges{node{content(country:$country,language:\"en\"){title originalReleaseYear}"
                    + " offers(country:$country,platform:ANDROID_TV){monetizationType package{clearName} deeplinkURL(platform:ANDROID_TV)}}}}}";
            String body = new JSONObject().put("query", query)
                    .put("variables", new JSONObject().put("title", title).put("country", "IN")).toString();
            JSONArray edges = new JSONObject(get("https://apis.justwatch.com/graphql", body))
                    .getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
            for (int i = 0; i < edges.length(); i++) {
                JSONObject node = edges.getJSONObject(i).getJSONObject("node");
                JSONObject content = node.getJSONObject("content");
                int y = content.optInt("originalReleaseYear");
                if (!norm(content.getString("title")).equals(norm(title))) continue;
                if (year != 0 && y != 0 && Math.abs(y - year) > 1) continue;
                JSONArray offers = node.getJSONArray("offers");
                for (String[] app : new String[][]{{"netflix", "Netflix"}, {"amazon prime video", "Prime Video"},
                        {"jiohotstar", "JioHotstar"}}) {
                    for (int j = 0; j < offers.length(); j++) {
                        JSONObject o = offers.getJSONObject(j);
                        String type = o.optString("monetizationType");
                        String url = o.optString("deeplinkURL");
                        if (o.getJSONObject("package").getString("clearName").toLowerCase(Locale.ROOT).equals(app[0])
                                && !url.isEmpty() && !"null".equals(url)
                                && (type.equals("FLATRATE") || type.equals("FREE") || type.equals("ADS"))) {
                            return new String[]{app[1], pageLink(url)};
                        }
                    }
                }
            }
        } catch (IOException | JSONException | InterruptedException e) {
            Log.i(TAG, "no link: " + e.getClass().getSimpleName());
        }
        return null;
    }

    /** The title's page instead of playback: Netflix /title/, Prime Video /detail. */
    static String pageLink(String uri) {
        return uri.replace("www.netflix.com/watch/", "www.netflix.com/title/")
                .replaceAll("app\\.primevideo\\.com/watch\\?(gti=[^&#]+)[^#]*", "app.primevideo.com/detail?$1&territory=IN");
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ---- small helpers -------------------------------------------------------------------------

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    /** GET, or POST of JSON when body is set, with retries: api connections are sometimes reset here. */
    private static String get(String url, String body) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(20_000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0");
                if (body != null) {
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
                    return bytes.toString("UTF-8");
                }
            } catch (IOException e) {
                last = e;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("rename");
    }
}

package cv.sarthakgoel.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Don't have time to browse?": a deck of mood cards. Left skips a mood; Right or OK opens its titles as a row of
 * posters to scroll through, and OK opens the one in the middle. Its own screen, loaded when opened and closed as soon as it leaves the screen, so
 * Home carries none of it: posters decode in RGB_565 and live in a small cache that dies with the activity.
 */
public final class DeckActivity extends Activity {
    private static final String IMAGES = "https://image.tmdb.org/t/p/";
    private static final int CACHE_BYTES = 16 * 1024 * 1024;
    private static final long DISK_BYTES = 80L * 1024 * 1024;
    private static final int SLOTS = 11;
    private static final long MOVE_MS = 180, BACKDROP_DELAY_MS = 450;

    private enum State { DECK, BROWSE }

    private State state = State.DECK;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private boolean closed;
    private float density;

    private List<DeckPool.Mood> moods = new ArrayList<>();
    private int moodIndex;
    private FrameLayout root;
    private ImageView backdrop;
    private View glow;
    private FrameLayout deck;
    private final CardViews[] cards = new CardViews[3];
    private TextView nope, yes, header;

    private FrameLayout reel;
    private final ImageView[] slots = new ImageView[SLOTS];
    private final int[] slotIndex = new int[SLOTS];
    private List<DeckPool.Title> reelTitles = new ArrayList<>();
    private ValueAnimator move;
    private int focus;
    private float reelPosition;
    private TextView pickedName, pickedInfo, hint;
    private String backdropPath;

    private static final class CardViews {
        FrameLayout card;
        ImageView[] posters = new ImageView[3];
        TextView name, count;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        Config cfg = Config.load(this);
        buildViews();
        setContentView(root);
        moods = new ArrayList<>(DeckPool.load(this));
        // A different order every time; "Anything at all" stays last.
        DeckPool.Mood last = null;
        if (!moods.isEmpty() && DeckPool.ANYTHING.equals(moods.get(moods.size() - 1).name)) last = moods.remove(moods.size() - 1);
        Collections.shuffle(moods, random);
        if (last != null) moods.add(last);
        for (DeckPool.Mood m : moods) m.titles = pickInstalled(m.titles);
        for (Iterator<DeckPool.Mood> it = moods.iterator(); it.hasNext(); ) if (it.next().titles.isEmpty()) it.remove();
        for (DeckPool.Mood m : moods) Collections.shuffle(m.titles, random);
        if (moods.isEmpty()) {
            header.setText(R.string.deck_empty);
            deck.setVisibility(View.GONE);
        } else {
            bindCards();
        }
        trimDiskCache();
        if (DeckPool.stale(this)) {
            final String key = cfg.deckTmdbKey;
            final android.content.Context app = getApplicationContext();
            Thread t = new Thread(() -> DeckPool.refresh(app, key), "deck-refresh");
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
    }

    private List<DeckPool.Title> pickInstalled(List<DeckPool.Title> titles) {
        List<DeckPool.Title> out = new ArrayList<>();
        for (DeckPool.Title t : titles) {
            String pkg = DeckPool.packageOf(t.app);
            if (pkg != null && getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null) out.add(t);
        }
        return out;
    }

    // ---- views ---------------------------------------------------------------------------------

    private FrameLayout.LayoutParams lp(int w, int h, int gravity) {
        return new FrameLayout.LayoutParams(w, h, gravity);
    }

    private TextView text(float sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    private void buildViews() {
        Typeface regular = getResources().getFont(R.font.outfit_regular);
        Typeface medium = getResources().getFont(R.font.outfit_medium);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050507);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ColorMatrix grey = new ColorMatrix();
        grey.setSaturation(0f);
        backdrop.setColorFilter(new ColorMatrixColorFilter(grey));
        backdrop.setAlpha(0f);
        root.addView(backdrop, lp(-1, -1, Gravity.NO_GRAVITY));
        View shade = new View(this);
        shade.setBackgroundColor(0x99050507);
        root.addView(shade, lp(-1, -1, Gravity.NO_GRAVITY));
        glow = new View(this);
        root.addView(glow, lp(-1, -1, Gravity.NO_GRAVITY));

        header = text(17, 0x8CFFFFFF, regular);
        FrameLayout.LayoutParams hp = lp(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = dp(52);
        root.addView(header, hp);
        header.setText(R.string.deck_title);

        deck = new FrameLayout(this);
        for (int i = 2; i >= 0; i--) {
            cards[i] = card(medium, regular);
            deck.addView(cards[i].card, lp(dp(200), dp(268), Gravity.CENTER));
        }
        FrameLayout.LayoutParams dpm = lp(dp(330), dp(340), Gravity.CENTER);
        dpm.topMargin = dp(16);
        root.addView(deck, dpm);

        nope = text(14, 0x8CFFFFFF, medium);
        nope.setText(R.string.deck_nope);
        FrameLayout.LayoutParams np = lp(-2, -2, Gravity.CENTER);
        np.rightMargin = dp(290);
        np.topMargin = dp(16);
        root.addView(nope, np);
        yes = text(14, 0x8CFFFFFF, medium);
        yes.setText(R.string.deck_yes);
        FrameLayout.LayoutParams yp = lp(-2, -2, Gravity.CENTER);
        yp.leftMargin = dp(290);
        yp.topMargin = dp(16);
        root.addView(yes, yp);

        reel = new FrameLayout(this);
        reel.setVisibility(View.GONE);
        for (int i = 0; i < SLOTS; i++) {
            ImageView slot = new ImageView(this);
            slot.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1A1A1E);
            bg.setCornerRadius(dp(10));
            slot.setBackground(bg);
            slot.setClipToOutline(true);
            slots[i] = slot;
            reel.addView(slot, lp(dp(160), dp(240), Gravity.CENTER));
        }
        FrameLayout.LayoutParams rp = lp(-1, dp(270), Gravity.CENTER);
        rp.bottomMargin = dp(10);
        root.addView(reel, rp);

        pickedName = text(24, 0xFFFFFFFF, medium);
        FrameLayout.LayoutParams pn = lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pn.bottomMargin = dp(82);
        pickedName.setVisibility(View.GONE);
        root.addView(pickedName, pn);
        pickedInfo = text(14, 0xB3FFFFFF, regular);
        FrameLayout.LayoutParams cp = lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cp.bottomMargin = dp(60);
        pickedInfo.setVisibility(View.GONE);
        root.addView(pickedInfo, cp);
        hint = text(12, 0x80FFFFFF, regular);
        hint.setText(R.string.deck_browse_hint);
        FrameLayout.LayoutParams hi = lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hi.bottomMargin = dp(36);
        hint.setVisibility(View.GONE);
        root.addView(hint, hi);

        // TMDB's terms ask for this notice; the data on where titles stream comes from JustWatch.
        TextView credit = text(10, 0x59FFFFFF, regular);
        credit.setText(R.string.deck_credit);
        FrameLayout.LayoutParams cr = lp(-2, -2, Gravity.BOTTOM | Gravity.END);
        cr.rightMargin = dp(20);
        cr.bottomMargin = dp(10);
        root.addView(credit, cr);
    }

    private CardViews card(Typeface medium, Typeface regular) {
        CardViews c = new CardViews();
        c.card = new FrameLayout(this);
        int[] x = {-45, 45, 0};
        int[] tilt = {-9, 9, 0};
        int[] y = {6, 6, 0};
        for (int i = 0; i < 3; i++) {
            ImageView p = new ImageView(this);
            p.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1A1A1E);
            bg.setCornerRadius(dp(8));
            p.setBackground(bg);
            p.setClipToOutline(true);
            p.setTranslationX(dp(x[i]));
            p.setTranslationY(dp(y[i]));
            p.setRotation(tilt[i]);
            FrameLayout.LayoutParams lp = lp(dp(84), dp(126), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(32);
            c.card.addView(p, lp);
            c.posters[i] = p;
        }
        c.name = text(21, 0xFFF4F4F6, medium);
        FrameLayout.LayoutParams n = lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        n.bottomMargin = dp(46);
        c.card.addView(c.name, n);
        c.count = text(13, 0xB3FFFFFF, regular);
        FrameLayout.LayoutParams t = lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        t.bottomMargin = dp(22);
        c.card.addView(c.count, t);
        return c;
    }

    private DeckPool.Mood mood(int offset) {
        return moods.get((moodIndex + offset) % moods.size());
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * t + ((b >> 16) & 255) * (1 - t));
        int g = Math.round(((a >> 8) & 255) * t + ((b >> 8) & 255) * (1 - t));
        int bl = Math.round((a & 255) * t + (b & 255) * (1 - t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Top card with its posters and text; the two behind it are colour only, as in the deck's design. */
    private void bindCards() {
        int[] tx = {0, -14, 32};
        int[] ty = {0, 8, 16};
        int[] rot = {0, -5, 14};
        float[] sc = {1f, 0.95f, 0.90f};
        float[] al = {1f, 0.78f, 0.55f};
        for (int i = 0; i < 3; i++) {
            CardViews c = cards[i];
            DeckPool.Mood m = mood(i);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{blend(m.color, 0xFF141416, 0.30f), 0xFF0E0E10});
            bg.setCornerRadius(dp(18));
            bg.setStroke(dp(1.2f), (m.color & 0x00FFFFFF) | 0x80000000);
            c.card.setBackground(bg);
            c.card.setTranslationX(dp(tx[i]));
            c.card.setTranslationY(dp(ty[i]));
            c.card.setRotation(rot[i]);
            c.card.setScaleX(sc[i]);
            c.card.setScaleY(sc[i]);
            c.card.setAlpha(al[i]);
            boolean top = i == 0;
            for (int p = 0; p < 3; p++) {
                c.posters[p].setVisibility(top ? View.VISIBLE : View.GONE);
                if (top) {
                    c.posters[p].setImageDrawable(null);
                    show(c.posters[p], m.titles.get(p % m.titles.size()).poster, "w185");
                }
            }
            c.name.setVisibility(top ? View.VISIBLE : View.GONE);
            c.count.setVisibility(top ? View.VISIBLE : View.GONE);
            if (top) {
                c.name.setText(m.name);
                c.count.setText(getResources().getQuantityString(R.plurals.deck_titles, m.titles.size(), m.titles.size()));
            }
        }
        DeckPool.Mood top = mood(0);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(260));   // fades out inside the screen height, so it has no visible edge
        g.setColors(new int[]{(top.color & 0x00FFFFFF) | 0x3A000000, 0x00000000});
        glow.setBackground(g);
        setBackdrop(top.titles.get(0).backdrop);
    }

    private void setBackdrop(String path) {
        if (path.equals(backdropPath)) return;
        backdropPath = path;
        load(path, "w780", bitmap -> {
            if (!path.equals(backdropPath)) return;
            backdrop.animate().cancel();
            backdrop.setImageBitmap(bitmap);
            backdrop.setAlpha(0f);
            backdrop.animate().alpha(0.32f).setDuration(340).start();
        });
    }

    private void show(ImageView view, String path, String size) {
        view.setTag(path);
        load(path, size, bitmap -> {
            if (path.equals(view.getTag())) view.setImageBitmap(bitmap);
        });
    }

    // ---- remote --------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE;
        if (volume) return super.onKeyDown(keyCode, event);
        // Holding Left or Right keeps scrolling the row; nothing else repeats.
        boolean horizontal = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (event.getRepeatCount() > 0 && !(state == State.BROWSE && horizontal)) return true;
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
        boolean left = keyCode == KeyEvent.KEYCODE_DPAD_LEFT, right = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean back = keyCode == KeyEvent.KEYCODE_BACK;
        if (moods.isEmpty()) return super.onKeyDown(keyCode, event);
        if (state == State.DECK) {
            if (left) nope();
            else if (right || ok) startBrowse();
            else return super.onKeyDown(keyCode, event);
            return true;
        }
        if (left) moveFocus(-1);
        else if (right) moveFocus(1);
        else if (ok) open(reelTitles.get(focus));
        else if (back) backToDeck();
        else return super.onKeyDown(keyCode, event);
        return true;
    }

    private void nope() {
        final View top = cards[0].card;
        top.animate().cancel();
        top.animate().translationX(-deck.getWidth() * 0.9f).rotation(-16).alpha(0f).setDuration(280)
                .setInterpolator(new DecelerateInterpolator(1.4f)).withEndAction(() -> {
                    moodIndex = (moodIndex + 1) % moods.size();
                    bindCards();
                }).start();
    }

    // ---- the mood's titles --------------------------------------------------------------------

    private void setDeckVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        deck.setVisibility(v);
        nope.setVisibility(v);
        yes.setVisibility(v);
    }

    private void setBrowseVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        reel.setVisibility(v);
        pickedName.setVisibility(v);
        pickedInfo.setVisibility(v);
        hint.setVisibility(v);
    }

    private void startBrowse() {
        state = State.BROWSE;
        setDeckVisible(false);
        DeckPool.Mood m = mood(0);
        header.setText(m.name);
        reelTitles = DeckPool.freshFirst(this, m, random);
        focus = 0;
        reelPosition = 0f;
        for (int i = 0; i < SLOTS; i++) slotIndex[i] = Integer.MIN_VALUE;
        setBrowseVisible(true);
        placeReel(0f);
        showFocused();
    }

    private void backToDeck() {
        if (move != null) move.cancel();
        handler.removeCallbacks(backdropForFocus);
        state = State.DECK;
        setBrowseVisible(false);
        header.setText(R.string.deck_title);
        setDeckVisible(true);
        bindCards();
    }

    private void moveFocus(int by) {
        int n = reelTitles.size();
        focus = (focus + by + n) % n;
        final float from = reelPosition, to = nearest(from, focus, n);
        if (move != null) move.cancel();
        move = ValueAnimator.ofFloat(from, to);
        move.setDuration(MOVE_MS);
        move.setInterpolator(new DecelerateInterpolator(1.6f));
        move.addUpdateListener(a -> {
            reelPosition = (float) a.getAnimatedValue();
            placeReel(reelPosition);
        });
        move.start();
        showFocused();
    }

    /** The reel position showing this title that is closest to where the reel is now, so wrapping scrolls the short way. */
    private static float nearest(float from, int index, int n) {
        return index + n * Math.round((from - index) / n);
    }

    private void showFocused() {
        DeckPool.Title t = reelTitles.get(focus);
        pickedName.setText(t.title);
        pickedInfo.setText(t.year > 0 ? t.year + "  \u00B7  " + t.app : t.app);
        // Wait until the remote stops moving: a backdrop per key press would make scrolling heavy.
        handler.removeCallbacks(backdropForFocus);
        handler.postDelayed(backdropForFocus, BACKDROP_DELAY_MS);
    }

    private final Runnable backdropForFocus = () -> {
        if (state == State.BROWSE) setBackdrop(reelTitles.get(focus).backdrop);
    };

    /** Lays the slots out around reel position p: the middle poster big and bright, the far ones small and dim. */
    private void placeReel(float p) {
        int n = reelTitles.size();
        int centre = Math.round(p);
        float spacing = dp(132);
        for (int i = 0; i < SLOTS; i++) {
            int index = centre + i - SLOTS / 2;
            float distance = Math.abs(index - p);
            ImageView slot = slots[i];
            slot.setTranslationX((index - p) * spacing);
            float scale = Math.max(0.58f, 1f - distance * 0.14f);
            slot.setScaleX(scale);
            slot.setScaleY(scale);
            slot.setAlpha(Math.max(0.12f, 1f - distance * 0.22f));
            slot.setTranslationZ(-distance);
            if (slotIndex[i] != index) {
                slotIndex[i] = index;
                DeckPool.Title t = reelTitles.get(((index % n) + n) % n);
                Bitmap b = memory.get(t.poster + "w342");
                slot.setTag(t.poster);
                if (b != null) slot.setImageBitmap(b);
                else {
                    slot.setImageDrawable(null);
                    show(slot, t.poster, "w342");
                }
            }
        }
    }

    /**
     * Opens the title in its app. A running Netflix or Prime Video ignores a link, so a background copy is ended first,
     * and the memory this screen holds goes before the app needs it.
     */
    private void open(DeckPool.Title t) {
        String pkg = DeckPool.packageOf(t.app);
        try {
            if (pkg == null || !DeckPool.allowedUri(t.uri)) throw new URISyntaxException(t.uri, "not allowed");
            Intent intent = Intent.parseUri(t.uri, Intent.URI_INTENT_SCHEME);
            intent.setPackage(pkg);
            intent.setComponent(null);
            intent.setSelector(null);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityManager am = getSystemService(ActivityManager.class);
            if (am != null) am.killBackgroundProcesses(pkg);
            DeckPool.markSeen(this, t.id);
            startActivity(intent);
        } catch (URISyntaxException | ActivityNotFoundException | SecurityException e) {
            Intent app = pkg == null ? null : getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (app != null) startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }

    // ---- images --------------------------------------------------------------------------------

    private interface Loaded {
        void on(Bitmap bitmap);
    }

    private void load(String path, String size, Loaded done) {
        String key = path + size;
        Bitmap hit = memory.get(key);
        if (hit != null) {
            done.on(hit);
            return;
        }
        final File cacheDir = new File(getCacheDir(), "deck");
        images.execute(() -> {
            Bitmap b = fetch(cacheDir, path, size);
            if (b == null) return;
            handler.post(() -> {
                if (closed) return;
                memory.put(key, b);
                done.on(b);
            });
        });
    }

    private static Bitmap fetch(File dir, String path, String size) {
        File file = new File(dir, size + path.replace('/', '_'));
        try {
            if (!file.isFile() || file.length() == 0) {
                if (!dir.isDirectory() && !dir.mkdirs()) return null;
                HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + size + path).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(15_000);
                File tmp = new File(file.getPath() + ".tmp");
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                } finally {
                    c.disconnect();
                }
                if (!tmp.renameTo(file)) return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), o);
        } catch (IOException e) {
            return null;
        }
    }

    /** Keeps the poster folder from growing without end: the oldest files go first. */
    private void trimDiskCache() {
        final File dir = new File(getCacheDir(), "deck");
        images.execute(() -> {
            File[] files = dir.listFiles();
            if (files == null) return;
            long total = 0;
            for (File f : files) total += f.length();
            if (total <= DISK_BYTES) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : files) {
                if (total <= DISK_BYTES) break;
                total -= f.length();
                f.delete();
            }
        });
    }

    // ---- leaving -------------------------------------------------------------------------------

    /** Off the screen means finished: nothing of the deck stays in memory behind Home or an app. */
    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        closed = true;
        if (move != null) move.cancel();
        handler.removeCallbacksAndMessages(null);
        images.shutdownNow();
        memory.evictAll();
        super.onDestroy();
    }
}

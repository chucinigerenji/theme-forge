package com.dsh.themeforge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 从主题库里已保存的主题中取素材，替换当前正在制作的某个界面。
 *
 * 三个要点：
 *  1) 来源主题用一排「药丸按钮」直接显示，点一下就换，不用找下拉框；
 *     「全部主题」会把库里所有主题的素材合起来，一个界面可以从任何主题里挑图。
 *  2) 扫描是大工程（主题包几百 MB），用「代号 gen」做版本控制：
 *     每次切换来源/分类/搜索都会让旧扫描的结果作废，不会再出现
 *     「切了主题但列表还是上一个主题」的情况。
 *  3) 每个主题一个 TemplatePreview（记住哪些槽位真的有图），
 *     结果按「主题+分类+搜索词」缓存，来回切换是秒开的。
 */
public class ThemePickActivity extends Activity {

    public static final String EXTRA_SLOT = "slot";
    public static final String EXTRA_THEME = "theme";

    private static final int PAGE = 60;
    /** 「全部主题」的虚拟 id。 */
    private static final String ALL = "*";

    private Store store;
    private SlotData.Slot target;
    private List<ThemeLib.Item> themes = new ArrayList<>();
    private final Map<String, TemplatePreview> previews = new LinkedHashMap<>();
    private final Map<String, List<SlotData.Slot>> resultCache = new LinkedHashMap<>();

    /** 当前来源：主题库里的 id，或 ALL 表示全部主题。 */
    private String themeId = ALL;

    private LinearLayout box;
    private LinearLayout themeChips;
    private Spinner spFeature;
    private EditText etSearch;
    private TextView tvTarget;
    private TextView tvStatus;
    private Button btnMore;
    private Button btnUse;
    private View headBar;

    private SlotData.Feature feat;
    private String query = "";
    private List<SlotData.Slot> hits = new ArrayList<>();
    private final Map<String, View> rows = new LinkedHashMap<>();
    private int shown = PAGE;
    private SlotData.Slot selected;
    private View selectedView;

    /** 每次刷新 +1；后台任务只在自己这一代仍是最新时才回填结果。 */
    private int gen = 0;
    private boolean scanning;
    private long scanStart;
    private Runnable ticker;
    private boolean destroyed;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        T.init(this);
        setContentView(R.layout.activity_themepick);
        store = new Store(this);

        try {
            SlotData.load(this);
        } catch (Exception e) {
            toast("数据载入失败：" + e);
            finish();
            return;
        }

        target = SlotData.BY_KEY.get(getIntent().getStringExtra(EXTRA_SLOT));
        if (target == null) {
            toast("没有指定要替换的界面");
            finish();
            return;
        }

        box = findViewById(R.id.pickBox);
        themeChips = findViewById(R.id.themeChips);
        spFeature = findViewById(R.id.spFeature);
        etSearch = findViewById(R.id.etPickSearch);
        tvTarget = findViewById(R.id.tvPickTarget);
        tvStatus = findViewById(R.id.tvPickStatus);
        btnMore = findViewById(R.id.btnPickMore);
        btnUse = findViewById(R.id.btnPickUse);
        headBar = findViewById(R.id.headBar);

        T.apply(this, headBar, btnUse);
        btnUse.setTextColor(0xFFFFFFFF);
        btnUse.setEnabled(false);
        tvTarget.setText("用于「" + target.label + "」 · 输出 " + target.sizeText()
                + (target.nine ? "  .9" : ""));

        themes = ThemeLib.list(this);
        if (themes.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("主题库是空的")
                    .setMessage("先去「主题库」导入一个或多个主题文件，才能从里面取素材。")
                    .setPositiveButton("去导入", (d, w) -> {
                        startActivity(new Intent(ThemePickActivity.this, LibraryActivity.class));
                        finish();
                    })
                    .setNegativeButton("返回", (d, w) -> finish())
                    .show();
            return;
        }
        // 一次性建好所有主题的索引器，之后只读，线程安全。
        // 用较小的缩略图缓存上限：这里是「每个主题一份实例」，
        // 主题库里存了十几个主题时，400 条/份会直接吃掉几百 MB。
        for (ThemeLib.Item it : themes) {
            previews.put(it.id, new TemplatePreview(it.file, 160));
        }

        // 默认来源＝全部主题：制作时可以直接从任何一个已保存的主题里挑素材，
        // 想只看某一个主题就点上面对应的药丸按钮（当前基础模板那个带 ★）。
        themeId = ALL;
        String want = getIntent().getStringExtra(EXTRA_THEME);
        if (want != null && previews.containsKey(want)) {
            // 只用来把当前模板主题排到最前面，方便一眼看到
            for (int i = 0; i < themes.size(); i++) {
                if (themes.get(i).id.equals(want)) {
                    ThemeLib.Item hit = themes.remove(i);
                    themes.add(0, hit);
                    break;
                }
            }
        }

        // 默认停在目标界面所属的分类，改起来最直观
        int fi = 0;
        for (int i = 0; i < SlotData.FEATURES.size(); i++) {
            if (SlotData.FEATURES.get(i).id.equals(target.featureId)) {
                fi = i;
                break;
            }
        }
        feat = SlotData.FEATURES.get(fi);
        List<String> fnames = new ArrayList<>();
        for (SlotData.Feature f : SlotData.FEATURES) fnames.add(f.name);
        ArrayAdapter<String> fad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, fnames);
        fad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFeature.setAdapter(fad);
        spFeature.setSelection(fi);
        spFeature.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= SlotData.FEATURES.size()) return;
                if (SlotData.FEATURES.get(pos) == feat) return;
                feat = SlotData.FEATURES.get(pos);
                refresh();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                String q = e.toString().trim();
                if (q.equals(query)) return;
                query = q;
                refresh();
            }
        });

        btnMore.setOnClickListener(v -> {
            shown += PAGE;
            renderList();
        });
        btnUse.setOnClickListener(v -> use());

        buildThemeChips();
        refresh();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopTicker();
        super.onDestroy();
    }

    // ==================== 来源主题：药丸按钮 ====================

    private void buildThemeChips() {
        themeChips.removeAllViews();
        String cur = store.getTemplatePath();
        ThemeLib.Item curItem = null;
        for (ThemeLib.Item it : themes) {
            if (it.file.getAbsolutePath().equals(cur)) {
                curItem = it;
                break;
            }
        }
        addChip("全部主题（" + themes.size() + "个）", ALL);
        for (ThemeLib.Item it : themes) {
            addChip((it == curItem ? "★ " : "") + it.name, it.id);
        }
    }

    private void addChip(String label, final String id) {
        final boolean on = (id == null ? themeId == null : id.equals(themeId));
        Button chip = new Button(this);
        chip.setText(label);
        chip.setTextSize(13);
        chip.setSingleLine(true);
        chip.setAllCaps(false);
        chip.setPadding((int) dp(16), 0, (int) dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) dp(46));
        lp.rightMargin = (int) dp(8);
        chip.setLayoutParams(lp);
        chip.setBackground(T.rr(on ? T.withAlpha(T.accent(), 0x26) : 0xFFEFF0F3, dp(23)));
        chip.setTextColor(on ? T.accent() : 0xFF8A8A8E);
        chip.setOnClickListener(v -> {
            if (on) return;
            themeId = id;
            buildThemeChips();
            refresh();
        });
        themeChips.addView(chip);
    }

    private String themeLabel() {
        if (ALL.equals(themeId)) return "全部主题";
        for (ThemeLib.Item it : themes) if (it.id.equals(themeId)) return it.name;
        return "全部主题";
    }

    private List<ThemeLib.Item> sourcesInOrder() {
        List<ThemeLib.Item> out = new ArrayList<>();
        if (ALL.equals(themeId)) {
            out.addAll(themes);
        } else {
            for (ThemeLib.Item it : themes) if (it.id.equals(themeId)) out.add(it);
        }
        return out;
    }

    // ==================== 扫描 + 列表 ====================

    private List<SlotData.Slot> candidates() {
        List<SlotData.Slot> cand = new ArrayList<>();
        if (query.length() > 0) {
            // 有搜索词就跨全部分类找
            for (SlotData.Feature ff : SlotData.FEATURES) {
                for (SlotData.Slot s : ff.slots) {
                    if (match(s, query)) cand.add(s);
                }
            }
        } else if (feat != null) {
            cand.addAll(feat.slots);
        }
        return cand;
    }

    private static boolean match(SlotData.Slot s, String q) {
        String lq = q.toLowerCase();
        return s.label.toLowerCase().contains(lq)
                || s.path.toLowerCase().contains(lq)
                || s.module.toLowerCase().contains(lq);
    }

    private String cacheKey() {
        return themeId + "|" + (feat == null ? "" : feat.id) + "|" + query;
    }

    private void refresh() {
        final int myGen = ++gen;
        selected = null;
        selectedView = null;
        rows.clear();
        box.removeAllViews();
        btnMore.setVisibility(View.GONE);
        btnUse.setEnabled(false);

        final List<SlotData.Slot> cand = candidates();
        if (cand.isEmpty()) {
            stopTicker();
            tvStatus.setText(query.length() > 0
                    ? "没有匹配「" + query + "」的槽位"
                    : "这个分类里没有槽位");
            return;
        }

        final String key = cacheKey();
        List<SlotData.Slot> cached = resultCache.get(key);
        if (cached != null) {
            stopTicker();
            hits = cached;
            shown = PAGE;
            renderList();
            return;
        }

        hits = new ArrayList<>();
        startTicker();
        final List<ThemeLib.Item> srcs = sourcesInOrder();
        pool.execute(() -> {
            Set<SlotData.Slot> hit = new LinkedHashSet<>();
            for (ThemeLib.Item it : srcs) {
                TemplatePreview p = previews.get(it.id);
                if (p == null) continue;
                try {
                    p.index(cand);
                    for (SlotData.Slot s : cand) if (p.exists(s)) hit.add(s);
                } catch (Throwable ignored) {
                    // 单个主题坏了不影响别的主题
                }
            }
            final List<SlotData.Slot> fhit = new ArrayList<>(hit);
            ui.post(() -> {
                if (destroyed || myGen != gen) return;   // 页面已关 / 已被更新的请求取代
                stopTicker();
                resultCache.put(key, fhit);
                hits = fhit;
                shown = PAGE;
                renderList();
            });
        });
    }

    private void startTicker() {
        stopTicker();
        scanning = true;
        scanStart = SystemClock.uptimeMillis();
        ticker = new Runnable() {
            @Override
            public void run() {
                if (!scanning) return;
                long sec = (SystemClock.uptimeMillis() - scanStart) / 1000;
                tvStatus.setText("正在扫描「" + themeLabel() + "」里的素材… " + sec + "s\n"
                        + "主题包很大时第一次会比较慢，扫过一次就会记住，再切换是秒开的");
                ui.postDelayed(this, 500);
            }
        };
        ui.post(ticker);
    }

    private void stopTicker() {
        scanning = false;
        if (ticker != null) {
            ui.removeCallbacks(ticker);
            ticker = null;
        }
    }

    private void renderList() {
        box.removeAllViews();
        rows.clear();
        selectedView = null;
        if (hits.isEmpty()) {
            tvStatus.setText(query.length() > 0
                    ? "「" + themeLabel() + "」里没有匹配「" + query + "」的素材"
                    : "「" + themeLabel() + "」的「" + feat.name + "」分类里没有可选素材，"
                    + "换个分类、换个主题，或用搜索找找");
            btnMore.setVisibility(View.GONE);
            return;
        }
        int limit = Math.min(hits.size(), Math.max(shown, 1));
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < limit; i++) {
            SlotData.Slot s = hits.get(i);
            View row = inf.inflate(R.layout.item_pick, box, false);
            ((TextView) row.findViewById(R.id.tvPickName)).setText(s.label);
            ((TextView) row.findViewById(R.id.tvPickPath)).setText(s.path);
            ((TextView) row.findViewById(R.id.tvPickBadge)).setText(s.w + "×" + s.h);
            row.setOnClickListener(v -> select(s, v));
            box.addView(row);
            rows.put(s.id(), row);
        }
        if (limit < hits.size()) {
            btnMore.setVisibility(View.VISIBLE);
            btnMore.setText("显示更多（" + limit + " / " + hits.size() + "）");
        } else {
            btnMore.setVisibility(View.GONE);
        }
        tvStatus.setText("「" + themeLabel() + "」找到 " + hits.size() + " 个素材"
                + (query.length() > 0 ? "（全部分类）" : "（" + feat.name + "）")
                + "\n点一个素材，再按右下角「使用这张图」");
        loadThumbs(hits.subList(0, limit));
    }

    private void select(SlotData.Slot s, View row) {
        if (selectedView != null) {
            selectedView.setBackgroundResource(R.drawable.card_bg);
        }
        selected = s;
        selectedView = row;
        row.setBackgroundColor(T.withAlpha(T.accent(), 0x30));
        btnUse.setEnabled(true);
        tvStatus.setText("已选：" + s.label + "\n" + s.path
                + "\n按「使用这张图」把它用到「" + target.label + "」");
    }

    private void loadThumbs(final List<SlotData.Slot> page) {
        pool.execute(() -> {
            Map<TemplatePreview, List<SlotData.Slot>> byPrev = new LinkedHashMap<>();
            for (SlotData.Slot s : page) {
                TemplatePreview p = owner(s);
                if (p == null) continue;
                List<SlotData.Slot> l = byPrev.get(p);
                if (l == null) {
                    l = new ArrayList<>();
                    byPrev.put(p, l);
                }
                l.add(s);
            }
            for (Map.Entry<TemplatePreview, List<SlotData.Slot>> e : byPrev.entrySet()) {
                try {
                    e.getKey().prepare(ThemePickActivity.this, e.getValue(), 128);
                } catch (Throwable ignored) {
                }
            }
            ui.post(() -> {
                if (destroyed) return;
                for (SlotData.Slot s : page) {
                    View row = rows.get(s.id());
                    if (row == null) continue;
                    TemplatePreview p = owner(s);
                    if (p == null) continue;
                    Bitmap bm = p.cached(s);
                    if (bm == null) continue;
                    ImageView iv = row.findViewById(R.id.ivPickThumb);
                    if (iv != null) iv.setImageBitmap(bm);
                }
            });
        });
    }

    /** 这张图是哪个主题里的（全部主题模式下可能有多个来源）。 */
    private TemplatePreview owner(SlotData.Slot s) {
        for (ThemeLib.Item it : themes) {
            TemplatePreview p = previews.get(it.id);
            if (p != null && p.exists(s)) return p;
        }
        return null;
    }

    // ==================== 取图 ====================

    private void use() {
        final SlotData.Slot src = selected;
        if (src == null) {
            toast("先点一个素材");
            return;
        }
        btnUse.setEnabled(false);
        tvStatus.setText("正在取出素材…");
        final List<ThemeLib.Item> srcs = sourcesInOrder();
        pool.execute(() -> {
            try {
                byte[] raw = null;
                String from = null;
                for (ThemeLib.Item it : srcs) {
                    TemplatePreview p = previews.get(it.id);
                    if (p == null || !p.exists(src)) continue;
                    byte[] d = p.loadRaw(src);
                    if (d != null && d.length > 0) {
                        raw = d;
                        from = it.name;
                        break;
                    }
                }
                if (raw == null) {
                    throw new Exception("没读到这张图（换个来源主题试试）");
                }
                boolean srcNine = src.path.toLowerCase().endsWith(".9.png");
                if (srcNine || target.nine) {
                    int maxDim = Math.max(512, Math.min(4096, Math.max(target.outW(), target.outH())));
                    Bitmap bm = Img.decodeBytes(raw, maxDim);
                    if (bm == null) throw new Exception("这张图无法解码");
                    bm = Img.stripNinePatch(bm, srcNine);
                    try {
                        store.put(target, bm);
                    } finally {
                        bm.recycle();
                    }
                } else {
                    // 原样搬运，不重编码
                    store.putBytes(target, raw);
                }
                final String fname = from;
                ui.post(() -> {
                    if (destroyed) return;
                    toast("已用「" + fname + "」里的素材替换「" + target.label + "」");
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    if (destroyed) return;
                    btnUse.setEnabled(true);
                    tvStatus.setText("取素材失败：" + t);
                });
            }
        });
    }

    // ==================== 工具 ====================

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

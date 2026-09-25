package com.dsh.themeforge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    private static final int REQ_SLOT = 1;
    private static final int REQ_BULK = 2;
    private static final int REQ_TEMPLATE = 3;
    private static final int REQ_ZIP = 4;
    private static final int REQ_EDIT = 5;
    private static final int REQ_SETTINGS = 6;
    private static final int REQ_PICKTHEME = 7;
    private static final int REQ_LIB = 8;

    private static final int INITIAL_ROWS = 8;
    private static final int STEP_ROWS = 30;
    private static final int MAX_BULK = 150;

    private Store store;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout container;
    private TextView tvStatus;
    private TextView tvSub;
    private TextView tvTemplate;
    private EditText etTitle;
    private EditText etAuthor;
    private Button btnBuild;
    private Button btnOpen;
    private Button btnSettings;
    private View infoBody;
    private TextView tvInfoArrow;
    private View topBar;

    private final Map<String, Card> cards = new LinkedHashMap<>();
    private final Map<String, View> slotRows = new LinkedHashMap<>();
    private final Map<String, String> rowCard = new LinkedHashMap<>();

    private SlotData.Slot pendingSlot;
    private SlotData.Slot pendingEdit;
    private List<SlotData.Slot> pendingBulk;
    private SlotData.Feature pendingZipFeature;

    private File lastOut;
    private boolean infoOpen;
    private int lastAccent;

    private EditText etSearch;
    private Button btnClearSearch;
    private TemplatePreview tplPrev;
    private boolean searching;
    private final Map<String, String> hayCache = new LinkedHashMap<>();

    private class Card {
        SlotData.Feature f;
        View root;
        View header;
        View body;
        LinearLayout slotBox;
        TextView tvCount;
        TextView tvArrow;
        TextView btnMore;
        Button btnFillAll;
        Button btnClearAll;
        Spinner sp;
        View groupRow;
        List<String> groupNames = new ArrayList<>();
        int gi = 0;
        int shown = INITIAL_ROWS;
        boolean expanded = false;
        boolean rendered = false;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        T.init(this);
        lastAccent = T.accent();
        setContentView(R.layout.activity_main);
        store = new Store(this);

        container = findViewById(R.id.container);
        tvStatus = findViewById(R.id.tvStatus);
        tvSub = findViewById(R.id.tvSub);
        btnBuild = findViewById(R.id.btnBuild);
        btnOpen = findViewById(R.id.btnOpen);
        btnSettings = findViewById(R.id.btnSettings);
        topBar = findViewById(R.id.topBar);
        etSearch = findViewById(R.id.etSearch);
        btnClearSearch = findViewById(R.id.btnClearSearch);

        try {
            SlotData.load(this);
        } catch (Exception e) {
            toast("数据载入失败：" + e);
            return;
        }

        renderAll();
        applyTheme();
        updateStatus();
        updateTemplateLabel();

        String lo = store.getLastOut();
        if (lo != null) {
            File lf = new File(lo);
            if (lf.isFile()) lastOut = lf;
        }
        initTemplatePreview();
        updateTemplateLabel();
        maybeExtractDropIn7z();

        btnSettings.setOnClickListener(v ->
                startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS));
        btnBuild.setOnClickListener(v -> onBuildClick());
        btnOpen.setOnClickListener(v -> showOutputDialog());
        wireSearch();
    }

    private void wireSearch() {
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                doSearch(e.toString());
            }
        });
        btnClearSearch.setOnClickListener(v -> etSearch.setText(""));
    }

    private String hay(SlotData.Slot s) {
        String v = hayCache.get(s.id());
        if (v == null) {
            SlotData.Feature f = SlotData.BY_ID.get(s.featureId);
            v = (s.label + " " + s.path + " " + s.module + " " + s.group + " " + (f == null ? "" : f.name))
                    .toLowerCase();
            hayCache.put(s.id(), v);
        }
        return v;
    }

    /** 主页搜索：跨 11 个分类筛选所有槽位，结果可直接操作。 */
    private void doSearch(String q) {
        String k = q == null ? "" : q.trim().toLowerCase();
        if (k.length() == 0) {
            btnClearSearch.setVisibility(View.GONE);
            if (searching) {
                searching = false;
                renderAll();
                updateStatus();
            }
            return;
        }
        btnClearSearch.setVisibility(View.VISIBLE);
        if (!searching) {
            searching = true;
            cards.clear();
            slotRows.clear();
            rowCard.clear();
        }
        container.removeAllViews();
        LayoutInflater inf = getLayoutInflater();

        List<SlotData.Slot> hits = new ArrayList<>();
        for (SlotData.Feature f : SlotData.FEATURES) {
            for (SlotData.Slot s : f.slots) {
                if (hay(s).contains(k)) hits.add(s);
            }
        }
        final int cap = 200;
        TextView head = new TextView(this);
        head.setTextSize(14);
        head.setPadding((int) dp(8), (int) dp(10), (int) dp(8), (int) dp(10));
        head.setTextColor(T.accent());
        head.setText("搜索结果 " + hits.size() + " 项"
                + (hits.size() > cap ? "（只显示前 " + cap + " 项，请把关键词写具体些）" : "")
                + "\n点结果行里的按钮可直接换图 / 编辑 / 改尺寸 / 清除");
        container.addView(head);

        int limit = Math.min(cap, hits.size());
        for (int i = 0; i < limit; i++) {
            SlotData.Slot s = hits.get(i);
            View row = inf.inflate(R.layout.item_slot, container, false);
            bindSlot(row, s);
            container.addView(row);
            slotRows.put(s.id(), row);
            rowCard.put(s.id(), s.featureId);
        }
        tvStatus.setText("搜索中：命中 " + hits.size() + " 项；清空搜索框回到分类列表");
    }

    @Override
    protected void onResume() {
        super.onResume();
        T.init(this);
        SlotData.initScreen(this);
        if (T.accent() != lastAccent) {
            lastAccent = T.accent();
            renderAll();
            applyTheme();
            updateStatus();
            return;
        }
        refreshVisibleRows();
    }

    /** 平板/折叠屏旋转后：屏幕分辨率变了，把可见行的「输出 wxh」重新算一遍。 */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        SlotData.initScreen(this);
        refreshVisibleRows();
    }

    private void refreshVisibleRows() {
        if (searching) {
            doSearch(etSearch.getText().toString());
            return;
        }
        for (String id : new ArrayList<>(slotRows.keySet())) {
            SlotData.Slot s = SlotData.BY_KEY.get(id);
            View r = slotRows.get(id);
            if (s != null && r != null) bindSlot(r, s);
        }
    }

    private void applyTheme() {
        T.apply(this, topBar, btnOpen, btnClearSearch);
        btnSettings.setTextColor(0xFFFFFFFF);
        btnOpen.setEnabled(true);
        btnBuild.setBackground(T.rr(T.accent(), dp(10)));
        tvSub.setText("简单三步：选图片 → 打包 → 用主题管理器打开");
        tvStatus.setTextColor(0xFF8A8A8E);
    }

    private void initTemplatePreview() {
        tplPrev = null;
        String p = store.getTemplatePath();
        File f = p == null ? null : new File(p);
        if (f == null || !f.isFile() || f.length() == 0) {
            // 记录失效时，看看默认位置有没有模板文件，有就认领
            File alt = new File(getExternalFilesDir(null), "base-template.mtz");
            if (alt.isFile() && alt.length() > 0) {
                f = alt;
                store.setTemplatePath(alt.getAbsolutePath());
            } else {
                if (p != null) store.setTemplatePath(null);
                return;
            }
        }
        tplPrev = new TemplatePreview(f);
    }

    /** 后台把这一批槽位在模板里的图片抽成缩略图，回来刷新对应行。 */
    private void loadTemplateThumbs(List<SlotData.Slot> slots) {
        if (tplPrev == null || !tplPrev.valid() || slots == null || slots.isEmpty()) return;
        final List<SlotData.Slot> need = new ArrayList<>();
        for (SlotData.Slot s : slots) {
            if (store.imageFor(s) != null) continue;
            if (tplPrev.cached(s) != null) continue;
            need.add(s);
        }
        if (need.isEmpty()) return;
        tvStatus.setText("正在读取模板里的图片…");
        pool.execute(() -> {
            tplPrev.prepare(MainActivity.this, need, 128);
            ui.post(() -> {
                for (SlotData.Slot s : need) {
                    View r = slotRows.get(s.id());
                    if (r != null) bindSlot(r, s);
                }
                updateStatus();
            });
        });
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ==================== 界面 ====================

    private void renderAll() {
        container.removeAllViews();
        cards.clear();
        slotRows.clear();
        rowCard.clear();
        LayoutInflater inf = getLayoutInflater();
        container.addView(buildInfoCard(inf));
        for (SlotData.Feature f : SlotData.FEATURES) {
            container.addView(buildCard(inf, f));
        }
    }

    private View buildInfoCard(LayoutInflater inf) {
        View v = inf.inflate(R.layout.item_info, container, false);
        infoBody = v.findViewById(R.id.infoBody);
        tvInfoArrow = v.findViewById(R.id.tvInfoArrow);
        etTitle = v.findViewById(R.id.etTitle);
        etAuthor = v.findViewById(R.id.etAuthor);
        tvTemplate = v.findViewById(R.id.tvTemplate);

        etTitle.setText(store.getTitle());
        etAuthor.setText(store.getAuthor());

        final TextView summary = v.findViewById(R.id.tvInfoSummary);
        summary.setText(store.getTitle());

        View header = v.findViewById(R.id.infoHeader);
        header.setOnClickListener(x -> {
            infoOpen = !infoOpen;
            infoBody.setVisibility(infoOpen ? View.VISIBLE : View.GONE);
            tvInfoArrow.setText(infoOpen ? "▸" : "▾");
        });
        infoBody.setVisibility(infoOpen ? View.VISIBLE : View.GONE);
        tvInfoArrow.setText(infoOpen ? "▸" : "▾");

        Button tpl = v.findViewById(R.id.btnTemplate);
        tpl.setTextColor(T.accent());
        tpl.setOnClickListener(x -> onTemplateClick());
        return v;
    }

    private View buildCard(LayoutInflater inf, final SlotData.Feature f) {
        final Card c = new Card();
        c.f = f;
        View v = inf.inflate(R.layout.item_feature, container, false);
        c.root = v;
        c.header = v.findViewById(R.id.headerRow);
        c.body = v.findViewById(R.id.body);
        c.tvCount = v.findViewById(R.id.tvCount);
        c.tvArrow = v.findViewById(R.id.tvArrow);
        c.slotBox = v.findViewById(R.id.slotBox);
        c.sp = v.findViewById(R.id.spGroup);
        c.groupRow = v.findViewById(R.id.groupRow);
        c.btnMore = v.findViewById(R.id.btnMore);
        c.btnFillAll = v.findViewById(R.id.btnFillAll);
        c.btnClearAll = v.findViewById(R.id.btnClearAll);

        ((TextView) v.findViewById(R.id.tvName)).setText(f.name);
        ((TextView) v.findViewById(R.id.tvDesc)).setText(f.desc);
        c.btnFillAll.setTextColor(T.accent());
        c.btnClearAll.setTextColor(T.accent());
        c.btnMore.setTextColor(T.accent());

        c.groupNames = new ArrayList<>(f.groups.keySet());
        int best = 0, bestKey = -1;
        for (int i = 0; i < c.groupNames.size(); i++) {
            int k = 0;
            for (SlotData.Slot s : f.groups.get(c.groupNames.get(i))) if (s.key) k++;
            if (k > bestKey) {
                bestKey = k;
                best = i;
            }
        }
        c.gi = best;

        TextView scopeHint = v.findViewById(R.id.tvScopeHint);
        if (c.groupNames.size() > 1) {
            c.groupRow.setVisibility(View.VISIBLE);
            scopeHint.setVisibility(View.VISIBLE);
            scopeHint.setText("「范围」= 这个界面在主题包里的资源目录。\n"
                    + "同一张图通常有 浅色/深色 × 标准/超清 四份，名字一样、不是重复；\n"
                    + "多数手机用「浅色 · 标准」，想让夜间深色模式也变，要再改「深色」那一份"
                    + "（或在「整组套用一张图」里勾上「同时写入同名其它范围」）。");
            ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, c.groupNames);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            c.sp.setAdapter(ad);
            c.sp.setSelection(best);
            c.sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    c.gi = position;
                    c.shown = INITIAL_ROWS;
                    if (c.expanded) renderSlots(c);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        } else {
            c.groupRow.setVisibility(View.GONE);
        }

        c.btnFillAll.setOnClickListener(x -> onBulkClick(c));
        c.btnClearAll.setOnClickListener(x -> onClearFeature(c));
        Button groupSize = v.findViewById(R.id.btnGroupSize);
        groupSize.setTextColor(T.accent());
        groupSize.setOnClickListener(x -> SizeDialog.show(this, currentGroup(c), () -> {
            if (c.expanded) renderSlots(c);
        }));
        Button zip = v.findViewById(R.id.btnZip);
        if ("icons".equals(f.id)) {
            zip.setVisibility(View.VISIBLE);
            zip.setTextColor(T.accent());
            zip.setOnClickListener(x -> {
                pendingZipFeature = f;
                pickFile(REQ_ZIP, "*/*");
            });
        }

        c.btnMore.setOnClickListener(x -> {
            List<SlotData.Slot> g = currentGroup(c);
            if (c.shown < g.size()) {
                c.shown = Math.min(g.size(), c.shown + STEP_ROWS);
            } else {
                c.shown = INITIAL_ROWS;
            }
            renderSlots(c);
        });

        c.header.setOnClickListener(x -> toggleCard(c));

        cards.put(f.id, c);
        updateCount(c);
        return v;
    }

    private void toggleCard(Card c) {
        c.expanded = !c.expanded;
        c.body.setVisibility(c.expanded ? View.VISIBLE : View.GONE);
        c.tvArrow.setText(c.expanded ? "▸" : "▾");
        if (c.expanded && !c.rendered) {
            c.rendered = true;
            renderSlots(c);
        }
    }

    private List<SlotData.Slot> currentGroup(Card c) {
        List<SlotData.Slot> g = c.f.groups.get(c.groupNames.get(c.gi));
        return g == null ? new ArrayList<SlotData.Slot>() : g;
    }

    private void renderSlots(Card c) {
        c.slotBox.removeAllViews();
        List<SlotData.Slot> g = currentGroup(c);
        int total = g.size();
        int limit = Math.min(total, Math.max(c.shown, 1));
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < limit; i++) {
            SlotData.Slot s = g.get(i);
            View row = inf.inflate(R.layout.item_slot, c.slotBox, false);
            bindSlot(row, s);
            c.slotBox.addView(row);
            slotRows.put(s.id(), row);
            rowCard.put(s.id(), c.f.id);
        }
        if (limit < total) {
            c.btnMore.setVisibility(View.VISIBLE);
            c.btnMore.setText("显示更多（" + limit + " / " + total + "）");
        } else if (total > INITIAL_ROWS) {
            c.btnMore.setVisibility(View.VISIBLE);
            c.btnMore.setText("收起（共 " + total + " 项）");
        } else {
            c.btnMore.setVisibility(View.GONE);
        }
        updateCount(c);
        loadTemplateThumbs(g.subList(0, limit));
    }

    private void bindSlot(View row, final SlotData.Slot s) {
        TextView label = row.findViewById(R.id.tvLabel);
        TextView sub = row.findViewById(R.id.tvSub);
        ImageView iv = row.findViewById(R.id.ivThumb);
        Button pick = row.findViewById(R.id.btnPick);
        Button edit = row.findViewById(R.id.btnEdit);
        Button size = row.findViewById(R.id.btnSize);
        Button del = row.findViewById(R.id.btnDel);

        label.setText(s.label);
        String featName = null;
        if (searching) {
            SlotData.Feature ff = SlotData.BY_ID.get(s.featureId);
            if (ff != null) featName = ff.name;
        }
        size.setTextColor(T.accent());
        size.setOnClickListener(v -> SizeDialog.show(this, java.util.Collections.singletonList(s), () -> {
            bindSlot(row, s);
            Card c = cards.get(rowCard.get(s.id()));
            if (c != null) updateCount(c);
        }));

        final File img = store.imageFor(s);
        final Bitmap tplBm = (img == null && tplPrev != null) ? tplPrev.cached(s) : null;
        if (img != null) {
            pick.setText("换图");
            edit.setEnabled(true);
            edit.setTextColor(T.accent());
            del.setEnabled(true);
            del.setTextColor(T.accent());
            iv.setTag(img.getAbsolutePath());
            loadThumb(iv, img);
            sub.setText(subTextOf(s, featName, false));
        } else if (tplBm != null) {
            pick.setText("换成自己的");
            edit.setEnabled(false);
            edit.setTextColor(0xFFBBBBBB);
            del.setEnabled(false);
            del.setTextColor(0xFFBBBBBB);
            iv.setTag(null);
            iv.setImageBitmap(tplBm);
            sub.setText(subTextOf(s, featName, true));
        } else {
            pick.setText("选图");
            edit.setEnabled(false);
            edit.setTextColor(0xFFBBBBBB);
            del.setEnabled(false);
            del.setTextColor(0xFFBBBBBB);
            iv.setTag(null);
            iv.setImageDrawable(null);
            sub.setText(subTextOf(s, featName, false));
        }

        // 点缩略图全屏看图（有图才可点）
        if (img != null || tplBm != null) {
            iv.setClickable(true);
            iv.setOnClickListener(v -> PreviewActivity.open(MainActivity.this, s.id()));
        } else {
            iv.setClickable(false);
            iv.setOnClickListener(null);
        }

        pick.setOnClickListener(v -> onPickClick(s));
        edit.setOnClickListener(v -> {
            if (store.imageFor(s) == null) {
                toast("先选一张图，再来裁剪");
                return;
            }
            pendingEdit = s;
            Intent i = new Intent(this, EditorActivity.class);
            i.putExtra(EditorActivity.EXTRA_SLOT, s.id());
            startActivityForResult(i, REQ_EDIT);
        });
        del.setOnClickListener(v -> {
            store.clear(s);
            bindSlot(row, s);
            Card c = cards.get(rowCard.get(s.id()));
            if (c != null) updateCount(c);
            updateStatus();
            loadTemplateThumbs(java.util.Collections.singletonList(s));
        });
    }

    private String subTextOf(SlotData.Slot s, String featName, boolean fromTemplate) {
        return "输出 " + s.sizeText() + (s.customSize() ? "（自定义）" : "（默认）")
                + (s.nine ? "  .9" : "")
                + (fromTemplate ? "  ·  模板图" : "")
                + (featName != null ? "  ·  " + featName : "")
                + "\n" + s.path;
    }

    private void loadThumb(final ImageView iv, final File img) {
        pool.execute(() -> {
            final Bitmap bm;
            try {
                bm = Img.decodeFile(img, 112);
            } catch (Throwable t) {
                return;
            }
            if (bm == null) return;
            ui.post(() -> {
                Object tag = iv.getTag();
                if (tag != null && tag.equals(img.getAbsolutePath())) {
                    iv.setImageBitmap(bm);
                }
            });
        });
    }

    private void updateCount(Card c) {
        int n = store.countFilled(c.f.slots);
        if (n > 0) {
            c.tvCount.setText("已选 " + n);
            c.tvCount.setTextColor(T.accent());
        } else {
            c.tvCount.setText("未设置");
            c.tvCount.setTextColor(0xFFAAAAAA);
        }
    }

    private void updateStatus() {
        int n = store.countAll();
        if (n == 0) {
            tvStatus.setText("还没有选图片。\n不选＝不改动，全部使用系统自带样式");
        } else {
            tvStatus.setText("已选 " + n + " 项，未选的界面保持系统自带");
        }
    }

    private void updateTemplateLabel() {
        String p = store.getTemplatePath();
        ThemeLib.Item cur = themeForPath(p);
        List<ThemeLib.Item> lib = ThemeLib.list(this);
        StringBuilder sb = new StringBuilder();
        if (cur != null) sb.append("基础模板：").append(cur.name);
        else if (p != null) sb.append("基础模板：").append(new File(p).getName());
        else sb.append("基础模板：未使用（只打包你选的图片）");
        sb.append("\n主题库：").append(lib.size()).append(" 个主题");
        if (lib.isEmpty()) sb.append("\n点这里导入主题文件，制作时还能从里面取素材");
        else sb.append("　可切换模板 / 取素材");
        tvTemplate.setText(sb.toString());
    }

    /** 按路径 / 文件名＋体积找出这个模板对应主题库里的哪一项（不读文件内容，够快）。 */
    private ThemeLib.Item themeForPath(String p) {
        if (p == null) return null;
        File f = new File(p);
        if (!f.isFile()) return null;
        for (ThemeLib.Item it : ThemeLib.list(this)) {
            if (it.file.getAbsolutePath().equals(p)) return it;
            if (it.file.getName().equals(f.getName()) && it.size == f.length()) return it;
        }
        return null;
    }

    // ==================== 交互 ====================

    /** 给某个槽位选图：相册、主题库两条来源。主题库空着时不打扰，直接开相册。 */
    private void onPickClick(final SlotData.Slot s) {
        pendingSlot = s;
        List<ThemeLib.Item> lib = ThemeLib.list(this);
        if (lib.isEmpty()) {
            pickFile(REQ_SLOT, "image/*");
            return;
        }
        StringBuilder sb = new StringBuilder("主题库里已有 " + lib.size() + " 个主题：");
        for (int i = 0; i < lib.size() && i < 4; i++) sb.append("\n· ").append(lib.get(i).name);
        if (lib.size() > 4) sb.append("\n· …");
        sb.append("\n\n从主题库取素材，可以直接用别的主题里的图标/图片，不用再找原图。");
        new AlertDialog.Builder(this)
                .setTitle("给「" + s.label + "」选图")
                .setMessage(sb.toString())
                .setPositiveButton("从相册选图片", (d, w) -> pickFile(REQ_SLOT, "image/*"))
                .setNeutralButton("从主题库取素材", (d, w) -> openThemePick(s))
                .setNegativeButton("取消", null)
                .show();
    }

    private void openThemePick(SlotData.Slot s) {
        Intent i = new Intent(this, ThemePickActivity.class);
        i.putExtra(ThemePickActivity.EXTRA_SLOT, s.id());
        ThemeLib.Item cur = themeForPath(store.getTemplatePath());
        if (cur != null) i.putExtra(ThemePickActivity.EXTRA_THEME, cur.id);
        startActivityForResult(i, REQ_PICKTHEME);
    }

    private void onTemplateClick() {
        startActivityForResult(new Intent(this, LibraryActivity.class), REQ_LIB);
    }

    /** 把刚打好的主题收进主题库，下次可当基础模板或从中取素材。 */
    private void saveAsTemplate(final File mtz) {
        if (mtz == null || !mtz.isFile()) {
            toast("没有可保存的主题包");
            return;
        }
        tvStatus.setText("正在存入主题库…");
        pool.execute(() -> {
            try {
                final ThemeLib.Item it = ThemeLib.add(MainActivity.this, mtz, ThemeLib.stripExt(mtz.getName()));
                ui.post(() -> {
                    updateTemplateLabel();
                    updateStatus();
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(it.duplicate ? "主题库里已经有这个主题" : "已存入主题库")
                            .setMessage("「" + it.name + "」 " + it.sizeText()
                                    + "\n\n要把它设为下次打包的基础模板吗？\n设为模板后，你没有替换的界面会原样保留。")
                            .setPositiveButton("设为基础模板", (d, w) -> {
                                store.setTemplatePath(it.file.getAbsolutePath());
                                initTemplatePreview();
                                renderAll();
                                updateTemplateLabel();
                                updateStatus();
                                toast("已设为基础模板：" + it.name);
                            })
                            .setNegativeButton("只保存", (d, w) -> toast("已存入主题库"))
                            .show();
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    updateStatus();
                    toast("存入主题库失败：" + t);
                });
            }
        });
    }

    private void onBulkClick(final Card c) {
        List<SlotData.Slot> g = currentGroup(c);
        List<SlotData.Slot> keys = new ArrayList<>();
        for (SlotData.Slot s : g) if (s.key) keys.add(s);
        final List<SlotData.Slot> base = keys.isEmpty() ? new ArrayList<>(g) : keys;
        if (base.isEmpty()) return;
        if (base.size() > MAX_BULK) {
            toast("本组可选槽位太多（" + base.size() + "），请用「选图」逐个设置");
            return;
        }

        View v = getLayoutInflater().inflate(R.layout.dialog_bulk, null);
        final TextView msg = v.findViewById(R.id.tvBulkMsg);
        final TextView hint = v.findViewById(R.id.tvBulkHint);
        final android.widget.CheckBox cb = v.findViewById(R.id.cbSync);

        final List<SlotData.Slot>[] cur = new List[1];
        cur[0] = base;
        Runnable upd = () -> {
            cur[0] = cb.isChecked() ? withSiblings(c.f, base) : base;
            List<SlotData.Slot> hit = cur[0];
            msg.setText("当前范围：" + c.groupNames.get(c.gi)
                    + "\n数量：" + hit.size() + " 个槽位\n\n"
                    + "同一张图会按各槽位尺寸自动缩放（.9.png 自动生成九宫格），之后仍可单独更换或裁剪。");
            if (hit.size() > MAX_BULK) {
                hint.setText("⚠ 展开后槽位太多（" + hit.size() + "，上限 " + MAX_BULK
                        + "），请取消勾选，或先用「选图」逐个设置。");
            } else {
                hint.setText(cb.isChecked()
                        ? "已展开：同一个文件名的图片，在浅色/深色、标准/超清里会一起写掉，省得改四遍。"
                        : "范围＝主题包里的资源目录（浅色/深色 × 屏幕密度）。想让夜间深色模式也变，建议勾上。");
            }
        };
        cb.setOnCheckedChangeListener((b, x) -> upd.run());
        upd.run();

        new AlertDialog.Builder(this)
                .setTitle("整组套用一张图")
                .setView(v)
                .setPositiveButton("选择图片", (d, w) -> {
                    if (cur[0].size() > MAX_BULK) {
                        toast("槽位太多（" + cur[0].size() + "），请取消勾选或缩小范围");
                        return;
                    }
                    pendingBulk = cur[0];
                    pickFile(REQ_BULK, "image/*");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 把目标槽位展开成「同名文件的所有范围」。
     * 例：改 res/drawable-xxhdpi/notification_item_bg.9.png 时，
     * 把 nightmode/… 与 drawable-nxhdpi/… 里的同名图一起带上，深浅色 + 两种密度一次搞定。
     */
    @SuppressWarnings("unchecked")
    private List<SlotData.Slot> withSiblings(SlotData.Feature f, List<SlotData.Slot> targets) {
        Map<String, List<SlotData.Slot>> byBase = new LinkedHashMap<>();
        for (SlotData.Slot s : f.slots) {
            int i = s.path.lastIndexOf('/');
            String b = i < 0 ? s.path : s.path.substring(i + 1);
            List<SlotData.Slot> l = byBase.get(b);
            if (l == null) {
                l = new ArrayList<>();
                byBase.put(b, l);
            }
            l.add(s);
        }
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        List<SlotData.Slot> out = new ArrayList<>();
        for (SlotData.Slot t : targets) {
            int i = t.path.lastIndexOf('/');
            String b = i < 0 ? t.path : t.path.substring(i + 1);
            List<SlotData.Slot> sib = byBase.get(b);
            if (sib == null) sib = java.util.Collections.singletonList(t);
            for (SlotData.Slot s : sib) {
                if (seen.add(s.id())) out.add(s);
            }
        }
        return out;
    }


    private void pickFile(int req, String type) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            Intent j = new Intent(Intent.ACTION_GET_CONTENT);
            j.setType(type);
            startActivityForResult(j, req);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SETTINGS) return;
        if (req == REQ_LIB) {
            // 主题库那边可能换了基础模板 / 改名 / 删了，回来全部刷新
            initTemplatePreview();
            renderAll();
            updateTemplateLabel();
            updateStatus();
            return;
        }
        if (req == REQ_PICKTHEME) {
            final SlotData.Slot s = pendingSlot;
            pendingSlot = null;
            if (res == RESULT_OK && s != null) {
                View row = slotRows.get(s.id());
                if (row != null) bindSlot(row, s);
                Card c = cards.get(rowCard.get(s.id()));
                if (c != null) updateCount(c);
                updateStatus();
            }
            return;
        }
        if (req == REQ_EDIT) {
            if (res == RESULT_OK && pendingEdit != null) {
                View row = slotRows.get(pendingEdit.id());
                if (row != null) bindSlot(row, pendingEdit);
                Card c = cards.get(rowCard.get(pendingEdit.id()));
                if (c != null) updateCount(c);
                toast("已保存裁剪结果");
            }
            pendingEdit = null;
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        switch (req) {
            case REQ_SLOT: {
                final SlotData.Slot s = pendingSlot;
                if (s == null) return;
                runStoreOne(uri, s);
                break;
            }
            case REQ_BULK: {
                final List<SlotData.Slot> list = pendingBulk;
                if (list == null || list.isEmpty()) return;
                tvStatus.setText("正在处理 " + list.size() + " 个槽位…");
                pool.execute(() -> {
                    try {
                        int maxDim = 512;
                        for (SlotData.Slot s : list) {
                            if (s.w > 0 && s.h > 0) {
                                maxDim = Math.max(maxDim, Math.min(2048, Math.max(s.w, s.h) * 2));
                            }
                        }
                        Bitmap bm = Img.decodeUri(getContentResolver(), uri, maxDim);
                        if (bm == null) throw new Exception("无法解码该图片");
                        for (SlotData.Slot s : list) store.put(s, bm);
                        bm.recycle();
                        ui.post(() -> {
                            SlotData.Slot probe = list.get(0);
                            Card c = cards.get(rowCard.get(probe.id()));
                            if (c != null && c.expanded) renderSlots(c);
                            for (SlotData.Slot s : list) {
                                Card cc = cards.get(rowCard.get(s.id()));
                                if (cc != null) updateCount(cc);
                            }
                            updateStatus();
                            toast("已套用到 " + list.size() + " 个槽位");
                        });
                    } catch (Throwable t) {
                        fail(t);
                    }
                });
                break;
            }
            case REQ_TEMPLATE: {
                tvStatus.setText("正在复制模板…");
                pool.execute(() -> {
                    try {
                        File dir = getExternalFilesDir(null);
                        File staged = new File(dir, "template.in");
                        File dst = new File(dir, "base-template.mtz");
                        InputStream in = getContentResolver().openInputStream(uri);
                        FileOutputStream out = new FileOutputStream(staged);
                        try {
                            byte[] buf = new byte[1 << 16];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        } finally {
                            Img.closeQuietly(in);
                            Img.closeQuietly(out);
                        }
                        String note;
                        if (SevenZ.is7z(staged)) {
                            ui.post(() -> tvStatus.setText("检测到 7z 压缩包，正在取出里面的主题…"));
                            SevenZ.extractMtz(staged, dst,
                                    msg -> ui.post(() -> tvStatus.setText(msg)));
                            staged.delete();
                            note = "已从 7z 中取出主题";
                        } else {
                            if (dst.exists()) dst.delete();
                            if (!staged.renameTo(dst)) {
                                copyFileTo(staged, dst);
                                staged.delete();
                            }
                            note = "模板已就绪";
                        }
                        store.setTemplatePath(dst.getAbsolutePath());
                        ui.post(() -> {
                            initTemplatePreview();
                            renderAll();
                            updateTemplateLabel();
                            updateStatus();
                            tvStatus.setText(note + "：" + dst.getName()
                                    + "（" + (dst.length() / 1048576) + " MB）；"
                                    + "展开任意分类即可看到模板里的原图");
                        });
                    } catch (Throwable t) {
                        fail(t);
                    }
                });
                break;
            }
            case REQ_ZIP: {
                final SlotData.Feature f = pendingZipFeature;
                if (f == null) return;
                tvStatus.setText("正在导入图标包…");
                pool.execute(() -> {
                    try {
                        final int n = importIconZip(uri, f);
                        ui.post(() -> {
                            Card c = cards.get(f.id);
                            if (c != null) {
                                if (c.expanded) renderSlots(c);
                                updateCount(c);
                            }
                            updateStatus();
                            toast("已从压缩包导入 " + n + " 个图标");
                        });
                    } catch (Throwable t) {
                        fail(t);
                    }
                });
                break;
            }
        }
    }

    private void fail(final Throwable t) {
        ui.post(() -> {
            updateStatus();
            toast("出错：" + t);
        });
    }

    private void storeOne(android.net.Uri uri, SlotData.Slot s) throws Exception {
        int maxDim = 2048;
        if (s.w > 0 && s.h > 0) {
            maxDim = Math.min(2048, Math.max(512, Math.max(s.w, s.h) * 2));
        }
        Bitmap bm = Img.decodeUri(getContentResolver(), uri, maxDim);
        if (bm == null) throw new Exception("无法解码该图片");
        try {
            store.put(s, bm);
        } finally {
            bm.recycle();
        }
    }

    /** 单张存图（后台线程）。 */
    private void runStoreOne(final android.net.Uri uri, final SlotData.Slot s) {
        tvStatus.setText("正在处理图片…");
        pool.execute(() -> {
            try {
                storeOne(uri, s);
                ui.post(() -> {
                    View row = slotRows.get(s.id());
                    if (row != null) bindSlot(row, s);
                    Card c = cards.get(rowCard.get(s.id()));
                    if (c != null) updateCount(c);
                    updateStatus();
                });
            } catch (Throwable t) {
                fail(t);
            }
        });
    }


    /** 刷新一批槽位对应的可见行与计数。 */
    private void refreshSlots(List<SlotData.Slot> list) {
        for (SlotData.Slot s : list) {
            View row = slotRows.get(s.id());
            if (row != null) bindSlot(row, s);
            String fid = rowCard.get(s.id());
            Card c = fid == null ? null : cards.get(fid);
            if (c != null) updateCount(c);
        }
        updateStatus();
    }

    /** 一键清空整个大类（所有分组）已选的图片。 */
    private void onClearFeature(final Card c) {
        final List<SlotData.Slot> all = c.f.slots;
        final int n = store.countFilled(all);
        if (n == 0) {
            toast("「" + c.f.name + "」还没有选过图片");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空本项")
                .setMessage("将清除「" + c.f.name + "」全部 " + all.size() + " 个槽位里已选的 "
                        + n + " 张图片。\n\n清空后本项恢复系统自带样式，不影响其它界面。")
                .setPositiveButton("全部清空", (d, w) -> {
                    store.clear(all);
                    if (searching) {
                        doSearch(etSearch.getText().toString());
                    } else {
                        if (c.expanded) renderSlots(c);
                        updateCount(c);
                        updateStatus();
                    }
                    toast("已清空「" + c.f.name + "」全部槽位");
                })
                .setNegativeButton("取消", null)
                .show();
    }


    private static void copyFileTo(File from, File to) throws Exception {
        InputStream in = new java.io.FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            Img.closeQuietly(in);
            Img.closeQuietly(out);
        }
    }

    /** 外部直接塞了 base-template.7z / .exe 时，后台自动解包并收进主题库。 */
    private void maybeExtractDropIn7z() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        final File out = new File(dir, "base-template.mtz");
        if (out.isFile()) return;
        File a = new File(dir, "base-template.7z");
        if (!a.isFile()) a = new File(dir, "base-template.exe");
        if (!a.isFile()) return;
        final File src = a;
        tvStatus.setText("发现 7z 模板，正在解包…");
        pool.execute(() -> {
            try {
                SevenZ.extractMtz(src, out, msg -> ui.post(() -> tvStatus.setText(msg)));
            } catch (Throwable t) {
                ui.post(() -> tvStatus.setText("7z 解包失败：" + t));
                return;
            }
            ThemeLib.Item it = ThemeLib.adoptLegacy(MainActivity.this, out);
            if (it != null) {
                ThemeLib.rename(MainActivity.this, it, ThemeLib.stripExt(src.getName()));
                store.setTemplatePath(it.file.getAbsolutePath());
                // 标记来源已处理，下次启动不再重复解包
                src.renameTo(new File(src.getAbsolutePath() + ".imported"));
            } else {
                store.setTemplatePath(out.getAbsolutePath());
            }
            ui.post(() -> {
                initTemplatePreview();
                renderAll();
                updateTemplateLabel();
                updateStatus();
            });
        });
    }

    private int importIconZip(android.net.Uri uri, SlotData.Feature f) throws Exception {
        Map<String, SlotData.Slot> byName = new LinkedHashMap<>();
        for (SlotData.Slot s : f.slots) {
            byName.put(s.path.substring(s.path.lastIndexOf('/') + 1).toLowerCase(), s);
        }
        InputStream raw = getContentResolver().openInputStream(uri);
        ZipInputStream zin = new ZipInputStream(raw);
        int n = 0;
        try {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                if (name.length() == 0 || name.charAt(0) == '.') continue;
                SlotData.Slot s = byName.get(name.toLowerCase());
                if (s == null) continue;
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                int k;
                while ((k = zin.read(buf)) > 0) bos.write(buf, 0, k);
                Bitmap bm = Img.decodeStream(new java.io.ByteArrayInputStream(bos.toByteArray()), 512);
                if (bm == null) continue;
                store.put(s, bm);
                bm.recycle();
                n++;
            }
        } finally {
            Img.closeQuietly(zin);
        }
        return n;
    }

    // ==================== 打包 ====================

    private void onBuildClick() {
        store.saveMeta(etTitle.getText().toString().trim(), etAuthor.getText().toString().trim());
        final String title = store.getTitle();
        final String author = store.getAuthor();
        final List<SlotData.Slot> filled = store.filledAll();
        if (filled.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("还没有选图片")
                    .setMessage("未设置图片的界面不会被改动，手机继续使用系统自带样式。\n\n"
                            + "先去任意一个分类里点「选图」，再回来打包。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        final String basePath = store.getTemplatePath();
        btnBuild.setEnabled(false);
        tvStatus.setText("准备打包…");

        pool.execute(() -> {
            try {
                List<ThemeBuilder.Fill> fills = new ArrayList<>();
                for (SlotData.Slot s : filled) {
                    File img = store.imageFor(s);
                    if (img != null) fills.add(new ThemeBuilder.Fill(s, img));
                }
                String safe = title.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (safe.length() == 0) safe = "theme";
                File out = new File(new File(getExternalFilesDir(null), "out"), safe + ".mtz");
                if (out.getParentFile() != null) out.getParentFile().mkdirs();
                File base = basePath == null ? null : new File(basePath);
                final ThemeBuilder.Result r = ThemeBuilder.build(MainActivity.this, out, fills, base,
                        title, author, null, msg -> ui.post(() -> tvStatus.setText(msg)));
                ui.post(() -> {
                    lastOut = r.out;
                    store.setLastOut(r.out.getAbsolutePath());
                    btnBuild.setEnabled(true);
                    btnOpen.setEnabled(true);
                    updateStatus();
                    StringBuilder sb = new StringBuilder();
                    sb.append("文件：").append(r.out.getName()).append('\n');
                    sb.append("大小：").append(String.format("%.1f", r.bytes / 1048576.0)).append(" MB\n");
                    sb.append("替换图片：").append(r.replaced).append(" 张\n");
                    sb.append("模块：").append(r.modules).append(" 个\n");
                    if (base != null) sb.append("基础模板：").append(base.getName()).append('\n');
                    sb.append("\n未选的界面未做任何改动，保持系统自带。");
                    if (!r.warnings.isEmpty()) {
                        sb.append("\n\n提示：\n");
                        for (String w : r.warnings) sb.append("· ").append(w).append('\n');
                    }
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("主题包已生成")
                            .setMessage(sb.toString()
                                    + "\n\n「保存为模板」会把它收进主题库："
                                    + "下次可以直接在它基础上继续改，或者从里面取素材。")
                            .setPositiveButton("用主题安装器打开", (d, w) -> openWithThemeKit(lastOut))
                            .setNeutralButton("保存为模板", (d, w) -> saveAsTemplate(lastOut))
                            .setNegativeButton("关闭", null)
                            .show();
                });
            } catch (final Throwable t) {
                ui.post(() -> {
                    btnBuild.setEnabled(true);
                    updateStatus();
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("打包失败")
                            .setMessage(String.valueOf(t))
                            .setPositiveButton("知道了", null)
                            .show();
                });
            }
        });
    }

    private void showOutputDialog() {
        if (lastOut == null || !lastOut.isFile()) {
            String p = store.getLastOut();
            if (p != null) {
                File f = new File(p);
                if (f.isFile()) lastOut = f;
            }
        }
        if (lastOut == null || !lastOut.isFile()) {
            new AlertDialog.Builder(this)
                    .setTitle("还没有主题包")
                    .setMessage("「打开/分享」会打开上一次打包生成的主题包。\n\n"
                            + "现在还没有生成过，先去选几张图片，再点「打包主题」。")
                    .setPositiveButton("去打包", (d, w) -> onBuildClick())
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(lastOut.getName())
                .setMessage(lastOut.getAbsolutePath() + "\n\n"
                        + String.format("%.2f", lastOut.length() / 1048576.0) + " MB")
                .setPositiveButton("用主题安装器打开", (d, w) -> openWithThemeKit(lastOut))
                .setNeutralButton("分享", (d, w) -> share(lastOut))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openWithThemeKit(File f) {
        android.net.Uri u = FsProvider.uriFor(f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, "application/octet-stream");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "打开主题包"));
        } catch (Exception e) {
            toast("没有找到可以打开 .mtz 的应用");
        }
    }

    private void share(File f) {
        android.net.Uri u = FsProvider.uriFor(f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "分享主题包"));
        } catch (Exception e) {
            toast("分享失败：" + e);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

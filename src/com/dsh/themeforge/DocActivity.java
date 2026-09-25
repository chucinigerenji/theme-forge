package com.dsh.themeforge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** 文档页：使用手册 / 新手教程 / MTZ 文件功能对照表。 */
public class DocActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_GUIDE = "guide";
    public static final String MODE_TABLE = "table";
    public static final String MODE_TUTORIAL = "tutorial";

    private String mode;
    private final List<Row> all = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private RowAdapter adapter;
    private TextView tvCount;

    private static class Row {
        String a;
        String b;
        String key;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        T.init(this);
        setContentView(R.layout.activity_doc);
        SlotData.initScreen(this);

        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_GUIDE;

        T.apply(this, findViewById(R.id.topBar));
        Button back = findViewById(R.id.btnBack);
        back.setOnClickListener(v -> finish());

        tvCount = findViewById(R.id.tvCount);
        TextView title = findViewById(R.id.tvTitle);

        if (MODE_TUTORIAL.equals(mode) || MODE_GUIDE.equals(mode)) {
            title.setText(MODE_TUTORIAL.equals(mode) ? "新手教程" : "使用手册");
            findViewById(R.id.searchBar).setVisibility(View.GONE);
            findViewById(R.id.listDoc).setVisibility(View.GONE);
            ScrollView sv = findViewById(R.id.scrollDoc);
            sv.setVisibility(View.VISIBLE);
            TextView tv = findViewById(R.id.tvDoc);
            tv.setText(readAsset(MODE_TUTORIAL.equals(mode) ? "tutorial.txt" : "guide.txt"));
            tvCount.setText(MODE_TUTORIAL.equals(mode)
                    ? "主题工坊 · 超详细新手教程（上下滑动阅读）"
                    : "主题工坊 · 使用手册（20 章，上下滑动阅读）");
            tv.setTextColor(0xFF333333);
        } else {
            title.setText("MTZ 文件功能对照表");
            findViewById(R.id.scrollDoc).setVisibility(View.GONE);
            findViewById(R.id.listDoc).setVisibility(View.VISIBLE);
            View bar = findViewById(R.id.searchBar);
            bar.setVisibility(View.VISIBLE);
            buildTable();
            ListView lv = findViewById(R.id.listDoc);
            adapter = new RowAdapter();
            lv.setAdapter(adapter);
            apply("");
            EditText et = findViewById(R.id.etSearch);
            et.setTextColor(Color.BLACK);
            et.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable e) {
                    apply(e.toString());
                }
            });
        }
    }

    private String readAsset(String name) {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(getAssets().open(name), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } catch (Throwable ignored) {
        } finally {
            Img.closeQuietly(br);
        }
        return sb.toString();
    }

    private void buildTable() {
        if (!all.isEmpty()) return;
        for (SlotData.Feature f : SlotData.FEATURES) {
            for (SlotData.Slot s : f.slots) {
                Row r = new Row();
                r.a = s.label;
                StringBuilder sb = new StringBuilder();
                sb.append(f.name).append("  ·  ").append(s.module).append(" · ").append(s.path);
                sb.append("   [").append(s.sizeText());
                if (s.nine) sb.append("  九宫格 .9");
                if (s.customSize()) sb.append("  自定义");
                sb.append(']');
                r.b = sb.toString();
                r.key = (r.a + " " + r.b).toLowerCase();
                all.add(r);
            }
        }
    }

    private void apply(String q) {
        shown.clear();
        String k = q == null ? "" : q.trim().toLowerCase();
        if (k.length() == 0) {
            shown.addAll(all);
        } else {
            for (Row r : all) if (r.key.contains(k)) shown.add(r);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        findViewById(R.id.tvEmpty).setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        tvCount.setText("共 " + all.size() + " 项" + (k.length() == 0 ? "" : ("，匹配 " + shown.size() + " 项")));
    }

    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            if (cv == null) {
                cv = getLayoutInflater().inflate(R.layout.item_doc, parent, false);
            }
            Row r = shown.get(i);
            ((TextView) cv.findViewById(R.id.tvA)).setText(r.a);
            ((TextView) cv.findViewById(R.id.tvB)).setText(r.b);
            return cv;
        }
    }
}

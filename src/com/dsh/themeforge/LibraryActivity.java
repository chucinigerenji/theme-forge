package com.dsh.themeforge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主题库：把主题文件（.mtz / .7z / 自解压 exe）存进 App，
 * 随时切换成「制作时的基础模板」，制作时还能从任意一个已保存的主题里取素材。
 */
public class LibraryActivity extends Activity {

    private static final int REQ_IMPORT = 21;

    private Store store;
    private LinearLayout box;
    private TextView tvSub;
    private TextView tvStatus;
    private View headBar;
    private Button btnImport;
    private Button btnBack;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        T.init(this);
        setContentView(R.layout.activity_library);
        store = new Store(this);

        box = findViewById(R.id.libBox);
        tvSub = findViewById(R.id.tvLibSub);
        tvStatus = findViewById(R.id.tvLibStatus);
        headBar = findViewById(R.id.headBar);
        btnImport = findViewById(R.id.btnImport);
        btnBack = findViewById(R.id.btnLibBack);

        T.apply(this, headBar, btnImport, btnBack);
        btnBack.setOnClickListener(v -> finish());
        btnImport.setOnClickListener(v -> pickThemeFile());

        tvStatus.setText("正在读取主题库…");
        pool.execute(() -> {
            adoptLegacy();
            ui.post(this::render);
        });
    }

    // ==================== 旧模板迁移 ====================

    /** 老版本的基础模板文件收进库里，避免升级后用户原来的模板丢失。 */
    private void adoptLegacy() {
        String p = store.getTemplatePath();
        if (p != null) {
            File f = new File(p);
            if (!f.isFile()) {
                store.setTemplatePath(null);
            } else if (ThemeLib.findByFile(this, f) == null) {
                ThemeLib.Item adopted = ThemeLib.adoptLegacy(this, f);
                if (adopted != null) store.setTemplatePath(adopted.file.getAbsolutePath());
            }
        }
        File ext = getExternalFilesDir(null);
        if (ext != null) {
            File orphan = new File(ext, "base-template.mtz");
            if (orphan.isFile() && ThemeLib.findByFile(this, orphan) == null) {
                ThemeLib.adoptLegacy(this, orphan);
            }
        }
    }

    // ==================== 列表 ====================

    private void render() {
        List<ThemeLib.Item> items = ThemeLib.list(this);
        box.removeAllViews();
        final String cur = store.getTemplatePath();

        if (items.isEmpty()) {
            tvSub.setText("还没有保存过主题");
            tvStatus.setText("点右上角「＋ 导入」，把 .mtz / .7z 主题文件存进来");
            TextView empty = new TextView(this);
            empty.setText("主题库是空的。\n\n"
                    + "「＋ 导入」把主题文件存进来以后：\n"
                    + "· 可以把它设成打包时的基础模板（未替换的界面原样保留）；\n"
                    + "· 制作主题给某个界面选图时，可以直接从这些主题里挑素材，\n"
                    + "  不用再去找原始图片文件。\n\n"
                    + "同一个文件导入多次不会重复占空间。");
            empty.setTextSize(13);
            empty.setLineSpacing(dp(3), 1f);
            empty.setTextColor(getResources().getColor(R.color.text_dim));
            empty.setPadding((int) dp(16), (int) dp(16), (int) dp(16), (int) dp(16));
            empty.setBackgroundResource(R.drawable.card_bg);
            box.addView(empty);
            return;
        }

        tvSub.setText("共 " + items.size() + " 个主题，可当基础模板 / 取素材");
        long total = 0;
        LayoutInflater inf = getLayoutInflater();
        for (ThemeLib.Item it : items) {
            total += it.size;
            View v = inf.inflate(R.layout.item_theme, box, false);
            TextView name = v.findViewById(R.id.tvThemeName);
            TextView meta = v.findViewById(R.id.tvThemeMeta);
            TextView badge = v.findViewById(R.id.tvThemeBadge);
            Button use = v.findViewById(R.id.btnThemeUse);

            final boolean isCur = it.file.getAbsolutePath().equals(cur);
            badge.setVisibility(isCur ? View.VISIBLE : View.GONE);
            badge.setBackground(T.rr(T.accent(), dp(7)));
            name.setText(it.name);

            StringBuilder sb = new StringBuilder();
            sb.append(it.sizeText()).append("  ·  ").append(it.dateText());
            if (it.source != null && it.source.length() > 0) sb.append('\n').append("来源：").append(it.source);
            if (isCur) sb.append('\n').append("打包基础模板 · 未替换的界面原样保留");
            meta.setText(sb.toString());

            use.setTextColor(T.accent());
            use.setText(isCur ? "使用中" : "设为模板");
            // 主操作一键直达（澎湃OS 上 AlertDialog 的 setItems 与 setMessage 会打架，
            // 所以「设为模板」不再弹选择框，其它操作走自定义对话框）
            use.setOnClickListener(x -> setBase(isCur ? null : it));
            v.setOnClickListener(x -> actions(it));
            box.addView(v);
        }
        tvStatus.setText(items.size() + " 个主题，共 " + (total / 1048576) + " MB");
    }

    // ==================== 单个主题的操作 ====================

    private void actions(final ThemeLib.Item it) {
        final boolean isCur = it.file.getAbsolutePath().equals(store.getTemplatePath());
        View v = getLayoutInflater().inflate(R.layout.dialog_theme_actions, null);
        ((TextView) v.findViewById(R.id.tvActMeta)).setText(
                it.sizeText() + "  ·  " + it.dateText()
                        + (it.source != null && it.source.length() > 0 ? "\n来源：" + it.source : "")
                        + (isCur ? "\n\n当前正作为打包基础模板，未替换的界面会原样保留。" : ""));

        Button base = v.findViewById(R.id.btnActBase);
        Button ren = v.findViewById(R.id.btnActRename);
        Button sha = v.findViewById(R.id.btnActShare);
        Button del = v.findViewById(R.id.btnActDelete);
        base.setText(isCur ? "取消使用此模板" : "设为打包基础模板");
        base.setTextColor(T.accent());

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(it.name)
                .setView(v)
                .setNegativeButton("关闭", null)
                .create();
        base.setOnClickListener(x -> {
            d.dismiss();
            setBase(isCur ? null : it);
        });
        ren.setOnClickListener(x -> {
            d.dismiss();
            renameDialog(it);
        });
        sha.setOnClickListener(x -> {
            d.dismiss();
            share(it.file);
        });
        del.setOnClickListener(x -> {
            d.dismiss();
            deleteDialog(it);
        });
        d.show();
    }

    private void setBase(ThemeLib.Item it) {
        store.setTemplatePath(it == null ? null : it.file.getAbsolutePath());
        render();
        toast(it == null ? "已取消基础模板" : "已设为基础模板：" + it.name);
        setResult(RESULT_OK);
    }

    private void renameDialog(final ThemeLib.Item it) {
        final EditText et = new EditText(this);
        et.setText(it.name);
        et.setSingleLine(true);
        et.setSelection(et.getText().length());
        int pad = (int) dp(16);
        et.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle("重命名主题")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    ThemeLib.rename(LibraryActivity.this, it, et.getText().toString());
                    render();
                    setResult(RESULT_OK);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteDialog(final ThemeLib.Item it) {
        new AlertDialog.Builder(this)
                .setTitle("删除主题")
                .setMessage("确定从主题库里删除「" + it.name + "」吗？\n\n"
                        + "只删除 App 里保存的这份，不影响你手机里的其它主题文件。")
                .setPositiveButton("删除", (d, w) -> {
                    boolean wasCur = it.file.getAbsolutePath().equals(store.getTemplatePath());
                    ThemeLib.remove(it);
                    if (wasCur) store.setTemplatePath(null);
                    render();
                    setResult(RESULT_OK);
                    toast("已删除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void share(File f) {
        try {
            Uri u = FsProvider.uriFor(f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享主题文件"));
        } catch (Exception e) {
            toast("分享失败：" + e);
        }
    }

    // ==================== 导入 ====================

    private void pickThemeFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            Intent j = new Intent(Intent.ACTION_GET_CONTENT);
            j.setType("*/*");
            startActivityForResult(j, REQ_IMPORT);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null) return;

        final java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        btnImport.setEnabled(false);
        tvStatus.setText("正在导入 " + uris.size() + " 个文件…");
        pool.execute(() -> {
            int ok = 0, dup = 0;
            final StringBuilder err = new StringBuilder();
            ThemeLib.Item last = null;
            for (Uri u : uris) {
                try {
                    String nm = nameOf(u);
                    File tmp = File.createTempFile("import", ".bin", getCacheDir());
                    try {
                        InputStream in = getContentResolver().openInputStream(u);
                        if (in == null) throw new Exception("无法读取文件");
                        FileOutputStream out = new FileOutputStream(tmp);
                        try {
                            byte[] buf = new byte[1 << 16];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        } finally {
                            Img.closeQuietly(in);
                            Img.closeQuietly(out);
                        }
                        final String showName = nm;
                        ThemeLib.Item it = ThemeLib.addAny(LibraryActivity.this, tmp,
                                ThemeLib.stripExt(nm),
                                msg -> ui.post(() -> tvStatus.setText(showName + "：" + msg)));
                        if (it.duplicate) dup++;
                        else {
                            ThemeLib.setSource(it, showName);
                            ok++;
                        }
                        last = it;
                    } finally {
                        if (tmp.exists()) tmp.delete();
                    }
                } catch (Throwable t) {
                    if (err.length() > 0) err.append('\n');
                    err.append(nameOf(u)).append("：").append(t.getMessage() == null ? t : t.getMessage());
                }
            }
            final int fOk = ok, fDup = dup;
            final ThemeLib.Item fLast = last;
            ui.post(() -> {
                btnImport.setEnabled(true);
                render();
                StringBuilder sb = new StringBuilder();
                sb.append("新导入 ").append(fOk).append(" 个");
                if (fDup > 0) sb.append("，").append(fDup).append(" 个库里已有（没重复占空间）");
                sb.append("。");
                AlertDialog.Builder ab = new AlertDialog.Builder(LibraryActivity.this)
                        .setTitle("导入完成")
                        .setMessage(sb.toString()
                                + (err.length() > 0 ? "\n\n失败：\n" + err : "")
                                + (fLast != null ? "\n\n要把它设为打包时的基础模板吗？" : ""))
                        .setNegativeButton("稍后再说", null);
                if (fLast != null) {
                    final ThemeLib.Item pick = fLast;
                    ab.setPositiveButton("设为基础模板", (d, w) -> setBase(pick));
                }
                ab.show();
            });
        });
    }

    private String nameOf(Uri u) {
        String s = u.getLastPathSegment();
        if (s == null) return "主题文件";
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    // ==================== 工具 ====================

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

package com.jisudownloader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.jisudownloader.R;
import com.jisudownloader.model.HistoryRecord;
import com.jisudownloader.model.HistoryStore;
import com.jisudownloader.util.Fmt;
import com.jisudownloader.util.ThemeHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends Activity implements HistoryStore.Listener {

    private ListView list;
    private TextView empty;
    private TextView selText;
    private View actionBar;
    private Adapter adapter;
    private final HashSet<String> selected = new HashSet<>();
    private boolean selectMode = false;

    private static class Handler extends android.os.Handler {
        Handler() {
            super(android.os.Looper.getMainLooper());
        }
    }

    private final Handler h = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        HistoryStore.init(this);

        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        selText = findViewById(R.id.selText);
        actionBar = findViewById(R.id.actionBar);

        adapter = new Adapter();
        list.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnManage).setOnClickListener(v -> toggleSelectMode());
        findViewById(R.id.btnCancel).setOnClickListener(v -> toggleSelectMode());
        findViewById(R.id.btnAll).setOnClickListener(v -> selectAll());
        findViewById(R.id.btnEdit).setOnClickListener(v -> editSelected());
        findViewById(R.id.btnDel).setOnClickListener(v -> deleteSelected());

        paint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        HistoryStore.addListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        HistoryStore.removeListener(this);
    }

    @Override
    public void onChanged() {
        runOnUiThread(() -> adapter.notifyDataSetChanged());
    }

    private void toggleSelectMode() {
        selectMode = !selectMode;
        if (!selectMode) selected.clear();
        actionBar.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        updateSelText();
    }

    private void selectAll() {
        List<HistoryRecord> all = HistoryStore.all();
        if (selected.size() == all.size()) {
            selected.clear();
        } else {
            selected.clear();
            for (HistoryRecord r : all) selected.add(r.id);
        }
        adapter.notifyDataSetChanged();
        updateSelText();
    }

    private void updateSelText() {
        selText.setText("已选 " + selected.size() + " 项");
        TextView edit = findViewById(R.id.btnEdit);
        edit.setAlpha(selected.size() == 1 ? 1f : 0.45f);
    }

    private void editSelected() {
        if (selected.size() != 1) {
            Toast.makeText(this, "请仅选择一项进行编辑", Toast.LENGTH_SHORT).show();
            return;
        }
        String id = selected.iterator().next();
        HistoryRecord rec = find(id);
        if (rec == null) return;
        showEdit(rec);
    }

    private HistoryRecord find(String id) {
        for (HistoryRecord r : HistoryStore.all()) if (r.id.equals(id)) return r;
        return null;
    }

    private void deleteSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择要删除的记录", Toast.LENGTH_SHORT).show();
            return;
        }
        int n = selected.size();
        new AlertDialog.Builder(this)
                .setTitle("删除记录")
                .setMessage("确定删除选中的 " + n + " 条下载记录吗？此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    List<String> ids = new ArrayList<>(selected);
                    HistoryStore.removeMany(ids);
                    selected.clear();
                    selectMode = false;
                    actionBar.setVisibility(View.GONE);
                    adapter.notifyDataSetChanged();
                    Toast.makeText(this, "已删除 " + n + " 条记录", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showEdit(final HistoryRecord rec) {
        Context ctx = this;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));

        EditText nameEdit = new EditText(ctx);
        nameEdit.setHint("文件名");
        nameEdit.setText(rec.fileName);
        nameEdit.setSingleLine(true);
        nameEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        nameEdit.setTextColor(ThemeHelper.textColor(this));
        nameEdit.setHintTextColor(ThemeHelper.subColor(this));

        EditText srcEdit = new EditText(ctx);
        srcEdit.setHint("下载链接");
        srcEdit.setText(rec.source);
        srcEdit.setSingleLine(false);
        srcEdit.setMaxLines(3);
        srcEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        srcEdit.setTextColor(ThemeHelper.textColor(this));
        srcEdit.setHintTextColor(ThemeHelper.subColor(this));

        TextView info = new TextView(ctx);
        info.setText("大小 " + Fmt.size(rec.size) + " · 耗时 " + Fmt.duration(rec.durationMs)
                + " · 平均 " + Fmt.speed(rec.avgSpeed) + "\n完成于 " + fmtTime(rec.finishedAt));
        info.setTextSize(12);
        info.setTextColor(ThemeHelper.subColor(this));
        info.setPadding(0, dp(10), 0, dp(6));

        box.addView(nameEdit);
        box.addView(srcEdit);
        box.addView(info);

        new AlertDialog.Builder(ctx)
                .setTitle("编辑记录")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String nm = nameEdit.getText().toString().trim();
                    String sr = srcEdit.getText().toString().trim();
                    if (nm.isEmpty() || sr.isEmpty()) {
                        Toast.makeText(ctx, "文件名和链接不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    rec.fileName = nm;
                    rec.source = sr;
                    HistoryStore.update(rec);
                    adapter.notifyDataSetChanged();
                    Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteOne(final HistoryRecord rec) {
        new AlertDialog.Builder(this)
                .setTitle("删除记录")
                .setMessage("确定删除「" + rec.fileName + "」的下载记录吗？此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    HistoryStore.remove(rec.id);
                    selected.remove(rec.id);
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String fmtTime(long ms) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(int v) {
        return ThemeHelper.dp(this, v);
    }

    private void paint() {
        int primary = ThemeHelper.primary(this);
        int bg = ThemeHelper.bgColor(this);
        int sub = ThemeHelper.subColor(this);
        findViewById(R.id.root).setBackgroundColor(bg);
        findViewById(R.id.header).setBackgroundColor(primary);
        actionBar.setBackgroundColor(primary);
        List<HistoryRecord> all = HistoryStore.all();
        empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private final class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return HistoryStore.all().size();
        }

        @Override
        public Object getItem(int position) {
            return HistoryStore.all().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(HistoryActivity.this).inflate(R.layout.item_history, parent, false);
                h = new Holder(v);
                v.setTag(h);
            } else {
                h = (Holder) v.getTag();
            }
            final HistoryRecord r = (HistoryRecord) getItem(position);
            bind(h, r);
            return v;
        }
    }

    private static final class Holder {
        final View root;
        final CheckBox check;
        final TextView name;
        final TextView source;
        final TextView info;
        final TextView time;
        final TextView btnDelete;

        Holder(View v) {
            root = v;
            check = v.findViewById(R.id.check);
            name = v.findViewById(R.id.name);
            source = v.findViewById(R.id.source);
            info = v.findViewById(R.id.info);
            time = v.findViewById(R.id.time);
            btnDelete = v.findViewById(R.id.btnDelete);
        }
    }

    private void bind(Holder h, final HistoryRecord r) {
        int text = ThemeHelper.textColor(this);
        int sub = ThemeHelper.subColor(this);
        int primary = ThemeHelper.primary(this);

        ThemeHelper.card(this, h.root, 14);

        h.name.setText(r.fileName);
        h.name.setTextColor(text);
        h.source.setText(r.source);
        h.source.setTextColor(sub);

        StringBuilder sb = new StringBuilder();
        sb.append("大小 ").append(Fmt.size(r.size));
        sb.append(" · 耗时 ").append(Fmt.duration(r.durationMs));
        sb.append(" · 平均 ").append(Fmt.speed(r.avgSpeed));
        h.info.setText(sb.toString());
        h.info.setTextColor(sub);

        h.time.setText(fmtTime(r.finishedAt));
        h.time.setTextColor(sub);

        h.check.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        h.check.setChecked(selected.contains(r.id));
        h.check.setOnCheckedChangeListener(null);
        h.check.setOnClickListener(null);

        h.btnDelete.setTextColor(0xFFE05252);
        h.btnDelete.setVisibility(selectMode ? View.GONE : View.VISIBLE);
        h.btnDelete.setOnClickListener(v -> deleteOne(r));

        h.root.setOnClickListener(v -> {
            if (selectMode) {
                if (selected.contains(r.id)) selected.remove(r.id);
                else selected.add(r.id);
                h.check.setChecked(selected.contains(r.id));
                updateSelText();
            } else {
                showEdit(r);
            }
        });
    }
}

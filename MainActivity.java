package com.promo.magicmobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

public class MainActivity extends Activity {
    private static final String MC = "com.mojang.minecraftpe";
    private static final int REQ_IMPORT = 1;

    private static class Entry { String label; String apkPath; boolean imported; File file; }

    private final List<Entry> entries = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private Spinner spinner;
    private ArrayAdapter<String> adapter;
    private TextView status;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("magic", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 80, 40, 40);

        TextView title = new TextView(this);
        title.setText("MagicMobile 0.2.0  (Android 10+)");
        title.setTextSize(20);
        root.addView(title);

        TextView lbl = new TextView(this);
        lbl.setText("\nMinecraft version:");
        root.addView(lbl);

        spinner = new Spinner(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < entries.size()) {
                    prefs.edit().putString("selected", entries.get(pos).label).apply();
                    updateStatus();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        root.addView(spinner);

        Button imp = new Button(this);
        imp.setText("Import version (APK)");
        imp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        });
        root.addView(imp);

        Button rem = new Button(this);
        rem.setText("Remove selected (imported only)");
        rem.setOnClickListener(v -> removeSelected());
        root.addView(rem);

        Button play = new Button(this);
        play.setText("Play");
        play.setOnClickListener(v -> {
            Entry e = selected();
            if (e == null) { toast("No Minecraft version available. Install or import one."); return; }
            toast("Loader comes in the next step. Selected: " + e.label);
        });
        root.addView(play);

        status = new TextView(this);
        status.setPadding(0, 30, 0, 0);
        root.addView(status);

        setContentView(root);
        refresh();
    }

    private Entry selected() {
        int p = spinner.getSelectedItemPosition();
        return (p >= 0 && p < entries.size()) ? entries.get(p) : null;
    }

    private void refresh() {
        entries.clear();
        labels.clear();
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(MC, 0);
            Entry e = new Entry();
            e.label = "Installed: " + pi.versionName;
            e.apkPath = pi.applicationInfo.sourceDir;
            entries.add(e);
        } catch (PackageManager.NameNotFoundException ignored) {}

        File[] files = new File(getFilesDir(), "versions").listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".apk")) {
                    Entry e = new Entry();
                    e.label = "Imported: " + f.getName().substring(0, f.getName().length() - 4);
                    e.apkPath = f.getAbsolutePath();
                    e.imported = true;
                    e.file = f;
                    entries.add(e);
                }
            }
        }
        for (Entry e : entries) labels.add(e.label);
        adapter.notifyDataSetChanged();

        String sel = prefs.getString("selected", null);
        int idx = sel == null ? 0 : labels.indexOf(sel);
        if (idx < 0) idx = 0;
        if (!entries.isEmpty()) spinner.setSelection(idx);
        updateStatus();
    }

    private void updateStatus() {
        Entry e = selected();
        if (e == null) status.setText("No Minecraft found. Install it from Play Store or import an APK.");
        else status.setText("Selected: " + e.label + "\n" + e.apkPath);
    }

    private void removeSelected() {
        Entry e = selected();
        if (e == null || !e.imported) { toast("Only imported versions can be removed."); return; }
        if (e.file.delete()) toast("Removed " + e.label); else toast("Could not remove.");
        refresh();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMPORT && res == RESULT_OK && data != null && data.getData() != null) {
            importApk(data.getData());
        }
    }

    private void importApk(final Uri uri) {
        status.setText("Importing... (big file, wait)");
        new Thread(() -> {
            File tmpDir = new File(getFilesDir(), "versions_tmp");
            tmpDir.mkdirs();
            File tmp = new File(tmpDir, "import.apk");
            String result;
            try {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                PackageInfo pi = getPackageManager().getPackageArchiveInfo(tmp.getAbsolutePath(), 0);
                if (pi == null || !MC.equals(pi.packageName)) {
                    result = "Not a Minecraft Bedrock APK.";
                } else {
                    boolean hasLib;
                    try (ZipFile z = new ZipFile(tmp)) {
                        hasLib = z.getEntry("lib/arm64-v8a/libminecraftpe.so") != null;
                    }
                    if (!hasLib) {
                        result = "This APK has no arm64 libminecraftpe.so (split APK?). Import the full arm64 APK.";
                    } else {
                        String name = (pi.versionName == null ? "unknown" : pi.versionName).replaceAll("[^A-Za-z0-9._-]", "_");
                        File dir = new File(getFilesDir(), "versions");
                        dir.mkdirs();
                        File dest = new File(dir, name + ".apk");
                        if (dest.exists()) dest.delete();
                        result = tmp.renameTo(dest) ? "Imported " + name : "Could not save the APK.";
                    }
                }
            } catch (Exception e) {
                result = "Import failed: " + e.getMessage();
            }
            tmp.delete();
            final String msg = result;
            runOnUiThread(() -> { toast(msg); refresh(); });
        }).start();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}

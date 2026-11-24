package com.rexglue.pop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class LauncherActivity extends Activity {
    private static final int REQ_IMPORT_GAME_FOLDER = 1001;
    private static final int REQ_IMPORT_DRIVER_ZIP  = 1002;
    public  static final String SETTINGS_FILE = "pop_settings.properties";

    private Properties cfg = new Properties();
    private TextView statusText;
    private TextView progressText;
    private Button launchBtn;

    private Spinner fpsSpinner;
    private Spinner afSpinner;
    private Spinner driverSpinner;
    private Switch vsyncSwitch;
    private Switch msaaSwitch;
    private Switch fastBootSwitch;
    private Switch fpsOverlaySwitch;

    private final List<String> driverLabels = new ArrayList<>();
    private final List<String> driverPaths  = new ArrayList<>();
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSettings();
        buildUi();
        refreshDriverList();
        verifyGameFiles();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private File getSettingsFile() {
        File dir = getExternalFilesDir(null);
        if (dir != null) dir.mkdirs();
        return new File(dir != null ? dir : getFilesDir(), SETTINGS_FILE);
    }

    private void loadSettings() {
        cfg = new Properties();
        File f = getSettingsFile();
        if (f.isFile()) {
            try (FileInputStream fis = new FileInputStream(f)) {
                cfg.load(fis);
            } catch (Exception ignored) {}
        }
    }

    private int getInt(String k, int def) {
        try { return Integer.parseInt(cfg.getProperty(k, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }

    private boolean getBool(String k, boolean def) {
        return Boolean.parseBoolean(cfg.getProperty(k, String.valueOf(def)));
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#111319"));
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        // === LEFT PANEL: Game Data Path, Verification, Import & Launch ===
        ScrollView leftScroll = new ScrollView(this);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        leftLp.rightMargin = dp(10);
        leftScroll.setLayoutParams(leftLp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackgroundColor(Color.parseColor("#1A1E29"));
        left.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("PRINCE OF PERSIA (2008)");
        title.setTextColor(Color.parseColor("#F0B84B"));
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title);

        left.addView(sectionLabel("Game Data Folder"));

        File extDir = getExternalFilesDir(null);
        String dataPath = extDir != null ? extDir.getAbsolutePath() : "/sdcard/Android/data/com.princeofpersia.files/files";

        TextView pathText = new TextView(this);
        pathText.setText("Copy Xbox 360 game files (default.xex, Data360*.forge, Videos/) to:\n" + dataPath);
        pathText.setTextColor(Color.parseColor("#DCE3EC"));
        pathText.setTextSize(12f);
        pathText.setBackgroundColor(Color.parseColor("#11141C"));
        pathText.setPadding(dp(10), dp(8), dp(10), dp(8));
        left.addView(pathText);

        statusText = new TextView(this);
        statusText.setTextSize(12.5f);
        statusText.setPadding(0, dp(10), 0, dp(8));
        left.addView(statusText);

        progressText = new TextView(this);
        progressText.setTextColor(Color.parseColor("#66C2FF"));
        progressText.setTextSize(12f);
        progressText.setVisibility(View.GONE);
        left.addView(progressText);

        LinearLayout fileBtnRow = new LinearLayout(this);
        fileBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        fileBtnRow.setPadding(0, dp(6), 0, dp(6));

        Button importBtn = styledButton("Import Game Folder", "#2B5B84");
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b1.rightMargin = dp(6);
        importBtn.setLayoutParams(b1);
        importBtn.setOnClickListener(v -> {
            if (busy) return;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQ_IMPORT_GAME_FOLDER);
        });
        fileBtnRow.addView(importBtn);

        Button verifyBtn = styledButton("Re-Verify", "#3A4152");
        verifyBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        verifyBtn.setOnClickListener(v -> verifyGameFiles());
        fileBtnRow.addView(verifyBtn);
        left.addView(fileBtnRow);

        launchBtn = styledButton("LAUNCH GAME", "#2E8B57");
        launchBtn.setTextSize(16f);
        LinearLayout.LayoutParams launchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        launchLp.topMargin = dp(10);
        launchBtn.setLayoutParams(launchLp);
        launchBtn.setOnClickListener(v -> {
            if (busy) return;
            saveSettings();
            if (!verifyGameFiles()) {
                Toast.makeText(this, "Missing default.xex — import or copy game files first.", Toast.LENGTH_LONG).show();
                return;
            }
            startActivity(new Intent(this, PopActivity.class));
        });
        left.addView(launchBtn);

        leftScroll.addView(left);
        root.addView(leftScroll);

        // === RIGHT PANEL: Essential Settings & Driver Manager ===
        ScrollView rightScroll = new ScrollView(this);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        rightScroll.setLayoutParams(rightLp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackgroundColor(Color.parseColor("#1A1E29"));
        right.setPadding(dp(16), dp(14), dp(16), dp(14));

        right.addView(sectionLabel("Settings"));

        fpsSpinner = addSpinnerRow(right, "FPS Cap",
                new String[] { "30 FPS (Cool & Stable)", "45 FPS", "60 FPS", "Uncapped" },
                getInt("fps_idx", 0));

        afSpinner = addSpinnerRow(right, "Anisotropic Filtering",
                new String[] { "Off (Fastest)", "2x", "4x", "16x" },
                getInt("af_idx", 0));

        vsyncSwitch      = addSwitchRow(right, "FIFO VSync", getBool("vsync", true));
        msaaSwitch       = addSwitchRow(right, "2x MSAA", getBool("msaa", true));
        fastBootSwitch   = addSwitchRow(right, "Fast Boot", getBool("fast_boot", true));
        fpsOverlaySwitch = addSwitchRow(right, "Show FPS Counter", getBool("show_fps", true));

        right.addView(sectionLabel("GPU Driver (Turnip / Adreno)"));

        driverSpinner = addSpinnerRow(right, "Driver", new String[] { "System Adreno Driver" }, 0);

        LinearLayout drvRow = new LinearLayout(this);
        drvRow.setOrientation(LinearLayout.HORIZONTAL);
        drvRow.setPadding(0, dp(6), 0, dp(6));

        Button dlDriverBtn = styledButton("Download Turnip", "#8A5A1E");
        LinearLayout.LayoutParams d1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        d1.rightMargin = dp(6);
        dlDriverBtn.setLayoutParams(d1);
        dlDriverBtn.setOnClickListener(v -> fetchOnlineDrivers());
        drvRow.addView(dlDriverBtn);

        Button importDriverBtn = styledButton("Import .zip", "#3A4152");
        importDriverBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        importDriverBtn.setOnClickListener(v -> {
            if (busy) return;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            startActivityForResult(intent, REQ_IMPORT_DRIVER_ZIP);
        });
        drvRow.addView(importDriverBtn);
        right.addView(drvRow);

        Button saveBtn = styledButton("Save Settings", "#2B5B84");
        saveBtn.setOnClickListener(v -> {
            saveSettings();
            Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show();
        });
        right.addView(saveBtn);

        rightScroll.addView(right);
        root.addView(rightScroll);

        setContentView(root);
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#F0B84B"));
        tv.setTextSize(14f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(8), 0, dp(4));
        return tv;
    }

    private Button styledButton(String text, String hexColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundColor(Color.parseColor(hexColor));
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        return b;
    }

    private Spinner addSpinnerRow(LinearLayout parent, String label, String[] items, int selectedIdx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        Spinner sp = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        if (selectedIdx >= 0 && selectedIdx < items.length) {
            sp.setSelection(selectedIdx);
        }
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));
        row.addView(sp);
        parent.addView(row);
        return sp;
    }

    private Switch addSwitchRow(LinearLayout parent, String label, boolean checked) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(Color.WHITE);
        sw.setTextSize(13f);
        sw.setChecked(checked);
        sw.setPadding(0, dp(5), 0, dp(5));
        parent.addView(sw);
        return sw;
    }

    private void saveSettings() {
        String selectedDriverPath = "";
        int dIdx = driverSpinner.getSelectedItemPosition();
        if (dIdx >= 0 && dIdx < driverPaths.size()) {
            selectedDriverPath = driverPaths.get(dIdx);
        }

        cfg.setProperty("fps_idx", String.valueOf(fpsSpinner.getSelectedItemPosition()));
        cfg.setProperty("af_idx", String.valueOf(afSpinner.getSelectedItemPosition()));
        cfg.setProperty("vsync", String.valueOf(vsyncSwitch.isChecked()));
        cfg.setProperty("msaa", String.valueOf(msaaSwitch.isChecked()));
        cfg.setProperty("fast_boot", String.valueOf(fastBootSwitch.isChecked()));
        cfg.setProperty("show_fps", String.valueOf(fpsOverlaySwitch.isChecked()));
        cfg.setProperty("driver_so", selectedDriverPath);

        try (FileOutputStream fos = new FileOutputStream(getSettingsFile())) {
            cfg.store(fos, "Prince of Persia Launcher Settings");
        } catch (Exception ignored) {}
    }

    private boolean verifyGameFiles() {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            statusText.setText("Storage unavailable.");
            statusText.setTextColor(Color.parseColor("#FF6B6B"));
            return false;
        }
        dir.mkdirs();

        boolean hasXex     = new File(dir, "default.xex").isFile();
        boolean hasShaders = new File(dir, "ekshadersxenoncompress.bin").isFile();
        int forgeCount = 0;
        File[] rootFiles = dir.listFiles();
        if (rootFiles != null) {
            for (File f : rootFiles) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".forge")) forgeCount++;
            }
        }
        int bikCount = 0;
        File videosDir = new File(dir, "Videos");
        if (videosDir.isDirectory()) {
            File[] vids = videosDir.listFiles();
            if (vids != null) {
                for (File f : vids) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".bik")) bikCount++;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(hasXex ? "[OK] " : "[MISSING] ").append("default.xex\n");
        sb.append(forgeCount > 0 ? "[OK] " : "[MISSING] ").append("Forge archives: ").append(forgeCount).append("\n");
        sb.append(hasShaders ? "[OK] " : "[WARN] ").append("ekshadersxenoncompress.bin\n");
        sb.append(bikCount > 0 ? "[OK] " : "[WARN] ").append("Videos/*.bik: ").append(bikCount);

        boolean ready = hasXex && forgeCount > 0;
        statusText.setText(sb.toString());
        statusText.setTextColor(ready ? Color.parseColor("#7BE09C") : Color.parseColor("#FF7A7A"));
        launchBtn.setEnabled(ready);
        launchBtn.setAlpha(ready ? 1.0f : 0.45f);
        return ready;
    }

    private void refreshDriverList() {
        driverLabels.clear();
        driverPaths.clear();
        driverLabels.add("System Adreno Driver");
        driverPaths.add("");

        File driversRoot = new File(getFilesDir(), "drivers");
        if (driversRoot.isDirectory()) {
            File[] dirs = driversRoot.listFiles();
            if (dirs != null) {
                for (File d : dirs) {
                    if (!d.isDirectory()) continue;
                    File meta = new File(d, "meta.json");
                    String label = d.getName();
                    String libName = null;
                    if (meta.isFile()) {
                        try {
                            JSONObject obj = new JSONObject(readFileText(meta));
                            String n = obj.optString("name", d.getName());
                            String v = obj.optString("driverVersion", "");
                            label = v.isEmpty() ? n : (n + " " + v);
                            libName = obj.optString("libraryName", null);
                        } catch (Exception ignored) {}
                    }
                    File soFile = (libName != null && !libName.isEmpty()) ? new File(d, libName) : findFirstSo(d);
                    if (soFile != null && soFile.isFile()) {
                        driverLabels.add(label);
                        driverPaths.add(soFile.getAbsolutePath());
                    }
                }
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, driverLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        driverSpinner.setAdapter(adapter);

        String saved = cfg.getProperty("driver_so", "");
        int idx = driverPaths.indexOf(saved);
        driverSpinner.setSelection(idx >= 0 ? idx : 0);
    }

    private File findFirstSo(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".so")) return f;
        }
        return null;
    }

    private String readFileText(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_IMPORT_GAME_FOLDER) importGameFolderAsync(data.getData());
        else if (requestCode == REQ_IMPORT_DRIVER_ZIP) importDriverZipAsync(data.getData());
    }

    private void importGameFolderAsync(Uri treeUri) {
        busy = true;
        progressText.setVisibility(View.VISIBLE);
        progressText.setText("Scanning game folder...");
        new Thread(() -> {
            try {
                File destRoot = getExternalFilesDir(null);
                if (destRoot == null) throw new IllegalStateException("External storage unavailable");
                destRoot.mkdirs();
                String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
                int[] copied = new int[] { 0 };
                copyTreeRecursive(treeUri, rootDocId, destRoot, copied);
                runOnUiThread(() -> {
                    busy = false;
                    progressText.setText("Import complete (" + copied[0] + " files).");
                    verifyGameFiles();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    progressText.setText("Import failed: " + e.getMessage());
                });
            }
        }).start();
    }

    private void copyTreeRecursive(Uri treeUri, String parentDocId, File destDir, int[] copied) throws Exception {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] proj = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor c = getContentResolver().query(childrenUri, proj, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String docId = c.getString(0);
                String name  = c.getString(1);
                String mime  = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    File sub = new File(destDir, name);
                    sub.mkdirs();
                    copyTreeRecursive(treeUri, docId, sub, copied);
                } else {
                    File outFile = new File(destDir, name);
                    runOnUiThread(() -> progressText.setText("Importing: " + name + " (" + copied[0] + " done)"));
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    try (InputStream in = getContentResolver().openInputStream(docUri);
                         FileOutputStream out = new FileOutputStream(outFile)) {
                        if (in != null) {
                            byte[] buf = new byte[256 * 1024];
                            int len;
                            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                        }
                    }
                    copied[0]++;
                }
            }
        }
    }

    private void importDriverZipAsync(Uri zipUri) {
        busy = true;
        progressText.setVisibility(View.VISIBLE);
        progressText.setText("Importing driver zip...");
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(zipUri)) {
                if (in == null) throw new IllegalStateException("Cannot open zip");
                extractDriverZip(in, "driver_" + System.currentTimeMillis());
                runOnUiThread(() -> {
                    busy = false;
                    progressText.setText("Driver imported.");
                    refreshDriverList();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    progressText.setText("Import failed: " + e.getMessage());
                });
            }
        }).start();
    }

    private void fetchOnlineDrivers() {
        if (busy) return;
        busy = true;
        progressText.setVisibility(View.VISIBLE);
        progressText.setText("Fetching Turnip releases from GitHub...");
        new Thread(() -> {
            final List<String> names = new ArrayList<>();
            final List<String> urls  = new ArrayList<>();
            String[] repos = new String[] {
                    "https://api.github.com/repos/faux123/turnip-faux123-driver/releases?per_page=5",
                    "https://api.github.com/repos/MrPurple666/purple-turnip/releases?per_page=5",
                    "https://api.github.com/repos/K11MCH1/AdrenoToolsDrivers/releases?per_page=5"
            };
            for (String api : repos) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
                    conn.setRequestProperty("User-Agent", "PrinceOfPersia-Android");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    if (conn.getResponseCode() != 200) continue;
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    JSONArray rels = new JSONArray(sb.toString());
                    for (int i = 0; i < rels.length(); i++) {
                        JSONArray assets = rels.getJSONObject(i).optJSONArray("assets");
                        if (assets == null) continue;
                        for (int j = 0; j < assets.length(); j++) {
                            JSONObject a = assets.getJSONObject(j);
                            String name = a.optString("name", "");
                            String dl   = a.optString("browser_download_url", "");
                            if (name.toLowerCase().endsWith(".zip") && !dl.isEmpty()) {
                                names.add(name);
                                urls.add(dl);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            runOnUiThread(() -> {
                busy = false;
                if (names.isEmpty()) {
                    progressText.setText("Could not reach GitHub. Use 'Import .zip' instead.");
                    return;
                }
                progressText.setVisibility(View.GONE);
                new AlertDialog.Builder(this)
                        .setTitle("Download Turnip Driver")
                        .setItems(names.toArray(new String[0]), (d, which) ->
                                downloadDriverAsync(names.get(which), urls.get(which)))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }).start();
    }

    private void downloadDriverAsync(String zipName, String urlStr) {
        busy = true;
        progressText.setVisibility(View.VISIBLE);
        progressText.setText("Downloading " + zipName + "...");
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestProperty("User-Agent", "PrinceOfPersia-Android");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                    extractDriverZip(in, zipName.replaceAll("[^a-zA-Z0-9._-]", "_"));
                }
                runOnUiThread(() -> {
                    busy = false;
                    progressText.setText("Installed: " + zipName);
                    refreshDriverList();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    progressText.setText("Download failed: " + e.getMessage());
                });
            }
        }).start();
    }

    private void extractDriverZip(InputStream in, String folderName) throws Exception {
        File destDir = new File(new File(getFilesDir(), "drivers"), folderName);
        destDir.mkdirs();
        String canonicalBase = destDir.getCanonicalPath() + File.separator;
        boolean foundSo = false;
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            byte[] buf = new byte[64 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                File outFile = new File(destDir, new File(entry.getName()).getName());
                if (!outFile.getCanonicalPath().startsWith(canonicalBase)) continue;
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    int len;
                    while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                }
                if (outFile.getName().endsWith(".so")) {
                    outFile.setReadable(true, false);
                    outFile.setExecutable(true, false);
                    foundSo = true;
                }
                zis.closeEntry();
            }
        }
        if (!foundSo) throw new IllegalArgumentException("Zip does not contain a Vulkan .so driver");
    }
}
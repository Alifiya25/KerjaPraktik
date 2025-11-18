package com.example.kerjapraktik;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class InputData2Activity extends AppCompatActivity {

    private static final String TAG = "InputData2Activity";
    private static final String BASE_URL = "http://192.168.1.12/API_Android/public/rembesan/";
    private static final String SERVER_INPUT_URL = BASE_URL + "input";
    private static final String CEK_DATA_URL = BASE_URL + "cek-data";
    private static final String GET_PENGUKURAN_URL = BASE_URL + "get_pengukuran";
    private static final String HITUNG_SEMUA_URL = BASE_URL + "Rumus-Rembesan";

    private Spinner spinnerPengukuran;
    private Button btnPilihPengukuran, btnSimpanDanHitung;
    private EditText inputA1R, inputA1L, inputB1, inputB3, inputB5;
    private EditText inputElv624T1, inputElv615T2, inputPipaP1, inputTmaWaduk;
    private Spinner inputElv624T1Kode, inputElv615T2Kode, inputPipaP1Kode;
    private Map<Integer, Spinner> srKodeSpinners = new HashMap<>();
    private Map<Integer, EditText> srNilaiFields = new HashMap<>();

    private final int[] srKodeArray = {1,40,66,68,70,79,81,83,85,92,94,96,98,100,102,104,106};
    private final Map<String,Integer> tanggalToIdMap = new LinkedHashMap<>();
    private int pengukuranId = -1;

    private OfflineDataHelper offlineDb;
    private final AtomicInteger syncCounter = new AtomicInteger(0);
    private int syncTotal = 0;
    private boolean showSyncToast = false;
    private SharedPreferences prefs;

    private boolean isSyncInProgress = false;
    private Handler networkCheckHandler = new Handler();
    private Runnable networkCheckRunnable;
    private boolean lastOnlineStatus = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_input_data2);

        offlineDb = new OfflineDataHelper(this);
        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);

        bindViews();
        setupSpinners();
        setupClickHandlers();
        hideUnnecessaryFieldsHP2();

        checkInternetAndShowToast();
        startNetworkMonitoring();

        if (isInternetAvailable()) {
            logInfo("onCreate", "Internet available -> sync master pengukuran");
            syncPengukuranMaster();
            new Handler().postDelayed(this::startAutoSyncWhenOnline, 2000);
        } else {
            logInfo("onCreate", "Offline -> load tanggal dari local master");
            loadTanggalOffline();
        }
    }

    private void hideUnnecessaryFieldsHP2() {
        try {
            TextInputLayout b3Layout = findViewById(R.id.b3_layout);
            TextInputLayout b5Layout = findViewById(R.id.b5_layout);

            if (b3Layout != null) b3Layout.setVisibility(View.GONE);
            if (b5Layout != null) b5Layout.setVisibility(View.GONE);

            TextView thomsonTitle = findViewById(R.id.thomson_title);
            if (thomsonTitle != null) {
                thomsonTitle.setText("Thomson Weir - GALLERY (A1 R, A1 L, B1)");
            }
        } catch (Exception e) {
            Log.e("InputData2Activity", "hideUnnecessaryFieldsHP2 - Error: " + e.getMessage(), e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        logInfo("onResume", "onResume fired");
        checkInternetAndShowToast();

        if (!isSyncInProgress && isInternetAvailable()) {
            syncCounter.set(0);
            syncTotal = 0;
            showSyncToast = true;

            try {
                syncTotal += offlineDb.getUnsyncedData("pengukuran").size();
                syncTotal += offlineDb.getUnsyncedData("thomson").size();
                syncTotal += offlineDb.getUnsyncedData("sr").size();
                syncTotal += offlineDb.getUnsyncedData("bocoran").size();
            } catch (Exception e) {
                logWarn("onResume", "Counting offline rows failed: " + e.getMessage());
            }

            if (syncTotal > 0) {
                logInfo("onResume", "Found " + syncTotal + " offline rows to sync");
                syncPengukuranMaster(() -> {
                    syncAllOfflineDataAuto(() -> {
                        if (syncTotal > 0 && !isAlreadySynced()) {
                            showElegantToast("Sinkronisasi offline selesai", "success");
                            markAsSynced();
                        }
                    });
                });
            } else {
                syncPengukuranMaster();
            }
        } else if (!isInternetAvailable()) {
            loadTanggalOffline();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopNetworkMonitoring();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopNetworkMonitoring();
        logInfo("onDestroy", "Activity destroyed");
    }

    private void startNetworkMonitoring() {
        networkCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkInternetAndShowToast();
                networkCheckHandler.postDelayed(this, 5000);
            }
        };
        networkCheckHandler.postDelayed(networkCheckRunnable, 5000);
    }

    private void stopNetworkMonitoring() {
        if (networkCheckHandler != null && networkCheckRunnable != null) {
            networkCheckHandler.removeCallbacks(networkCheckRunnable);
        }
    }

    private void checkInternetAndShowToast() {
        boolean isOnline = isInternetAvailable();
        if (isOnline != lastOnlineStatus) {
            if (isOnline) {
                showElegantToast("✅ Online - Koneksi tersedia", "success");
                startAutoSyncWhenOnline();
            } else {
                showElegantToast("📱 Offline - Data disimpan lokal", "warning");
            }
            lastOnlineStatus = isOnline;
        }
    }

    private void startAutoSyncWhenOnline() {
        if (isSyncInProgress || !isInternetAvailable()) {
            return;
        }

        int offlineCount = getOfflineDataCount();
        if (offlineCount > 0) {
            logInfo("AutoSync", "Found " + offlineCount + " offline data, starting auto-sync");
            triggerAutoSync();
        }
    }

    private int getOfflineDataCount() {
        int count = 0;
        try {
            count += offlineDb.getUnsyncedData("pengukuran").size();
            count += offlineDb.getUnsyncedData("thomson").size();
            count += offlineDb.getUnsyncedData("sr").size();
            count += offlineDb.getUnsyncedData("bocoran").size();
        } catch (Exception e) {
            logWarn("getOfflineDataCount", "Error counting offline data: " + e.getMessage());
        }
        return count;
    }

    private void triggerAutoSync() {
        if (isSyncInProgress) {
            return;
        }

        logInfo("AutoSync", "Triggering auto-sync for offline data");
        isSyncInProgress = true;

        showElegantToast("🔄 Auto-sync data offline...", "info");

        syncPengukuranMaster(() -> {
            syncAllOfflineDataAuto(() -> {
                isSyncInProgress = false;
                logInfo("AutoSync", "Auto-sync completed");
                runOnUiThread(this::loadTanggalOffline);
            });
        });
    }

    private void syncAllOfflineDataAuto(@Nullable Runnable onComplete) {
        logInfo("syncAllOfflineDataAuto", "Starting auto offline sync...");

        int offlineCount = getOfflineDataCount();
        if (offlineCount == 0) {
            logInfo("syncAllOfflineDataAuto", "No offline data to sync");
            if (onComplete != null) onComplete.run();
            return;
        }

        logInfo("syncAllOfflineDataAuto", "Auto-syncing " + offlineCount + " offline rows");

        // SIMPAN PENGUKURAN_ID SEBELUM SYNC UNTUK JAGA-JAGA
        final int currentPengukuranId = getLatestPengukuranIdForCalculation();
        if (currentPengukuranId != -1) {
            prefs.edit().putInt("pengukuran_id", currentPengukuranId).apply();
            logInfo("syncAllOfflineDataAuto", "Saved pengukuran_id to prefs: " + currentPengukuranId);
        }

        syncDataSerialAuto("pengukuran", () ->
                syncDataSerialAuto("thomson", () ->
                        syncDataSerialAuto("sr", () ->
                                syncDataSerialAuto("bocoran", () -> {
                                    logInfo("syncAllOfflineDataAuto", "All auto sync completed");

                                    // AUTO HITUNG SETELAH SYNC BERHASIL DENGAN RETRY
                                    if (offlineCount > 0) {
                                        runOnUiThread(() -> {
                                            showElegantToast("🔄 Menghitung data setelah sync...", "info");

                                            // COBA HITUNG DENGAN RETRY JIKA GAGAL
                                            attemptAutoHitungWithRetry(currentPengukuranId, 3, 2000);
                                        });
                                    }

                                    showElegantToast("✅ " + offlineCount + " data terkirim", "success");
                                    if (onComplete != null) onComplete.run();
                                }))));
    }

    private void attemptAutoHitungWithRetry(int pengukuranId, int retryCount, long delayMs) {
        if (retryCount <= 0) {
            showElegantToast("❌ Gagal menghitung setelah beberapa percobaan", "error");
            return;
        }

        if (!isInternetAvailable()) {
            showElegantToast("❌ Tidak ada koneksi internet untuk menghitung", "error");
            return;
        }

        logInfo("attemptAutoHitungWithRetry", "Attempt hitung, retry left: " + retryCount);

        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Menghitung data... (Percobaan " + (4 - retryCount) + "/3)");
        pd.setCancelable(false);
        pd.show();

        hitungDataOtomatisWithRetry(pengukuranId, pd, retryCount, delayMs);
    }

    private void hitungDataOtomatisWithRetry(int pengukuranId, ProgressDialog pd, int retryCount, long delayMs) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(HITUNG_SEMUA_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(300_000);
                conn.setReadTimeout(300_000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");

                JSONObject json = new JSONObject();
                json.put("pengukuran_id", pengukuranId);

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                InputStream is = (code == 200) ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject resp = new JSONObject(sb.toString());
                runOnUiThread(() -> {
                    pd.dismiss();
                    try {
                        String status = resp.optString("status", "");

                        if ("success".equalsIgnoreCase(status) || "partial_error".equalsIgnoreCase(status)) {
                            // BERHASIL ATAU PARTIAL SUCCESS - TAMPILKAN HASIL
                            processHitungResult(resp);
                        } else {
                            // GAGAL - COBA LAGI
                            if (retryCount > 1) {
                                showElegantToast("⚠️ Coba menghitung lagi...", "warning");
                                new Handler().postDelayed(() -> {
                                    attemptAutoHitungWithRetry(pengukuranId, retryCount - 1, delayMs + 1000);
                                }, delayMs);
                            } else {
                                showElegantToast("❌ Gagal menghitung setelah beberapa percobaan", "error");
                            }
                        }

                    } catch (Exception e) {
                        logError("hitungDataOtomatisWithRetry", "Error parsing: " + e.getMessage());
                        pd.dismiss();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    if (retryCount > 1) {
                        showElegantToast("⚠️ Coba menghitung lagi...", "warning");
                        new Handler().postDelayed(() -> {
                            attemptAutoHitungWithRetry(pengukuranId, retryCount - 1, delayMs + 1000);
                        }, delayMs);
                    } else {
                        showElegantToast("❌ Gagal menghitung: " + e.getMessage(), "error");
                    }
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void processHitungResult(JSONObject resp) {
        try {
            String status = resp.optString("status", "");
            JSONObject messages = resp.optJSONObject("messages");
            JSONObject data = resp.optJSONObject("data");
            String tanggal = resp.optString("tanggal", "-");

            StringBuilder msgBuilder = new StringBuilder();
            if (messages != null) {
                Iterator<String> keys = messages.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = messages.optString(key, "");
                    msgBuilder.append("• ").append(key).append(": ").append(value).append("\n");
                }
            }

            String lookBurtInfo = "";
            String statusKeterangan = "aman";
            if (data != null) {
                String rembBendungan = data.optString("rembesan_bendungan", "-");
                String rembPerM = data.optString("rembesan_per_m", "-");
                String ket = data.optString("keterangan", "-");
                lookBurtInfo = "\n💧 Analisa Look Burt:\n"
                        + "  - Rembesan Bendungan: " + rembBendungan + "\n"
                        + "  - Rembesan per M: " + rembPerM + "\n"
                        + "  - Keterangan: " + ket;

                if (ket.toLowerCase().contains("bahaya")) {
                    statusKeterangan = "danger";
                } else if (ket.toLowerCase().contains("peringatan") || ket.toLowerCase().contains("waspada")) {
                    statusKeterangan = "warning";
                } else {
                    statusKeterangan = "success";
                }
            }

            if ("success".equalsIgnoreCase(status)) {
                showCalculationResultDialog(" Perhitungan Berhasil",
                        "Semua perhitungan berhasil untuk tanggal " + tanggal + lookBurtInfo,
                        statusKeterangan, tanggal);
            } else if ("partial_error".equalsIgnoreCase(status)) {
                showCalculationResultDialog("⚠️ Perhitungan Sebagian Berhasil",
                        "Beberapa perhitungan gagal:\n\n" + msgBuilder.toString() + lookBurtInfo,
                        "warning", tanggal);
            } else {
                showElegantToast("❌ Gagal menghitung: " + resp.optString("message", "Terjadi kesalahan"), "error");
            }

        } catch (Exception e) {
            showElegantToast("Error parsing hasil: " + e.getMessage(), "error");
        }
    }

    // AUTO HITUNG SETELAH DATA OFFLINE BERHASIL DISINKRONKASI
    private void autoHitungSetelahSync() {
        if (!isInternetAvailable()) {
            showElegantToast("❌ Tidak bisa menghitung, tidak ada internet", "error");
            return;
        }

        // TUNGGU SEBENTAR UNTUK MEMASTIKAN DATA SUDAH TERSIMPAN DI SERVER
        new Handler().postDelayed(() -> {
            int latestPengukuranId = getLatestPengukuranIdForCalculation();
            if (latestPengukuranId != -1) {
                logInfo("autoHitungSetelahSync", "Auto hitung untuk pengukuran_id: " + latestPengukuranId);

                ProgressDialog pd = new ProgressDialog(this);
                pd.setMessage("Menghitung data setelah sync...");
                pd.setCancelable(false);
                pd.show();

                hitungDataOtomatis(latestPengukuranId, pd);
            } else {
                showElegantToast("❌ Gagal mendapatkan ID pengukuran untuk perhitungan", "error");
            }
        }, 2000); // Delay 2 detik untuk memastikan data tersimpan di server
    }

    private int getLatestPengukuranIdForCalculation() {
        // Priority 1: Ambil dari SharedPreferences (paling update)
        int savedId = prefs.getInt("pengukuran_id", -1);
        if (savedId != -1) {
            logInfo("getLatestPengukuranId", "Menggunakan pengukuran_id dari prefs: " + savedId);
            return savedId;
        }

        // Priority 2: Ambil dari spinner selection
        String sel = spinnerPengukuran.getSelectedItem() != null ?
                spinnerPengukuran.getSelectedItem().toString() : null;
        if (sel != null && tanggalToIdMap.containsKey(sel)) {
            int id = tanggalToIdMap.get(sel);
            logInfo("getLatestPengukuranId", "Menggunakan pengukuran_id dari spinner: " + id);
            return id;
        }

        // Priority 3: Coba ambil dari data offline terbaru
        try {
            List<Map<String, String>> unsyncedData = offlineDb.getUnsyncedData("pengukuran");
            if (unsyncedData != null && !unsyncedData.isEmpty()) {
                for (Map<String, String> data : unsyncedData) {
                    String jsonStr = data.get("json");
                    if (jsonStr != null) {
                        JSONObject json = new JSONObject(jsonStr);
                        if (json.has("pengukuran_id")) {
                            int id = json.getInt("pengukuran_id");
                            logInfo("getLatestPengukuranId", "Menggunakan pengukuran_id dari offline: " + id);
                            return id;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logError("getLatestPengukuranId", "Error getting ID from offline: " + e.getMessage());
        }

        logWarn("getLatestPengukuranId", "Tidak ada pengukuran_id yang ditemukan");
        return -1;
    }

    private void hitungDataOtomatis(int pengukuranId, ProgressDialog pd) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(HITUNG_SEMUA_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(300_000);
                conn.setReadTimeout(300_000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");

                JSONObject json = new JSONObject();
                json.put("pengukuran_id", pengukuranId);

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                InputStream is = (code == 200) ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject resp = new JSONObject(sb.toString());
                runOnUiThread(() -> {
                    pd.dismiss();
                    try {
                        String status = resp.optString("status", "");
                        JSONObject messages = resp.optJSONObject("messages");
                        JSONObject data = resp.optJSONObject("data");
                        String tanggal = resp.optString("tanggal", "-");

                        StringBuilder msgBuilder = new StringBuilder();
                        if (messages != null) {
                            Iterator<String> keys = messages.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                String value = messages.optString(key, "");
                                msgBuilder.append("• ").append(key).append(": ").append(value).append("\n");
                            }
                        }

                        String lookBurtInfo = "";
                        String statusKeterangan = "aman";
                        if (data != null) {
                            String rembBendungan = data.optString("rembesan_bendungan", "-");
                            String rembPerM = data.optString("rembesan_per_m", "-");
                            String ket = data.optString("keterangan", "-");
                            lookBurtInfo = "\n💧 Analisa Look Burt:\n"
                                    + "  - Rembesan Bendungan: " + rembBendungan + "\n"
                                    + "  - Rembesan per M: " + rembPerM + "\n"
                                    + "  - Keterangan: " + ket;

                            if (ket.toLowerCase().contains("bahaya")) {
                                statusKeterangan = "danger";
                            } else if (ket.toLowerCase().contains("peringatan") || ket.toLowerCase().contains("waspada")) {
                                statusKeterangan = "warning";
                            } else {
                                statusKeterangan = "success";
                            }
                        }

                        if ("success".equalsIgnoreCase(status)) {
                            showCalculationResultDialog(" Perhitungan Berhasil",
                                    "Semua perhitungan berhasil untuk tanggal " + tanggal + lookBurtInfo,
                                    statusKeterangan, tanggal);
                        } else if ("partial_error".equalsIgnoreCase(status)) {
                            showCalculationResultDialog("⚠️ Perhitungan Sebagian Berhasil",
                                    "Beberapa perhitungan gagal:\n\n" + msgBuilder.toString() + lookBurtInfo,
                                    "warning", tanggal);
                        } else if ("error".equalsIgnoreCase(status)) {
                            showElegantToast("❌ Gagal menghitung: " + resp.optString("message", "Terjadi kesalahan"), "error");
                        } else {
                            showElegantToast("ℹ️ Respon tidak dikenal dari server", "info");
                        }

                    } catch (Exception e) {
                        showElegantToast("Error parsing hasil: " + e.getMessage(), "error");
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    showElegantToast("Error saat menghitung: " + e.getMessage(), "error");
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void syncDataSerialAuto(String tableName, Runnable next) {
        List<Map<String,String>> list;
        try {
            list = offlineDb.getUnsyncedData(tableName);
        } catch (Exception e) {
            logError("syncDataSerialAuto", "Failed to read unsynced data for " + tableName + ": " + e.getMessage());
            if (next != null) next.run();
            return;
        }

        if (list == null || list.isEmpty()) {
            logInfo("syncDataSerialAuto", "No unsynced rows for " + tableName);
            if (next != null) next.run();
            return;
        }

        syncDataItemAuto(tableName, list, 0, next);
    }

    private void syncDataItemAuto(String tableName, List<Map<String,String>> dataList, int index, Runnable onFinish) {
        if (index >= dataList.size()) {
            if (onFinish != null) onFinish.run();
            return;
        }

        Map<String,String> item = dataList.get(index);
        String tempId = item.get("temp_id");
        String jsonStr = item.get("json");

        if (jsonStr == null || jsonStr.isEmpty()) {
            offlineDb.deleteByTempId(tableName, tempId);
            syncDataItemAuto(tableName, dataList, index + 1, onFinish);
            return;
        }

        new Thread(() -> {
            try {
                JSONObject json = new JSONObject(jsonStr);
                Map<String,String> dataMap = new HashMap<>();
                Iterator<String> it = json.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    dataMap.put(k, json.optString(k, ""));
                }

                HttpURLConnection conn = null;
                try {
                    URL url = new URL(SERVER_INPUT_URL);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(8_000);
                    conn.setReadTimeout(8_000);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("Accept", "application/json");

                    OutputStream os = conn.getOutputStream();
                    os.write(json.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    if (code == 200) {
                        offlineDb.deleteByTempId(tableName, tempId);
                        logInfo("syncDataItemAuto", "Synced " + tableName + " tempId=" + tempId);
                    }
                } catch (Exception e) {
                    logError("syncDataItemAuto", "Failed to sync tempId=" + tempId + ": " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            } catch (Exception e) {
                logError("syncDataItemAuto", "JSON parse failed for tempId=" + tempId + ": " + e.getMessage());
                offlineDb.deleteByTempId(tableName, tempId);
            }

            runOnUiThread(() -> syncDataItemAuto(tableName, dataList, index + 1, onFinish));
        }).start();
    }

    private void showElegantToast(String message, String type) {
        runOnUiThread(() -> {
            LayoutInflater inflater = getLayoutInflater();
            View layout = inflater.inflate(R.layout.toast_custom,
                    (android.view.ViewGroup) findViewById(R.id.custom_toast_container));

            TextView text = layout.findViewById(R.id.custom_toast_text);
            ImageView icon = layout.findViewById(R.id.custom_toast_icon);
            CardView card = layout.findViewById(R.id.custom_toast_card);

            text.setText(message);

            int colorRes = getColorForStatus(type);
            int iconRes = getIconForStatus(type);

            card.setCardBackgroundColor(ContextCompat.getColor(this, colorRes));
            icon.setImageResource(iconRes);

            card.setAlpha(0f);
            card.setScaleX(0.8f);
            card.setScaleY(0.8f);

            Toast toast = new Toast(getApplicationContext());
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setView(layout);
            toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 150);

            toast.show();

            card.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .start();
        });
    }

    private boolean isInternetAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo net = cm.getActiveNetworkInfo();
            return net != null && net.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private int getColorForStatus(String status) {
        switch (status.toLowerCase()) {
            case "success":
                return R.color.pln_success;
            case "warning":
                return R.color.pln_warning;
            case "error":
                return R.color.pln_danger;
            case "info":
            default:
                return R.color.pln_info;
        }
    }

    private int getIconForStatus(String status) {
        switch (status.toLowerCase()) {
            case "success":
                return R.drawable.ic_success;
            case "warning":
                return R.drawable.ic_warning;
            case "error":
                return R.drawable.ic_danger;
            case "info":
            default:
                return R.drawable.ic_info;
        }
    }

    private String safeText(EditText et) {
        return et == null ? "" : et.getText().toString().trim();
    }

    private void logInfo(String where, String msg) {
        Log.i(TAG, "[INFO][" + where + "] " + msg);
    }
    private void logWarn(String where, String msg) {
        Log.w(TAG, "[WARN][" + where + "] " + msg);
    }
    private void logError(String where, String msg) {
        Log.e(TAG, "[ERROR][" + where + "] " + msg);
    }

    private boolean isAlreadySynced() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String lastSyncDate = prefs.getString("last_sync_date", "");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        return today.equals(lastSyncDate);
    }

    private void markAsSynced() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        prefs.edit().putString("last_sync_date", today).apply();
    }

    private void bindViews() {
        spinnerPengukuran = findViewById(R.id.spinnerPengukuran);
        btnPilihPengukuran = findViewById(R.id.btnPilihPengukuran);
        btnSimpanDanHitung = findViewById(R.id.btnSimpanDanHitung);

        inputA1R = findViewById(R.id.inputA1R);
        inputA1L = findViewById(R.id.inputA1L);
        inputB1 = findViewById(R.id.inputB1);
        inputB3 = findViewById(R.id.inputB3);
        inputB5 = findViewById(R.id.inputB5);

        inputElv624T1 = findViewById(R.id.inputElv624T1);
        inputElv615T2 = findViewById(R.id.inputElv615T2);
        inputPipaP1 = findViewById(R.id.inputPipaP1);
        inputTmaWaduk = findViewById(R.id.inputTmaWaduk);

        inputElv624T1Kode = findViewById(R.id.inputElv624T1Kode);
        inputElv615T2Kode = findViewById(R.id.inputElv615T2Kode);
        inputPipaP1Kode = findViewById(R.id.inputPipaP1Kode);

        for (int kode : srKodeArray) {
            int kodeRes = getResources().getIdentifier("sr_" + kode + "_kode", "id", getPackageName());
            int nilaiRes = getResources().getIdentifier("sr_" + kode + "_nilai", "id", getPackageName());
            try {
                Spinner sp = findViewById(kodeRes);
                if (sp != null) srKodeSpinners.put(kode, sp);
            } catch (Exception e) { }
            try {
                EditText et = findViewById(nilaiRes);
                if (et != null) srNilaiFields.put(kode, et);
            } catch (Exception e) { }
        }
    }

    private void setupSpinners() {
        try {
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                    this, R.array.kode_options, android.R.layout.simple_spinner_item);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            for (Spinner s : srKodeSpinners.values()) {
                s.setAdapter(adapter);
            }
            if (inputElv624T1Kode != null) inputElv624T1Kode.setAdapter(adapter);
            if (inputElv615T2Kode != null) inputElv615T2Kode.setAdapter(adapter);
            if (inputPipaP1Kode != null) inputPipaP1Kode.setAdapter(adapter);
        } catch (Exception e) {
            logWarn("setupSpinners", "Gagal setup spinner: " + e.getMessage());
        }
    }

    private void setupClickHandlers() {
        btnPilihPengukuran.setOnClickListener(v -> {
            Object sel = spinnerPengukuran.getSelectedItem();
            if (sel == null) {
                showElegantToast("Pilih tanggal pengukuran dulu.", "warning");
                return;
            }

            String selected = sel.toString();
            if (tanggalToIdMap.containsKey(selected)) {
                pengukuranId = tanggalToIdMap.get(selected);
                prefs.edit().putInt("pengukuran_id", pengukuranId).apply();
                showElegantToast("Tanggal terpilih: " + selected, "success");
                logInfo("btnPilih", "tanggal pengukuran terpilih = " + selected);
            } else {
                showElegantToast("Tanggal tidak dikenali, coba sinkron ulang.", "error");
                logWarn("btnPilih", "tanggal '" + selected + "' tidak ada di map");
            }
        });

        // TOMBOL GABUNGAN SIMPAN & HITUNG
        btnSimpanDanHitung.setOnClickListener(v -> handleSimpanDanHitungSemua());
    }

    // METHOD UTAMA UNTUK TOMBOL GABUNGAN
    private void handleSimpanDanHitungSemua() {
        if (pengukuranId == -1) {
            showElegantToast("Pilih pengukuran terlebih dahulu.", "warning");
            return;
        }

        // Cek dulu apakah ada data yang diinput
        boolean adaData = !safeText(inputTmaWaduk).isEmpty() ||
                !safeText(inputA1R).isEmpty() || !safeText(inputA1L).isEmpty() || !safeText(inputB1).isEmpty() ||
                !safeText(inputElv624T1).isEmpty() || !safeText(inputElv615T2).isEmpty() || !safeText(inputPipaP1).isEmpty();

        if (!adaData) {
            showElegantToast("Masukkan data terlebih dahulu", "warning");
            return;
        }

        // Tampilkan progress dialog
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Menyimpan data...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        // Simpan semua data secara berurutan
        new Thread(() -> {
            try {
                // 1. Simpan TMA Waduk jika ada input
                String tmaWaduk = safeText(inputTmaWaduk);
                if (!tmaWaduk.isEmpty()) {
                    Map<String, String> tmaData = buildTmaData();
                    if (tmaData != null) {
                        simpanDataSinkron("pengukuran", tmaData);
                        Thread.sleep(500);
                    }
                }

                // 2. Simpan Data Thomson jika ada input
                if (!safeText(inputA1R).isEmpty() || !safeText(inputA1L).isEmpty() || !safeText(inputB1).isEmpty()) {
                    Map<String, String> thomsonData = buildThomsonDataHP2();
                    if (thomsonData != null) {
                        simpanDataSinkron("thomson", thomsonData);
                        Thread.sleep(500);
                    }
                }

                // 3. Simpan Data Bocoran jika ada input
                if (!safeText(inputElv624T1).isEmpty() || !safeText(inputElv615T2).isEmpty() || !safeText(inputPipaP1).isEmpty()) {
                    Map<String, String> bocoranData = buildBocoranData();
                    if (bocoranData != null) {
                        simpanDataSinkron("bocoran", bocoranData);
                        Thread.sleep(500);
                    }
                }

                runOnUiThread(() -> {
                    progressDialog.dismiss();

                    // JIKA ONLINE, LANGSUNG HITUNG
                    if (isInternetAvailable()) {
                        showElegantToast("✅ Data tersimpan, menghitung...", "success");
                        handleHitungSemua();
                    } else {
                        // JIKA OFFLINE, SIMPAN SAJA
                        showElegantToast("📱 Data disimpan offline, akan dihitung otomatis saat online", "warning");
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    showElegantToast("Error saat menyimpan data: " + e.getMessage(), "error");
                });
            }
        }).start();
    }

    private Map<String,String> buildThomsonDataHP2() {
        if (pengukuranId == -1) {
            return null;
        }
        Map<String,String> map = new HashMap<>();
        map.put("mode", "thomson");
        map.put("pengukuran_id", String.valueOf(pengukuranId));
        map.put("a1_r", safeText(inputA1R));
        map.put("a1_l", safeText(inputA1L));
        map.put("b1", safeText(inputB1));
        map.put("b3", "");
        map.put("b5", "");
        return map;
    }

    private Map<String,String> buildBocoranData() {
        if (pengukuranId == -1) {
            return null;
        }
        Map<String,String> map = new HashMap<>();
        map.put("mode", "bocoran");
        map.put("pengukuran_id", String.valueOf(pengukuranId));
        map.put("elv_624_t1", safeText(inputElv624T1));
        map.put("elv_624_t1_kode", inputElv624T1Kode != null && inputElv624T1Kode.getSelectedItem() != null ? inputElv624T1Kode.getSelectedItem().toString() : "");
        map.put("elv_615_t2", safeText(inputElv615T2));
        map.put("elv_615_t2_kode", inputElv615T2Kode != null && inputElv615T2Kode.getSelectedItem() != null ? inputElv615T2Kode.getSelectedItem().toString() : "");
        map.put("pipa_p1", safeText(inputPipaP1));
        map.put("pipa_p1_kode", inputPipaP1Kode != null && inputPipaP1Kode.getSelectedItem() != null ? inputPipaP1Kode.getSelectedItem().toString() : "");
        return map;
    }

    private Map<String,String> buildTmaData() {
        String tma = safeText(inputTmaWaduk);
        if (tma.isEmpty()) {
            return null;
        }
        Map<String,String> map = new HashMap<>();
        map.put("mode", "pengukuran");
        map.put("pengukuran_id", String.valueOf(pengukuranId));
        map.put("tma_waduk", tma);
        return map;
    }

    // METHOD UNTUK SIMPAN DATA SINKRON (DIGUNAKAN OLEH TOMBOL GABUNGAN)
    private void simpanDataSinkron(String table, Map<String,String> dataMap) {
        if (dataMap == null) return;

        if (!isInternetAvailable()) {
            saveOffline(table, dataMap);
            return;
        }

        // Untuk pengukuran (TMA), langsung kirim tanpa cek
        if ("pengukuran".equals(table)) {
            kirimDataSinkron(table, dataMap);
            return;
        }

        // Untuk tabel lain, cek dulu apakah data sudah ada
        if (!cekDataSudahAda(table, dataMap.get("pengukuran_id"))) {
            kirimDataSinkron(table, dataMap);
        }
    }

    private boolean cekDataSudahAda(String table, String pengukuranId) {
        if (!isInternetAvailable()) {
            return false;
        }

        HttpURLConnection conn = null;
        try {
            String urlStr = CEK_DATA_URL + "?pengukuran_id=" + pengukuranId;
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            InputStream is = (code == 200) ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JSONObject resp = new JSONObject(sb.toString());
            JSONObject data = resp.has("data") ? resp.getJSONObject("data") : resp;

            switch (table) {
                case "thomson":
                    return data.optBoolean("thomson_ada", false) && data.optBoolean("thomson_lengkap", false);
                case "sr":
                    return data.optBoolean("sr_ada", false);
                case "bocoran":
                    return data.optBoolean("bocoran_ada", false);
                default:
                    return false;
            }

        } catch (Exception e) {
            logWarn("cekDataSudahAda", "Gagal cek data: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void kirimDataSinkron(String table, Map<String,String> dataMap) {
        if (!isInternetAvailable()) {
            saveOffline(table, dataMap);
            return;
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(SERVER_INPUT_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");

            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> e : dataMap.entrySet()) {
                json.put(e.getKey(), e.getValue());
            }

            OutputStream os = conn.getOutputStream();
            os.write(json.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code == 200) {
                logInfo("kirimDataSinkron", "Data " + table + " berhasil dikirim");
            } else {
                // Jika gagal, simpan offline
                saveOffline(table, dataMap);
            }

        } catch (Exception e) {
            logError("kirimDataSinkron", "Gagal kirim data " + table + ": " + e.getMessage());
            saveOffline(table, dataMap);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void saveOffline(String table, Map<String,String> dataMap) {
        try {
            JSONObject json = new JSONObject(dataMap);
            String tempId = "local_" + System.currentTimeMillis();
            offlineDb.insertData(table, tempId, json.toString());
            logInfo("saveOffline", "Disimpan offline ke tabel " + table + " tempId=" + tempId);
        } catch (Exception e) {
            logError("saveOffline", "Gagal simpan offline: " + e.getMessage());
        }
    }

    private void syncPengukuranMaster() {
        syncPengukuranMaster(null);
    }

    private void syncPengukuranMaster(@Nullable Runnable onDone) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            List<String> tanggalList = new ArrayList<>();
            try {
                URL url = new URL(GET_PENGUKURAN_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("Accept", "application/json");

                int code = conn.getResponseCode();
                InputStream is = (code == 200) ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject resp = new JSONObject(sb.toString());
                String status = resp.optString("status", "");
                if (!"success".equalsIgnoreCase(status) && !resp.has("data")) {
                    logWarn("syncPengukuranMaster", "Server returned non-success when fetching pengukuran: " + resp.optString("message"));
                    runOnUiThread(() -> showElegantToast("❌ Gagal ambil daftar pengukuran dari server", "error"));
                    if (onDone != null) onDone.run();
                    return;
                }

                JSONArray arr = resp.optJSONArray("data");
                if (arr == null) arr = new JSONArray();

                OfflineDataHelper db = new OfflineDataHelper(this);
                db.clearPengukuranMaster();
                tanggalToIdMap.clear();

                Calendar cal = Calendar.getInstance();
                int bulanIni = cal.get(Calendar.MONTH) + 1;
                int tahunIni = cal.get(Calendar.YEAR);

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    int id = obj.optInt("id", -1);
                    String tanggal = obj.optString("tanggal", "");
                    if (id == -1 || tanggal.isEmpty()) continue;
                    String[] parts = tanggal.split("-");
                    if (parts.length >= 2) {
                        int tahunData = Integer.parseInt(parts[0]);
                        int bulanData = Integer.parseInt(parts[1]);
                        if (tahunData == tahunIni && bulanData == bulanIni) {
                            tanggalToIdMap.put(tanggal, id);
                            tanggalList.add(tanggal);
                            db.insertPengukuranMaster(id, tanggal);
                        }
                    }
                }

                if (tanggalList.isEmpty()) {
                    tanggalList.add("Belum ada pengukuran bulan ini");
                    pengukuranId = -1;
                }

                final List<String> finalTanggalList = tanggalList;
                runOnUiThread(() -> {
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, finalTanggalList);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinnerPengukuran.setAdapter(adapter);
                    spinnerPengukuran.setSelection(0);
                });

            } catch (Exception e) {
                logError("syncPengukuranMaster", "Gagal sync pengukuran: " + e.getMessage());
                runOnUiThread(() -> {
                    showElegantToast("❌ Gagal ambil tanggal pengukuran: " + e.getMessage(), "error");
                    loadTanggalOffline();
                });
            } finally {
                if (conn != null) conn.disconnect();
                if (onDone != null) runOnUiThread(onDone);
            }
        }).start();
    }

    private void loadTanggalOffline() {
        try {
            OfflineDataHelper db = new OfflineDataHelper(this);
            List<Map<String,String>> rows = db.getPengukuranMaster();
            List<String> list = new ArrayList<>();
            tanggalToIdMap.clear();
            if (rows != null && !rows.isEmpty()) {
                for (Map<String,String> r : rows) {
                    String tanggal = r.get("tanggal");
                    String idStr = r.get("id");
                    if (tanggal != null && idStr != null) {
                        list.add(tanggal);
                        try {
                            tanggalToIdMap.put(tanggal, Integer.parseInt(idStr));
                        } catch (Exception ignored) {}
                    }
                }
            } else {
                list.add("Belum ada pengukuran (offline)");
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, list);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerPengukuran.setAdapter(adapter);
            spinnerPengukuran.setSelection(0);
        } catch (Exception e) {
            logError("loadTanggalOffline", "Error load offline master: " + e.getMessage());
        }
    }

    private void handleHitungSemua() {
        if (!isInternetAvailable()) {
            showElegantToast("Tidak ada koneksi internet. Tidak dapat menghitung data.", "error");
            return;
        }

        String sel = spinnerPengukuran.getSelectedItem() != null ? spinnerPengukuran.getSelectedItem().toString() : null;
        int id = -1;
        if (sel != null && tanggalToIdMap.containsKey(sel)) {
            id = tanggalToIdMap.get(sel);
        } else {
            id = prefs.getInt("pengukuran_id", -1);
        }

        if (id == -1) {
            showElegantToast("Pilih data pengukuran terlebih dahulu!", "warning");
            return;
        }

        final int finalId = id;
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Menghitung data...");
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(HITUNG_SEMUA_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(300_000);
                conn.setReadTimeout(300_000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");

                JSONObject json = new JSONObject();
                json.put("pengukuran_id", finalId);

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                InputStream is = (code == 200) ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject resp = new JSONObject(sb.toString());
                runOnUiThread(() -> {
                    pd.dismiss();
                    try {
                        String status = resp.optString("status", "");
                        JSONObject messages = resp.optJSONObject("messages");
                        JSONObject data = resp.optJSONObject("data");
                        String tanggal = resp.optString("tanggal", "-");

                        StringBuilder msgBuilder = new StringBuilder();
                        if (messages != null) {
                            Iterator<String> keys = messages.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                String value = messages.optString(key, "");
                                msgBuilder.append("• ").append(key).append(": ").append(value).append("\n");
                            }
                        }

                        String lookBurtInfo = "";
                        String statusKeterangan = "aman";
                        if (data != null) {
                            String rembBendungan = data.optString("rembesan_bendungan", "-");
                            String rembPerM = data.optString("rembesan_per_m", "-");
                            String ket = data.optString("keterangan", "-");
                            lookBurtInfo = "\n💧 Analisa Look Burt:\n"
                                    + "  - Rembesan Bendungan: " + rembBendungan + "\n"
                                    + "  - Rembesan per M: " + rembPerM + "\n"
                                    + "  - Keterangan: " + ket;

                            if (ket.toLowerCase().contains("bahaya")) {
                                statusKeterangan = "danger";
                            } else if (ket.toLowerCase().contains("peringatan") || ket.toLowerCase().contains("waspada")) {
                                statusKeterangan = "warning";
                            } else {
                                statusKeterangan = "success";
                            }
                        }

                        if ("success".equalsIgnoreCase(status)) {
                            showCalculationResultDialog(" Perhitungan Berhasil",
                                    "Semua perhitungan berhasil untuk tanggal " + tanggal + lookBurtInfo,
                                    statusKeterangan, tanggal);
                        } else if ("partial_error".equalsIgnoreCase(status)) {
                            showCalculationResultDialog("⚠️ Perhitungan Sebagian Berhasil",
                                    "Beberapa perhitungan gagal:\n\n" + msgBuilder.toString() + lookBurtInfo,
                                    "warning", tanggal);
                        } else if ("error".equalsIgnoreCase(status)) {
                            showElegantToast("❌ Gagal menghitung: " + resp.optString("message", "Terjadi kesalahan"), "error");
                        } else {
                            showElegantToast("ℹ️ Respon tidak dikenal dari server", "info");
                        }

                    } catch (Exception e) {
                        showElegantToast("Error parsing hasil: " + e.getMessage(), "error");
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    showElegantToast("Error saat menghitung: " + e.getMessage(), "error");
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void showCalculationResultDialog(String title, String message, String status, String tanggal) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_calculation_result, null);
        builder.setView(dialogView);

        TextView titleText = dialogView.findViewById(R.id.dialog_title);
        TextView messageText = dialogView.findViewById(R.id.dialog_message);
        TextView tanggalText = dialogView.findViewById(R.id.dialog_tanggal);
        ImageView iconView = dialogView.findViewById(R.id.dialog_icon);
        Button okButton = dialogView.findViewById(R.id.dialog_button_ok);
        LinearLayout headerLayout = dialogView.findViewById(R.id.dialog_header);

        int colorRes = getColorForStatus(status);
        int iconRes = getIconForStatus(status);

        titleText.setText(title);
        messageText.setText(message);
        tanggalText.setText("📅 Tanggal: " + tanggal);
        iconView.setImageResource(iconRes);

        headerLayout.setBackgroundColor(ContextCompat.getColor(this, colorRes));

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            headerLayout.setElevation(8f);
            okButton.setElevation(4f);
        }

        iconView.setAlpha(0f);
        iconView.animate().alpha(1f).setDuration(500).start();

        okButton.setBackgroundColor(ContextCompat.getColor(this, colorRes));
        okButton.setTextColor(Color.WHITE);

        String formattedMessage = formatMessageWithIcons(message);
        messageText.setText(formattedMessage);

        dialogView.setAlpha(0f);
        dialogView.setScaleX(0.8f);
        dialogView.setScaleY(0.8f);

        final AlertDialog dialog = builder.create();
        dialog.setCancelable(false);
        dialog.show();

        dialogView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .start();

        okButton.setOnClickListener(v -> {
            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).withEndAction(() -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }).start();

            dialogView.animate()
                    .alpha(0f)
                    .scaleX(0.8f)
                    .scaleY(0.8f)
                    .setDuration(200)
                    .withEndAction(dialog::dismiss)
                    .start();
        });
    }

    private String formatMessageWithIcons(String message) {
        String formatted = message
                .replace("Analisa Look Burt", "🔍 Analisa Look Burt")
                .replace("Rembesan Bendungan", "💧 Rembesan Bendungan")
                .replace("Rembesan per M", "📏 Rembesan per M")
                .replace("Keterangan:", "📋 Keterangan:")
                .replace("Berhasil", "✅ Berhasil")
                .replace("Gagal", "❌ Gagal")
                .replace("Aman", "🟢 Aman")
                .replace("Peringatan", "🟡 Peringatan")
                .replace("Bahaya", "🔴 Bahaya");
        return formatted;
    }
}
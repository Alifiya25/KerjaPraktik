package com.example.kerjapraktik;

import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.ScrollView;
import androidx.cardview.widget.CardView;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class InputDataActivity extends AppCompatActivity {

    // Modal pengukuran
    private CardView modalPengukuran;
    private View modalOverlay;
    private ImageButton btnCloseModal;
    private ScrollView mainContent;

    // Input modal
    private EditText modalInputTahun;
    private AutoCompleteTextView modalInputBulan, modalInputPeriode;
    private EditText modalInputTanggal;
    private Button modalBtnSubmitPengukuran;

    // Form utama
    private Spinner spinnerPengukuran;
    private Button btnPilihPengukuran, btnSubmitTmaWaduk, btnSubmitThomson, btnSubmitSR, btnHitungSemua;
    private EditText inputTmaWaduk, inputB3, inputB5;

    private Calendar calendar;
    private String tempId = null;
    private int pengukuranId = -1;

    private final int[] srKodeArray = {1, 40, 66, 68, 70, 79, 81, 83, 85, 92, 94, 96, 98, 100, 102, 104, 106};
    private final Map<Integer, Spinner> srKodeSpinners = new HashMap<>();

    private OfflineDataHelper offlineDb;
    private SharedPreferences syncPrefs;

    // API URL
    private static final String BASE_URL = "http://192.168.1.12/API_Android/public/rembesan/";
    private static final String INSERT_DATA_URL = BASE_URL + "input";
    private static final String CEK_DATA_URL = BASE_URL + "cek-data";
    private static final String GET_PENGUKURAN_URL = BASE_URL + "get_pengukuran";
    private static final String HITUNG_SEMUA_URL = BASE_URL + "Rumus-Rembesan";

    // Map untuk simpan pasangan tanggal → ID
    private final Map<String, Integer> pengukuranMap = new HashMap<>();

    // simpan list & adapter agar bisa refresh + notify
    private final List<String> tanggalList = new ArrayList<>();
    private ArrayAdapter<String> pengukuranAdapter;

    // ✅ AUTO SYNC VARIABLES
    private boolean isSyncInProgress = false;
    private Handler networkCheckHandler = new Handler();
    private Runnable networkCheckRunnable;
    private boolean lastOnlineStatus = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_input_data);

        // inisialisasi database offline + prefs
        offlineDb = new OfflineDataHelper(this);
        syncPrefs = getSharedPreferences("sync_prefs", MODE_PRIVATE);
        calendar = Calendar.getInstance();

        // init UI components dulu (important)
        spinnerPengukuran = findViewById(R.id.spinnerPengukuran);
        btnPilihPengukuran = findViewById(R.id.btnPilihPengukuran);

        initModalComponents();
        initFormComponents();
        setupSpinners();
        setupModalDropdowns();
        setupModalCalendar();

        // Siapkan adapter spinner (awal kosong, nanti diisi saat load)
        pengukuranAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, tanggalList);
        pengukuranAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPengukuran.setAdapter(pengukuranAdapter);

        // Ambil pengukuran_id dari SharedPreferences (jika ada)
        SharedPreferences prefs = getSharedPreferences("pengukuran", MODE_PRIVATE);
        pengukuranId = prefs.getInt("pengukuran_id", -1);

        // set listener spinner
        spinnerPengukuran.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = position >= 0 && position < tanggalList.size() ? tanggalList.get(position) : null;
                if (selected != null && pengukuranMap.containsKey(selected)) {
                    pengukuranId = pengukuranMap.get(selected);
                    getSharedPreferences("pengukuran", MODE_PRIVATE).edit().putInt("pengukuran_id", pengukuranId).apply();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // ✅ AUTO SYNC: Cek status internet dan mulai monitoring
        checkInternetAndShowToast();
        startNetworkMonitoring();

        // sekarang sinkron pengukuran master (dipanggil setelah adapter ready & UI inisialisasi)
        syncPengukuranMaster();

        // tombol pilih pengukuran
        btnPilihPengukuran.setOnClickListener(v -> {
            Object sel = spinnerPengukuran.getSelectedItem();
            if (sel == null) {
                showElegantToast("Pilih tanggal pengukuran dulu.", "warning");
                return;
            }

            String selected = sel.toString();
            if (pengukuranMap.containsKey(selected)) {
                pengukuranId = pengukuranMap.get(selected);
                getSharedPreferences("pengukuran", MODE_PRIVATE)
                        .edit()
                        .putInt("pengukuran_id", pengukuranId)
                        .apply();

                showElegantToast("Tanggal terpilih: " + selected, "success");

            } else {
                showElegantToast("Tanggal tidak dikenali, coba sinkron ulang.", "error");
            }
        });

        // set click listeners (pastikan sudah di-init di initFormComponents)
        if (btnSubmitTmaWaduk != null) btnSubmitTmaWaduk.setOnClickListener(v -> handleTmaWaduk());
        if (btnSubmitThomson != null) btnSubmitThomson.setOnClickListener(v -> handleThomsonHP1());
        if (btnSubmitSR != null) btnSubmitSR.setOnClickListener(v -> handleSR());


        // tampilkan modal jika komponen ada
        if (modalPengukuran != null && modalOverlay != null && mainContent != null) {
            showModal();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ✅ AUTO SYNC: Cek status internet setiap resume
        checkInternetAndShowToast();

        // jalankan sinkronisasi offline -> online saat internet tersedia
        if (isInternetAvailable()) {
            if (offlineDb.hasUnsyncedData()) {
                syncAllOfflineData(() -> {
                    if (!isAlreadySynced()) {
                        showElegantToast("Sinkronisasi data offline selesai", "success");
                        markAsSynced();
                    }
                });
            } else {
                // jika tidak ada data unsynced, masih bisa update master
                syncPengukuranMaster();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // ✅ AUTO SYNC: Hentikan monitoring saat pause
        stopNetworkMonitoring();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ✅ AUTO SYNC: Hentikan monitoring saat destroy
        stopNetworkMonitoring();
        logInfo("onDestroy", "Activity destroyed");
    }

    // ✅ AUTO SYNC METHODS
    private void startNetworkMonitoring() {
        networkCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkInternetAndShowToast();
                networkCheckHandler.postDelayed(this, 5000); // Cek setiap 5 detik
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

    private void syncAllOfflineDataAuto(Runnable onComplete) {
        logInfo("syncAllOfflineDataAuto", "Starting auto offline sync...");

        int offlineCount = getOfflineDataCount();
        if (offlineCount == 0) {
            logInfo("syncAllOfflineDataAuto", "No offline data to sync");
            if (onComplete != null) onComplete.run();
            return;
        }

        logInfo("syncAllOfflineDataAuto", "Auto-syncing " + offlineCount + " offline rows");

        syncDataSerialAuto("pengukuran", () ->
                syncDataSerialAuto("thomson", () ->
                        syncDataSerialAuto("sr", () ->
                                syncDataSerialAuto("bocoran", () -> {
                                    logInfo("syncAllOfflineDataAuto", "All auto sync completed");
                                    showElegantToast("✅ " + offlineCount + " data terkirim", "success");
                                    if (onComplete != null) onComplete.run();
                                }))));
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

        logInfo("syncDataSerialAuto", "Auto-syncing " + list.size() + " rows for " + tableName);
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
                    URL url = new URL(INSERT_DATA_URL);
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

    /** Cek apakah sudah pernah sinkron hari ini */
    private boolean isAlreadySynced() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String lastSyncDate = prefs.getString("last_sync_date", "");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        return today.equals(lastSyncDate);
    }

    /** Tandai sudah sinkron hari ini */
    private void markAsSynced() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        prefs.edit().putString("last_sync_date", today).apply();
    }

    private void initModalComponents() {
        modalPengukuran = findViewById(R.id.modalPengukuran);
        modalOverlay = findViewById(R.id.modalOverlay);
        btnCloseModal = findViewById(R.id.btnCloseModal);
        mainContent = findViewById(R.id.mainContent);

        modalInputTahun = findViewById(R.id.modalInputTahun);
        modalInputBulan = findViewById(R.id.modalInputBulan);
        modalInputPeriode = findViewById(R.id.modalInputPeriode);
        modalInputTanggal = findViewById(R.id.modalInputTanggal);
        modalBtnSubmitPengukuran = findViewById(R.id.modalBtnSubmitPengukuran);

        if (btnCloseModal != null) btnCloseModal.setOnClickListener(v -> hideModal());
        if (modalOverlay != null) modalOverlay.setOnClickListener(v -> hideModal());
        if (modalBtnSubmitPengukuran != null) modalBtnSubmitPengukuran.setOnClickListener(v -> handleModalPengukuran());
    }

    private void initFormComponents() {
        inputTmaWaduk = findViewById(R.id.inputTmaWaduk);
        inputB3 = findViewById(R.id.inputB3);
        inputB5 = findViewById(R.id.inputB5);

        btnSubmitTmaWaduk = findViewById(R.id.btnSubmitTmaWaduk);
        btnSubmitThomson = findViewById(R.id.btnSubmitThomson);
        btnSubmitSR = findViewById(R.id.btnSubmitSR);
        btnHitungSemua = findViewById(R.id.btnSimpanDanHitung);

        // inisialisasi SR spinners secara defensif
        for (int kode : srKodeArray) {
            try {
                int resId = getResources().getIdentifier("sr_" + kode + "_kode", "id", getPackageName());
                if (resId != 0) {
                    Spinner spinner = findViewById(resId);
                    if (spinner != null) {
                        srKodeSpinners.put(kode, spinner);
                    }
                }
            } catch (Exception e) {
                Log.w("INIT_FORM", "SR spinner tidak ditemukan untuk kode " + kode);
            }
        }
    }

    private void setupSpinners() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.kode_options,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        for (int kode : srKodeArray) {
            Spinner s = srKodeSpinners.get(kode);
            if (s != null) s.setAdapter(adapter);
        }
    }

    private void setupModalDropdowns() {
        try {
            String[] bulanArray = getResources().getStringArray(R.array.bulan_options);
            ArrayAdapter<String> bulanAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, bulanArray);
            if (modalInputBulan != null) {
                modalInputBulan.setAdapter(bulanAdapter);
                modalInputBulan.setOnClickListener(v -> modalInputBulan.showDropDown());
            }

            String[] periodeArray = getResources().getStringArray(R.array.periode_options);
            ArrayAdapter<String> periodeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, periodeArray);
            if (modalInputPeriode != null) {
                modalInputPeriode.setAdapter(periodeAdapter);
                modalInputPeriode.setOnClickListener(v -> modalInputPeriode.showDropDown());
            }
        } catch (Exception e) {
            Log.w("SETUP_MODAL", "Gagal setup dropdown: " + e.getMessage());
        }
    }

    private void setupModalCalendar() {
        if (modalInputTanggal != null) {
            modalInputTanggal.setOnClickListener(v -> showModalDatePickerDialog());
            try {
                TextInputLayout tanggalLayout = (TextInputLayout) modalInputTanggal.getParent().getParent();
                if (tanggalLayout != null) {
                    tanggalLayout.setEndIconDrawable(R.drawable.ic_calendar);
                    tanggalLayout.setEndIconOnClickListener(v -> showModalDatePickerDialog());
                }
            } catch (Exception ignored) {}
        }
    }

    private void showModalDatePickerDialog() {
        DatePickerDialog datePickerDialog = new DatePickerDialog(
                this,
                (view, selectedYear, selectedMonth, selectedDay) -> {
                    // update calendar and format tanggal (yyyy-MM-dd)
                    calendar.set(Calendar.YEAR, selectedYear);
                    calendar.set(Calendar.MONTH, selectedMonth);
                    calendar.set(Calendar.DAY_OF_MONTH, selectedDay);
                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                    if (modalInputTanggal != null) modalInputTanggal.setText(dateFormat.format(calendar.getTime()));

                    // set tahun otomatis
                    if (modalInputTahun != null) modalInputTahun.setText(String.valueOf(selectedYear));

                    // set bulan otomatis (gunakan nama dari resources bulan_options)
                    try {
                        String[] bulanNama = getResources().getStringArray(R.array.bulan_options);
                        if (modalInputBulan != null) {
                            if (selectedMonth >= 0 && selectedMonth < bulanNama.length) {
                                modalInputBulan.setText(bulanNama[selectedMonth]);
                            } else {
                                modalInputBulan.setText(String.format(Locale.getDefault(), "%02d", (selectedMonth + 1)));
                            }
                        }
                    } catch (Exception ignored) {}

                    // hitung triwulan otomatis berdasarkan bulan (selectedMonth: 0..11)
                    String triwulan;
                    if (selectedMonth <= 2) { // Jan-Mar
                        triwulan = "TW-1";
                    } else if (selectedMonth <= 5) { // Apr-Jun
                        triwulan = "TW-2";
                    } else if (selectedMonth <= 8) { // Jul-Sep
                        triwulan = "TW-3";
                    } else { // Oct-Dec
                        triwulan = "TW-4";
                    }
                    // set di modalInputPeriode (AutoCompleteTextView) agar user masih bisa ubah manual
                    if (modalInputPeriode != null) {
                        // setText dengan filter = false agar tidak memicu filtering dropdown
                        try {
                            // AutoCompleteTextView punya setText(CharSequence, boolean)
                            modalInputPeriode.setText(triwulan, false);
                        } catch (Exception e) {
                            // fallback ke setText biasa
                            modalInputPeriode.setText(triwulan);
                        }
                    }
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        );
        datePickerDialog.show();
    }

    private void showModal() {
        if (modalPengukuran == null || modalOverlay == null || mainContent == null) return;
        modalPengukuran.setVisibility(View.VISIBLE);
        modalOverlay.setVisibility(View.VISIBLE);
        mainContent.setAlpha(0.5f);
        mainContent.setEnabled(false);
    }

    private void hideModal() {
        if (modalPengukuran == null || modalOverlay == null || mainContent == null) return;
        modalPengukuran.setVisibility(View.GONE);
        modalOverlay.setVisibility(View.GONE);
        mainContent.setAlpha(1.0f);
        mainContent.setEnabled(true);
    }

    /** Load daftar pengukuran ke spinner (memperbarui list & notify adapter) **/
    private void loadPengukuranList() {
        if (!isInternetAvailable()) {
            showElegantToast("Offline: Tidak bisa ambil data pengukuran", "warning");
            return;
        }

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(GET_PENGUKURAN_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(7000);

                int code = conn.getResponseCode();
                InputStream is = (code == 200) ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject response = new JSONObject(sb.toString());
                JSONArray dataArray = response.optJSONArray("data");
                if (dataArray == null) dataArray = new JSONArray();

                pengukuranMap.clear();
                List<String> newTanggalList = new ArrayList<>();
                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject obj = dataArray.getJSONObject(i);
                    int id = obj.optInt("id", -1);
                    String tanggal = obj.optString("tanggal", "");
                    if (!tanggal.isEmpty()) {
                        newTanggalList.add(tanggal);
                        pengukuranMap.put(tanggal, id);
                    }
                }

                runOnUiThread(() -> {
                    tanggalList.clear();
                    tanggalList.addAll(newTanggalList);
                    if (pengukuranAdapter != null) pengukuranAdapter.notifyDataSetChanged();
                });

            } catch (Exception e) {
                Log.e("LOAD_PENGUKURAN", "error", e);
                runOnUiThread(() -> showElegantToast("Gagal ambil data pengukuran: " + e.getMessage(), "error"));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /** Fetch ulang daftar pengukuran setelah tambah baru */
    private void refreshPengukuranList() {
        loadPengukuranList();
    }

    /** Simpan pengukuran baru dari modal **/
    private void handleModalPengukuran() {
        if (modalInputTahun == null || modalInputBulan == null || modalInputPeriode == null || modalInputTanggal == null) {
            showElegantToast("Form modal belum siap", "error");
            return;
        }

        Map<String, String> data = new HashMap<>();
        data.put("mode", "pengukuran");
        data.put("tahun", modalInputTahun.getText().toString().trim());
        data.put("bulan", modalInputBulan.getText().toString().trim());
        data.put("periode", modalInputPeriode.getText().toString().trim());
        data.put("tanggal", modalInputTanggal.getText().toString().trim());

        if (data.get("tahun").isEmpty() || data.get("bulan").isEmpty() || data.get("periode").isEmpty() || data.get("tanggal").isEmpty()) {
            showElegantToast("Harap isi semua field yang wajib", "warning");
            return;
        }

        if (isInternetAvailable()) {
            sendToServer(data, "pengukuran", true);
        } else {
            // buat tempId jika belum ada
            tempId = "local_" + System.currentTimeMillis();
            data.put("temp_id", tempId);
            saveOffline("pengukuran", tempId, data);
            hideModal();
        }
    }

    private void handleTmaWaduk() {
        Map<String, String> data = new HashMap<>();
        data.put("mode", "pengukuran");
        data.put("tma_waduk", inputTmaWaduk != null ? inputTmaWaduk.getText().toString().trim() : "");

        String selected = spinnerPengukuran != null && spinnerPengukuran.getSelectedItem() != null
                ? spinnerPengukuran.getSelectedItem().toString() : null;

        if (selected != null && pengukuranMap.containsKey(selected)) {
            pengukuranId = pengukuranMap.get(selected);
            data.put("pengukuran_id", String.valueOf(pengukuranId));
        } else {
            pengukuranId = getSharedPreferences("pengukuran", MODE_PRIVATE).getInt("pengukuran_id", -1);
            if (pengukuranId != -1) {
                data.put("pengukuran_id", String.valueOf(pengukuranId));
            } else {
                // jika belum pilih pengukuran, buat tempId dan simpan offline
                if (tempId == null) tempId = "local_" + System.currentTimeMillis();
                data.put("temp_id", tempId);
            }
        }

        if (isInternetAvailable() && data.containsKey("pengukuran_id")) {
            cekDanSimpanData("tma_waduk", data, false);
        } else {
            saveOffline("tma_waduk", data.getOrDefault("temp_id", "local_" + System.currentTimeMillis()), data);
        }
    }

    // METHOD BARU: Handle Thomson khusus untuk HP 1 (hanya B3, B5)
    private void handleThomsonHP1() {
        Map<String, String> data = new HashMap<>();
        data.put("mode", "thomson");

        // HP 1 hanya mengirim B3 dan B5 (Stilling Basin)
        data.put("b3", inputB3 != null ? inputB3.getText().toString().trim() : "");
        data.put("b5", inputB5 != null ? inputB5.getText().toString().trim() : "");

        // A1 R, A1 L, B1 dikosongkan (akan diisi oleh HP 2/Gallery)
        data.put("a1_r", "");
        data.put("a1_l", "");
        data.put("b1", "");

        String selected = spinnerPengukuran != null && spinnerPengukuran.getSelectedItem() != null
                ? spinnerPengukuran.getSelectedItem().toString() : null;

        if (selected != null && pengukuranMap.containsKey(selected)) {
            data.put("pengukuran_id", String.valueOf(pengukuranMap.get(selected)));
        } else {
            int prefId = getSharedPreferences("pengukuran", MODE_PRIVATE).getInt("pengukuran_id", -1);
            if (prefId != -1) data.put("pengukuran_id", String.valueOf(prefId));
            else {
                if (tempId == null) tempId = "local_" + System.currentTimeMillis();
                data.put("temp_id", tempId);
            }
        }

        if (isInternetAvailable() && data.containsKey("pengukuran_id")) cekDanSimpanData("thomson", data, false);
        else saveOffline("thomson", data.getOrDefault("temp_id", "local_" + System.currentTimeMillis()), data);
    }

    // METHOD YANG DIPERBAIKI: Handle SR dengan dialog konfirmasi
    private void handleSR() {
        Map<String, String> data = new HashMap<>();
        data.put("mode", "sr");

        // Kumpulkan data SR dan identifikasi yang terisi/kosong
        List<String> filledFields = new ArrayList<>();
        List<String> emptyFields = new ArrayList<>();

        for (int kode : srKodeArray) {
            Spinner spinner = srKodeSpinners.get(kode);
            String kodeValue = (spinner != null && spinner.getSelectedItem() != null) ?
                    spinner.getSelectedItem().toString() : "";
            String nilaiValue = getTextFromId("sr_" + kode + "_nilai");

            data.put("sr_" + kode + "_kode", kodeValue);
            data.put("sr_" + kode + "_nilai", nilaiValue);

            // Cek apakah field terisi
            boolean isKodeFilled = !kodeValue.isEmpty() && !kodeValue.equals("Pilih Kode");
            boolean isNilaiFilled = !nilaiValue.isEmpty();

            if (isKodeFilled || isNilaiFilled) {
                StringBuilder fieldInfo = new StringBuilder("SR " + kode + ": ");
                if (isKodeFilled) fieldInfo.append("Kode=").append(kodeValue);
                if (isKodeFilled && isNilaiFilled) fieldInfo.append(", ");
                if (isNilaiFilled) fieldInfo.append("Nilai=").append(nilaiValue);
                filledFields.add(fieldInfo.toString());
            } else {
                emptyFields.add("SR " + kode + " (Kode & Nilai)");
            }
        }

        String selected = spinnerPengukuran != null && spinnerPengukuran.getSelectedItem() != null
                ? spinnerPengukuran.getSelectedItem().toString() : null;

        if (selected != null && pengukuranMap.containsKey(selected)) {
            data.put("pengukuran_id", String.valueOf(pengukuranMap.get(selected)));
        } else {
            int prefId = getSharedPreferences("pengukuran", MODE_PRIVATE).getInt("pengukuran_id", -1);
            if (prefId != -1) data.put("pengukuran_id", String.valueOf(prefId));
            else {
                if (tempId == null) tempId = "local_" + System.currentTimeMillis();
                data.put("temp_id", tempId);
            }
        }

        // Tampilkan dialog konfirmasi sebelum kirim
        showSRConfirmationDialog(filledFields, emptyFields, data);
    }

    // METHOD BARU: Dialog konfirmasi SR yang diperbaiki
    private void showSRConfirmationDialog(List<String> filledFields, List<String> emptyFields, Map<String, String> data) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.CustomAlertDialog);
        View view = getLayoutInflater().inflate(R.layout.dialog_sr_confirmation, null);

        TextView filledTextView = view.findViewById(R.id.filled_fields);
        TextView emptyTextView = view.findViewById(R.id.empty_fields);
        TextView filledCount = view.findViewById(R.id.filled_count);
        TextView emptyCount = view.findViewById(R.id.empty_count);
        Button btnYes = view.findViewById(R.id.btn_yes);
        Button btnNo = view.findViewById(R.id.btn_no);

        // Set counter
        filledCount.setText(String.valueOf(filledFields.size()));
        emptyCount.setText(String.valueOf(emptyFields.size()));

        // Format text untuk field yang terisi
        if (filledFields.isEmpty()) {
            filledTextView.setText("Tidak ada data yang diisi");
        } else {
            StringBuilder filledText = new StringBuilder();
            for (String field : filledFields) {
                filledText.append("• ").append(field).append("\n");
            }
            filledTextView.setText(filledText.toString());
        }

        // Format text untuk field yang kosong
        if (emptyFields.isEmpty()) {
            emptyTextView.setText("Semua data telah diisi");
        } else {
            StringBuilder emptyText = new StringBuilder();
            for (String field : emptyFields) {
                emptyText.append("• ").append(field).append("\n");
            }
            emptyTextView.setText(emptyText.toString());
        }

        AlertDialog dialog = builder.setView(view).create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.setCancelable(true);
        dialog.show();

        Window window = dialog.getWindow();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); // opsional: hilangkan efek gelap belakang
        }


        btnYes.setOnClickListener(v -> {
            dialog.dismiss();
            data.put("confirm", "yes"); // ✅ tambahkan ini agar server tahu user sudah setuju

            if (isInternetAvailable() && data.containsKey("pengukuran_id")) {
                sendToServerWithSRConfirm(data, "sr", false);
            } else {
                saveOffline("sr", data.getOrDefault("temp_id", "local_" + System.currentTimeMillis()), data);
            }
        });


        btnNo.setOnClickListener(v -> dialog.dismiss());
    }

    // METHOD BARU: Send to server dengan handle konfirmasi SR
    private void sendToServerWithSRConfirm(Map<String, String> dataMap, String table, boolean isPengukuran) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(INSERT_DATA_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(9000);
                conn.setReadTimeout(9000);

                JSONObject jsonData = new JSONObject();
                for (Map.Entry<String, String> entry : dataMap.entrySet()) {
                    jsonData.put(entry.getKey(), entry.getValue());
                }

                OutputStream os = conn.getOutputStream();
                os.write(jsonData.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                InputStream is = (responseCode == 200) ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject response = new JSONObject(sb.toString());
                String status = response.optString("status", "");
                String message = response.optString("message", "");

                // Handle khusus untuk konfirmasi SR dari server
                if ("sr".equals(table) && "confirm".equals(status)) {
                    JSONArray filledArray = response.optJSONArray("filled");
                    JSONArray emptyArray = response.optJSONArray("empty");

                    List<String> filledFields = new ArrayList<>();
                    List<String> emptyFields = new ArrayList<>();

                    if (filledArray != null) {
                        for (int i = 0; i < filledArray.length(); i++) {
                            filledFields.add(filledArray.getString(i));
                        }
                    }

                    if (emptyArray != null) {
                        for (int i = 0; i < emptyArray.length(); i++) {
                            emptyFields.add(emptyArray.getString(i));
                        }
                    }

                    runOnUiThread(() -> showSRConfirmationDialog(filledFields, emptyFields, dataMap));
                    return;
                }

                if (isPengukuran && response.has("pengukuran_id")) {
                    pengukuranId = response.optInt("pengukuran_id", -1);
                    SharedPreferences prefs = getSharedPreferences("pengukuran", MODE_PRIVATE);
                    prefs.edit().putInt("pengukuran_id", pengukuranId).apply();
                    tempId = null;
                }

                runOnUiThread(() -> {
                    switch (status.toLowerCase()) {
                        case "success":
                            showElegantToast(message, "success");
                            if (isPengukuran) {
                                hideModal();
                                refreshPengukuranList();
                            }
                            break;

                        case "confirm":
                            // Handle konfirmasi umum (bukan SR)
                            new AlertDialog.Builder(InputDataActivity.this)
                                    .setTitle("Konfirmasi Penyimpanan")
                                    .setMessage(message)
                                    .setPositiveButton("Ya", (dialog, which) -> {
                                        dataMap.put("confirm", "yes");
                                        sendToServerWithSRConfirm(dataMap, table, isPengukuran);
                                    })
                                    .setNegativeButton("Batal", null)
                                    .show();
                            break;

                        case "warning":
                            showElegantToast(message, "warning");
                            break;

                        case "error":
                        default:
                            showElegantToast("Error: " + message, "error");
                            break;
                    }
                });

            } catch (Exception e) {
                Log.e("SEND", "error", e);
                runOnUiThread(() -> showElegantToast("Gagal kirim: " + e.getMessage() + ". Data akan disimpan offline.", "warning"));
                try {
                    if (tempId == null) tempId = "local_" + System.currentTimeMillis();
                    saveOffline(table, tempId, dataMap);
                } catch (Exception ex) {
                    Log.e("SEND_SAVE_OFFLINE", "Gagal simpan offline", ex);
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
    // METHOD BARU: Format pesan dengan icon dan styling
    private String formatMessageWithIcons(String message) {
        // Ganti keyword dengan icon
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

    // Method untuk toast elegan dengan improvement
    private void showElegantToast(String message, String type) {
        runOnUiThread(() -> {
            LayoutInflater inflater = getLayoutInflater();
            View layout = inflater.inflate(R.layout.toast_custom,
                    findViewById(R.id.custom_toast_container));

            TextView text = layout.findViewById(R.id.custom_toast_text);
            ImageView icon = layout.findViewById(R.id.custom_toast_icon);
            CardView card = layout.findViewById(R.id.custom_toast_card);

            // PERBAIKAN: Format pesan toast
            String formattedMessage = formatMessageWithIcons(message);
            text.setText(formattedMessage);

            // Set warna dan icon berdasarkan type
            int colorRes = getColorForStatus(type);
            int iconRes = getIconForStatus(type);

            card.setCardBackgroundColor(ContextCompat.getColor(this, colorRes));
            icon.setImageResource(iconRes);

            // PERBAIKAN: Animasi toast
            card.setAlpha(0f);
            card.setScaleX(0.8f);
            card.setScaleY(0.8f);

            Toast toast = new Toast(getApplicationContext());
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setView(layout);
            toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 150);

            toast.show();

            // Animasi toast masuk
            card.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .start();
        });
    }

    // Helper method untuk mendapatkan warna berdasarkan status
    private int getColorForStatus(String status) {
        switch (status.toLowerCase()) {
            case "success":
            case "aman":
                return R.color.pln_success; // Hijau PLN
            case "warning":
            case "peringatan":
                return R.color.pln_warning; // Kuning/Oranye
            case "error":
            case "danger":
            case "bahaya":
                return R.color.pln_danger; // Merah
            case "info":
            default:
                return R.color.pln_info; // Biru PLN
        }
    }

    // Helper method untuk mendapatkan icon berdasarkan status
    private int getIconForStatus(String status) {
        switch (status.toLowerCase()) {
            case "success":
            case "aman":
                return R.drawable.ic_success;
            case "warning":
            case "peringatan":
                return R.drawable.ic_warning;
            case "error":
            case "danger":
            case "bahaya":
                return R.drawable.ic_danger;
            case "info":
            default:
                return R.drawable.ic_info;
        }
    }

    private void cekDanSimpanData(String table, Map<String, String> dataMap, boolean isPengukuran) {
        if (isPengukuran) {
            sendToServerWithSRConfirm(dataMap, table, isPengukuran);
            return;
        }

        new Thread(() -> {
            HttpURLConnection connCek = null;
            try {
                String pengukuranIdParam = dataMap.get("pengukuran_id");
                if (pengukuranIdParam == null) {
                    // langsung simpan jika tidak ada pengukuran_id (mungkin offline)
                    sendToServerWithSRConfirm(dataMap, table, isPengukuran);
                    return;
                }

                URL urlCek = new URL(CEK_DATA_URL + "?pengukuran_id=" + URLEncoder.encode(pengukuranIdParam, "UTF-8"));
                connCek = (HttpURLConnection) urlCek.openConnection();
                connCek.setRequestMethod("GET");
                connCek.setRequestProperty("Accept", "application/json");
                connCek.setConnectTimeout(7000);
                connCek.setReadTimeout(7000);

                int code = connCek.getResponseCode();
                InputStream is = (code == 200) ? connCek.getInputStream() : connCek.getErrorStream();
                BufferedReader readerCek = new BufferedReader(new InputStreamReader(is));
                StringBuilder sbCek = new StringBuilder();
                String lineCek;
                while ((lineCek = readerCek.readLine()) != null) sbCek.append(lineCek);
                readerCek.close();

                JSONObject responseCek = new JSONObject(sbCek.toString());
                JSONObject data = responseCek.has("data") ? responseCek.getJSONObject("data") : responseCek;

                boolean dataSudahAda = false;
                boolean dataLengkap = false;

                switch (table) {
                    case "tma_waduk":
                    case "pengukuran":
                        dataSudahAda = data.optBoolean("tma_waduk_ada", false);
                        break;
                    case "thomson":
                        dataSudahAda = data.optBoolean("thomson_ada", false);
                        dataLengkap = data.optBoolean("thomson_lengkap", false);
                        break;
                    case "sr":
                        dataSudahAda = data.optBoolean("sr_ada", false);
                        break;
                }

                if (dataSudahAda) {
                    if ("thomson".equals(table) && !dataLengkap) {
                        sendToServerWithSRConfirm(dataMap, table, isPengukuran);
                    } else {
                        runOnUiThread(() -> showElegantToast("Data " + table + " sudah lengkap untuk pengukuran ini!", "info"));
                    }
                    return;
                }

                sendToServerWithSRConfirm(dataMap, table, isPengukuran);

            } catch (Exception e) {
                Log.e("CEK_SAVE", "error", e);
                runOnUiThread(() -> showElegantToast("Gagal cek data, mencoba simpan langsung...", "warning"));
                sendToServerWithSRConfirm(dataMap, table, isPengukuran);
            } finally {
                if (connCek != null) connCek.disconnect();
            }
        }).start();
    }

    private void syncAllOfflineData(Runnable onComplete) {
        // gunakan hasUnsyncedData agar lebih efisien
        boolean adaData = offlineDb.hasUnsyncedData();

        if (!adaData) {
            if (onComplete != null) onComplete.run();
            return;
        }

        syncDataSerial("pengukuran", () ->
                syncDataSerial("tma_waduk", () ->
                        syncDataSerial("thomson", () ->
                                syncDataSerial("sr", onComplete)
                        )
                )
        );
    }

    private void syncDataSerial(String tableName, Runnable next) {
        // gunakan hanya unsynced data
        List<Map<String, String>> dataList = offlineDb.getUnsyncedData(tableName);
        if (dataList.isEmpty()) {
            if (next != null) next.run();
            return;
        }
        syncDataItem(tableName, dataList, 0, next);
    }

    private void syncDataItem(String tableName, List<Map<String, String>> dataList, int index, Runnable onFinish) {
        if (index >= dataList.size()) {
            if (onFinish != null) onFinish.run();
            return;
        }

        Map<String, String> item = dataList.get(index);
        String tempId = item.get("temp_id");
        String jsonStr = item.get("json");

        try {
            JSONObject jsonData = new JSONObject(jsonStr);

            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(INSERT_DATA_URL);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setConnectTimeout(9000);
                    conn.setReadTimeout(9000);

                    OutputStream os = conn.getOutputStream();
                    os.write(jsonData.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    int responseCode = conn.getResponseCode();
                    InputStream is = (responseCode == 200) ? conn.getInputStream() : conn.getErrorStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                    reader.close();

                    if (responseCode == 200) {
                        JSONObject jsonResponse = new JSONObject(response.toString());
                        if ("success".equalsIgnoreCase(jsonResponse.optString("status"))) {
                            offlineDb.deleteByTempId(tableName, tempId);
                            Log.d("SYNC", "Data " + tableName + " tempId=" + tempId + " berhasil disinkronisasi");
                        } else {
                            Log.e("SYNC", "Gagal sinkron data " + tableName + " tempId=" + tempId + ": " + jsonResponse.optString("message"));
                        }
                    } else {
                        Log.e("SYNC", "Response error code " + responseCode + " untuk data " + tableName + " tempId=" + tempId);
                    }

                } catch (Exception e) {
                    Log.e("SYNC", "Error sync " + tableName + " tempId=" + tempId, e);
                } finally {
                    if (conn != null) conn.disconnect();
                }

                runOnUiThread(() -> syncDataItem(tableName, dataList, index + 1, onFinish));
            }).start();

        } catch (Exception e) {
            Log.e("SYNC", "JSON parse error untuk data " + tableName + " tempId=" + tempId, e);
            runOnUiThread(() -> syncDataItem(tableName, dataList, index + 1, onFinish));
        }
    }

    private void sendToServer(Map<String, String> dataMap, String table, boolean isPengukuran) {
        sendToServerWithSRConfirm(dataMap, table, isPengukuran);
    }

    private void saveOffline(String table, String tempId, Map<String, String> data) {
        try {
            if (tempId == null) tempId = "local_" + System.currentTimeMillis();
            JSONObject json = new JSONObject(data);
            offlineDb.insertData(table, tempId, json.toString());
            showElegantToast("Tidak ada internet. Data disimpan offline.", "warning");
            syncPrefs.edit().putBoolean("toast_shown", false).apply();
        } catch (Exception e) {
            Log.e("SAVE_OFFLINE", "error", e);
            showElegantToast("Gagal simpan offline: " + e.getMessage(), "error");
        }
    }

    private String getTextFromId(String idName) {
        try {
            int id = getResources().getIdentifier(idName, "id", getPackageName());
            if (id == 0) return "";
            EditText et = findViewById(id);
            return et != null ? et.getText().toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isInternetAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo active = cm != null ? cm.getActiveNetworkInfo() : null;
            return active != null && active.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private void syncPengukuranMaster() {
        syncPengukuranMaster(null);
    }

    private void syncPengukuranMaster(Runnable onDone) {
        if (!isInternetAvailable()) {
            showElegantToast("Tidak ada koneksi internet. Sinkronisasi gagal.", "warning");
            if (onDone != null) onDone.run();
            return;
        }

        Calendar cal = Calendar.getInstance();
        int bulanSekarang = cal.get(Calendar.MONTH) + 1;
        int tahunSekarang = cal.get(Calendar.YEAR);

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(GET_PENGUKURAN_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(7000);

                int code = conn.getResponseCode();
                InputStream is = (code == 200) ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject response = new JSONObject(sb.toString());
                JSONArray dataArray = response.optJSONArray("data");
                if (dataArray == null) dataArray = new JSONArray();

                pengukuranMap.clear();
                List<String> bulanIniList = new ArrayList<>();

                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject obj = dataArray.getJSONObject(i);
                    String tanggal = obj.optString("tanggal", "");
                    int id = obj.optInt("id", -1);
                    if (tanggal.isEmpty()) continue;

                    String[] parts = tanggal.split("-");
                    if (parts.length < 2) continue;
                    int tahun = Integer.parseInt(parts[0]);
                    int bulan = Integer.parseInt(parts[1]);

                    if (tahun == tahunSekarang && bulan == bulanSekarang) {
                        bulanIniList.add(tanggal);
                        pengukuranMap.put(tanggal, id);
                    }
                }

                runOnUiThread(() -> {
                    tanggalList.clear();
                    tanggalList.addAll(bulanIniList);
                    if (pengukuranAdapter != null) pengukuranAdapter.notifyDataSetChanged();

                    if (tanggalList.isEmpty()) {
                        showElegantToast("Tidak ada data pengukuran untuk bulan ini.", "info");
                    } else {
                        spinnerPengukuran.setSelection(0);
                        pengukuranId = pengukuranMap.get(tanggalList.get(0));
                        getSharedPreferences("pengukuran", MODE_PRIVATE)
                                .edit().putInt("pengukuran_id", pengukuranId).apply();
                    }
                    if (onDone != null) onDone.run();
                });

            } catch (Exception e) {
                Log.e("SYNC_MASTER", "Error syncPengukuranMaster", e);
                runOnUiThread(() -> {
                    showElegantToast("Sinkronisasi gagal: " + e.getMessage(), "error");
                    if (onDone != null) onDone.run();
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void loadTanggalOffline() {
        try {
            OfflineDataHelper db = new OfflineDataHelper(this);
            List<Map<String,String>> rows = db.getPengukuranMaster();
            List<String> list = new ArrayList<>();
            pengukuranMap.clear();
            if (rows != null && !rows.isEmpty()) {
                for (Map<String,String> r : rows) {
                    String tanggal = r.get("tanggal");
                    String idStr = r.get("id");
                    if (tanggal != null && idStr != null) {
                        list.add(tanggal);
                        try {
                            pengukuranMap.put(tanggal, Integer.parseInt(idStr));
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

    // ✅ LOGGING METHODS
    private void logInfo(String where, String msg) {
        Log.i("InputDataActivity", "[INFO][" + where + "] " + msg);
    }
    private void logWarn(String where, String msg) {
        Log.w("InputDataActivity", "[WARN][" + where + "] " + msg);
    }
    private void logError(String where, String msg) {
        Log.e("InputDataActivity", "[ERROR][" + where + "] " + msg);
    }

    // METHOD LAMA (untuk kompatibilitas)
    private void showSRConfirmationDialog(Runnable onConfirm) {
        // Method ini tetap dipertahankan untuk kompatibilitas
        if (onConfirm != null) onConfirm.run();
    }
}
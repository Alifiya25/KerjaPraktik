package com.example.kerjapraktik;

import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends AppCompatActivity {

    private Spinner spinnerPengukuran;
    private Button btnTampilkanData;
    private Button btnExportDB;

    // CardView
    private CardView cardTma, cardThomson, cardSR, cardBocoran;

    // TMA
    private TextView tvTmaValue;

    // Thomson
    private TextView tvA1R, tvA1L, tvB1, tvB3, tvB5;

    // SR
    private LinearLayout containerSR;

    // Bocoran
    private LinearLayout containerBocoran;

    private RequestQueue requestQueue;
    private ArrayList<Integer> pengukuranIds = new ArrayList<>();
    private ArrayList<String> pengukuranLabels = new ArrayList<>();

    private static final String BASE_URL = "http://192.168.1.12/API_Android/public/api/rembesan/";

    private DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        dbHelper = new DatabaseHelper(this);
        requestQueue = Volley.newRequestQueue(this);

        spinnerPengukuran = findViewById(R.id.spinnerPengukuran);
        btnTampilkanData = findViewById(R.id.btnTampilkanData);
        btnExportDB = findViewById(R.id.btnExportDB);

        cardTma = findViewById(R.id.cardTma);
        cardThomson = findViewById(R.id.cardThomson);
        cardSR = findViewById(R.id.cardSR);
        cardBocoran = findViewById(R.id.cardBocoran);

        tvTmaValue = findViewById(R.id.tvTmaValue);
        tvA1R = findViewById(R.id.tvA1R);
        tvA1L = findViewById(R.id.tvA1L);
        tvB1 = findViewById(R.id.tvB1);
        tvB3 = findViewById(R.id.tvB3);
        tvB5 = findViewById(R.id.tvB5);

        containerSR = findViewById(R.id.containerSR);
        containerBocoran = findViewById(R.id.containerBocoran);

        loadPengukuranList();

        btnTampilkanData.setOnClickListener(v -> {
            int pos = spinnerPengukuran.getSelectedItemPosition();
            if (pos >= 0 && pos < pengukuranIds.size()) {
                int pengukuranId = pengukuranIds.get(pos);
                if (isOnline()) {
                    tampilkanDataOnline(pengukuranId);
                } else {
                    tampilkanDataOffline(pengukuranId);
                }
            }
        });

        btnExportDB.setOnClickListener(v -> exportDatabaseToSQL());
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnected();
    }

    private void loadPengukuranList() {
        if (isOnline()) {
            String url = BASE_URL + "pengukuran";
            JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET, url, null,
                    response -> {
                        try {
                            JSONArray data = response.getJSONArray("data");
                            pengukuranIds.clear();
                            pengukuranLabels.clear();

                            for (int i = 0; i < data.length(); i++) {
                                JSONObject obj = data.getJSONObject(i);
                                int id = obj.getInt("id");
                                String tgl = obj.getString("tanggal");
                                pengukuranIds.add(id);
                                pengukuranLabels.add(tgl);
                            }

                            ArrayAdapter<String> spinnerAdapter =
                                    new ArrayAdapter<>(this,
                                            android.R.layout.simple_spinner_item, pengukuranLabels);
                            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                            spinnerPengukuran.setAdapter(spinnerAdapter);

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    },
                    error -> error.printStackTrace());
            requestQueue.add(request);
        } else {
            // Offline: ambil list pengukuran dari SQLite
            pengukuranIds.clear();
            pengukuranLabels.clear();

            Cursor cursor = dbHelper.getReadableDatabase().rawQuery("SELECT id, tanggal FROM t_data_pengukuran ORDER BY tanggal DESC", null);
            if (cursor.moveToFirst()) {
                do {
                    int id = cursor.getInt(cursor.getColumnIndexOrThrow("id"));
                    String tgl = cursor.getString(cursor.getColumnIndexOrThrow("tanggal"));
                    pengukuranIds.add(id);
                    pengukuranLabels.add(tgl);
                } while (cursor.moveToNext());
            }
            cursor.close();

            ArrayAdapter<String> spinnerAdapter =
                    new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, pengukuranLabels);
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerPengukuran.setAdapter(spinnerAdapter);
        }
    }

    // ============ Tampilkan data online ============
    private void tampilkanDataOnline(int pengukuranId) {
        String url = BASE_URL + "detail/" + pengukuranId;
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET, url, null,
                response -> {
                    try {
                        JSONObject data = response.getJSONObject("data");
                        tampilkanDataJson(data);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                },
                error -> error.printStackTrace());
        requestQueue.add(request);
    }

    // ============ Tampilkan data offline ============
    private void tampilkanDataOffline(int pengukuranId) {
        // Hide semua
        cardTma.setVisibility(View.GONE);
        cardThomson.setVisibility(View.GONE);
        cardSR.setVisibility(View.GONE);
        cardBocoran.setVisibility(View.GONE);

        // === TMA ===
        Cursor cursorPengukuran = dbHelper.getReadableDatabase().rawQuery(
                "SELECT * FROM t_data_pengukuran WHERE id=?",
                new String[]{String.valueOf(pengukuranId)});
        if (cursorPengukuran.moveToFirst()) {
            cardTma.setVisibility(View.VISIBLE);
            tvTmaValue.setText(getDoubleOrDash(cursorPengukuran, "tma_waduk"));
        }
        cursorPengukuran.close();

        // === Thomson ===
        Cursor cursorThomson = dbHelper.getReadableDatabase().rawQuery(
                "SELECT * FROM t_thomson_weir WHERE pengukuran_id=?",
                new String[]{String.valueOf(pengukuranId)});
        if (cursorThomson.moveToFirst()) {
            cardThomson.setVisibility(View.VISIBLE);
            tvA1R.setText("A1R: " + getDoubleOrDash(cursorThomson, "a1_r"));
            tvA1L.setText("A1L: " + getDoubleOrDash(cursorThomson, "a1_l"));
            tvB1.setText("B1: " + getDoubleOrDash(cursorThomson, "b1"));
            tvB3.setText("B3: " + getDoubleOrDash(cursorThomson, "b3"));
            tvB5.setText("B5: " + getDoubleOrDash(cursorThomson, "b5"));
        }
        cursorThomson.close();

        // === SR ===
        Cursor cursorSR = dbHelper.getReadableDatabase().rawQuery(
                "SELECT * FROM t_sr WHERE pengukuran_id=?",
                new String[]{String.valueOf(pengukuranId)});
        if (cursorSR.moveToFirst()) {
            cardSR.setVisibility(View.VISIBLE);
            containerSR.removeAllViews();

            String[] srKolom = {
                    "sr_1", "sr_40", "sr_66", "sr_68", "sr_70",
                    "sr_79", "sr_81", "sr_83", "sr_85", "sr_92",
                    "sr_94", "sr_96", "sr_98", "sr_100", "sr_102",
                    "sr_104", "sr_106"
            };

            for (String base : srKolom) {
                String kode = cursorSR.getString(cursorSR.getColumnIndexOrThrow(base + "_kode"));
                Double nilai = cursorSR.isNull(cursorSR.getColumnIndexOrThrow(base + "_nilai"))
                        ? null : cursorSR.getDouble(cursorSR.getColumnIndexOrThrow(base + "_nilai"));

                if (kode != null || nilai != null) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);

                    row.addView(createDataTextView(base.toUpperCase(), 1.5f));
                    row.addView(createDataTextView(kode == null ? "-" : kode, 1f));
                    row.addView(createDataTextView(nilai == null ? "-" : String.valueOf(nilai), 1f));

                    containerSR.addView(row);
                }
            }
        }
        cursorSR.close();

        // === Bocoran Baru (Offline) ===
        Cursor cursorBocoran = dbHelper.getReadableDatabase().rawQuery(
                "SELECT * FROM t_bocoran_baru WHERE pengukuran_id=?",
                new String[]{String.valueOf(pengukuranId)});
        if (cursorBocoran.moveToFirst()) {
            cardBocoran.setVisibility(View.VISIBLE);
            containerBocoran.removeAllViews();

            addBocoranRow(
                    containerBocoran,
                    "ELV 624 T1",
                    getDoubleOrDash(cursorBocoran, "elv_624_t1"),
                    cursorBocoran.isNull(cursorBocoran.getColumnIndexOrThrow("elv_624_t1_kode"))
                            ? "-" : cursorBocoran.getString(cursorBocoran.getColumnIndexOrThrow("elv_624_t1_kode"))
            );

            addBocoranRow(
                    containerBocoran,
                    "ELV 615 T2",
                    getDoubleOrDash(cursorBocoran, "elv_615_t2"),
                    cursorBocoran.isNull(cursorBocoran.getColumnIndexOrThrow("elv_615_t2_kode"))
                            ? "-" : cursorBocoran.getString(cursorBocoran.getColumnIndexOrThrow("elv_615_t2_kode"))
            );

            addBocoranRow(
                    containerBocoran,
                    "PIPA P1",
                    getDoubleOrDash(cursorBocoran, "pipa_p1"),
                    cursorBocoran.isNull(cursorBocoran.getColumnIndexOrThrow("pipa_p1_kode"))
                            ? "-" : cursorBocoran.getString(cursorBocoran.getColumnIndexOrThrow("pipa_p1_kode"))
            );
        }
        cursorBocoran.close();
    }

    // ============ Online JSON ============
    private void tampilkanDataJson(JSONObject data) throws Exception {
        cardTma.setVisibility(View.GONE);
        cardThomson.setVisibility(View.GONE);
        cardSR.setVisibility(View.GONE);
        cardBocoran.setVisibility(View.GONE);

        // TMA
        if (data.has("tma")) {
            cardTma.setVisibility(View.VISIBLE);
            tvTmaValue.setText(data.getString("tma"));
        }

        // Thomson
        if (data.has("thomson")) {
            cardThomson.setVisibility(View.VISIBLE);
            JSONObject th = data.getJSONObject("thomson");
            tvA1R.setText("A1R: " + th.optString("a1_r", "--"));
            tvA1L.setText("A1L: " + th.optString("a1_l", "--"));
            tvB1.setText("B1: " + th.optString("b1", "--"));
            tvB3.setText("B3: " + th.optString("b3", "--"));
            tvB5.setText("B5: " + th.optString("b5", "--"));
        }

        // SR
        if (data.has("sr")) {
            cardSR.setVisibility(View.VISIBLE);
            containerSR.removeAllViews();
            JSONArray srArr = data.getJSONArray("sr");
            for (int i = 0; i < srArr.length(); i++) {
                JSONObject srObj = srArr.getJSONObject(i);
                String nama  = srObj.optString("nama", "SR" + (i + 1));
                String kode  = srObj.optString("kode", "-");
                String nilai = srObj.optString("nilai", "-");

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.addView(createDataTextView(nama, 1.5f));
                row.addView(createDataTextView(kode, 1f));
                row.addView(createDataTextView(nilai, 1f));

                containerSR.addView(row);
            }
        }

        // Bocoran Baru
        if (data.has("bocoran")) {
            cardBocoran.setVisibility(View.VISIBLE);
            containerBocoran.removeAllViews();
            JSONObject boc = data.getJSONObject("bocoran");

            addBocoranRow(containerBocoran,
                    "ELV 624 T1",
                    boc.optString("elv_624_t1", "--"),
                    boc.optString("elv_624_t1_kode", "-"));

            addBocoranRow(containerBocoran,
                    "ELV 615 T2",
                    boc.optString("elv_615_t2", "--"),
                    boc.optString("elv_615_t2_kode", "-"));

            addBocoranRow(containerBocoran,
                    "PIPA P1",
                    boc.optString("pipa_p1", "--"),
                    boc.optString("pipa_p1_kode", "-"));
        }
    }

    // ============ Export Database ============
    private void exportDatabaseToSQL() {
        try {
            // Dapatkan path database
            File databasePath = getDatabasePath(DatabaseHelper.DB_NAME);

            if (!databasePath.exists()) {
                Toast.makeText(this, "Database tidak ditemukan", Toast.LENGTH_SHORT).show();
                return;
            }

            // Buat nama file dengan timestamp
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "db_saguling_export_" + timeStamp + ".sql";

            // Direktori download
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File exportFile = new File(downloadDir, fileName);

            // Ekspor database ke file SQL
            exportDatabase(databasePath, exportFile);

            Toast.makeText(this, "Database berhasil diexport ke: " + exportFile.getAbsolutePath(), Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error exporting database: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void exportDatabase(File databasePath, File exportFile) throws Exception {
        StringBuilder sqlContent = new StringBuilder();

        // Header SQL
        sqlContent.append("-- Database Export: ").append(new Date().toString()).append("\n");
        sqlContent.append("-- Database: ").append(DatabaseHelper.DB_NAME).append("\n\n");

        // Daftar semua tabel yang akan diekspor
        String[] allTables = {
                "android_metadata",
                "t_data_pengukuran",
                "t_thomson_weir",
                "t_sr",
                "t_bocoran_baru",
                "ambang",
                "p_batasmaksimal",
                "p_intigalery",
                "p_spillway",
                "p_sr",
                "p_tebingkanan",
                "p_thomson_weir",
                "p_totalbocoran",
                "thomson",
                "t_ambang_batas",
                "p_bocoran_baru",
                "analisa_look_burt"
        };

        // Ekspor struktur dan data untuk setiap tabel
        for (String tableName : allTables) {
            try {
                exportTableData(tableName, sqlContent);
            } catch (Exception e) {
                sqlContent.append("-- Error exporting table: ").append(tableName)
                        .append(" - ").append(e.getMessage()).append("\n\n");
            }
        }

        // Tulis ke file
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(exportFile), StandardCharsets.UTF_8)) {
            writer.write(sqlContent.toString());
            writer.flush();
        }
    }

    private void exportTableData(String tableName, StringBuilder sqlContent) {
        Cursor cursor = null;
        try {
            // Cek apakah tabel exists
            cursor = dbHelper.getReadableDatabase().rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    new String[]{tableName});

            if (!cursor.moveToFirst()) {
                // Tabel tidak ada, skip
                sqlContent.append("-- Table ").append(tableName).append(" does not exist\n\n");
                return;
            }
            cursor.close();

            // Dapatkan struktur tabel
            cursor = dbHelper.getReadableDatabase().rawQuery(
                    "PRAGMA table_info(" + tableName + ")", null);

            if (cursor.getCount() == 0) {
                sqlContent.append("-- Table ").append(tableName).append(" has no columns\n\n");
                return;
            }

            List<String> columns = new ArrayList<>();
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
            }
            cursor.close();

            // Dapatkan data tabel
            cursor = dbHelper.getReadableDatabase().rawQuery(
                    "SELECT * FROM " + tableName, null);

            sqlContent.append("-- ===========================================\n");
            sqlContent.append("-- Data untuk tabel: ").append(tableName).append("\n");
            sqlContent.append("-- ===========================================\n");

            int rowCount = 0;
            while (cursor.moveToNext()) {
                StringBuilder insertStatement = new StringBuilder();
                insertStatement.append("INSERT INTO ").append(tableName).append(" (");

                // Tambahkan nama kolom
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        insertStatement.append(", ");
                    }
                    insertStatement.append(columns.get(i));
                }
                insertStatement.append(") VALUES(");

                // Tambahkan nilai
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        insertStatement.append(", ");
                    }

                    int columnType = cursor.getType(i);
                    switch (columnType) {
                        case Cursor.FIELD_TYPE_NULL:
                            insertStatement.append("NULL");
                            break;
                        case Cursor.FIELD_TYPE_INTEGER:
                            insertStatement.append(cursor.getLong(i));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            insertStatement.append(cursor.getDouble(i));
                            break;
                        case Cursor.FIELD_TYPE_STRING:
                            String value = cursor.getString(i);
                            if (value != null) {
                                // Escape single quotes
                                String escapedValue = value.replace("'", "''");
                                insertStatement.append("'").append(escapedValue).append("'");
                            } else {
                                insertStatement.append("NULL");
                            }
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            // Skip BLOB data untuk simplicity
                            insertStatement.append("NULL");
                            break;
                    }
                }

                insertStatement.append(");\n");
                sqlContent.append(insertStatement.toString());
                rowCount++;
            }

            sqlContent.append("-- Total rows: ").append(rowCount).append("\n\n");

        } catch (Exception e) {
            e.printStackTrace();
            sqlContent.append("-- Error exporting table: ").append(tableName)
                    .append(" - ").append(e.getMessage()).append("\n\n");
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
    }

    // Utility =====================================================
    private void addBocoranRow(LinearLayout container, String nama, String nilai, String kode) {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 12, 0, 12);

        TextView tvNama = createDataTextView(nama, 1.5f);
        TextView tvNilai = createDataTextView(nilai, 1f);
        TextView tvKode = createDataTextView(kode, 1f);

        row.addView(tvNama);
        row.addView(tvNilai);
        row.addView(tvKode);

        container.addView(row);
    }

    private String getDoubleOrDash(Cursor c, String column) {
        if (c.isNull(c.getColumnIndexOrThrow(column))) return "--";
        return String.valueOf(c.getDouble(c.getColumnIndexOrThrow(column)));
    }

    private TextView createDataTextView(String text, float weight) {
        TextView textView = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        params.gravity = Gravity.CENTER;
        textView.setLayoutParams(params);
        textView.setText(text);
        textView.setTextSize(14);
        textView.setTextColor(getResources().getColor(android.R.color.black));
        textView.setPadding(8, 8, 8, 8);
        textView.setGravity(Gravity.CENTER);
        return textView;
    }
}
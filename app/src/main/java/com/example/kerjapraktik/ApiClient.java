package com.example.kerjapraktik;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ApiClient {
    // Ganti sesuai alamat server kamu
    public static final String BASE_URL = "http://192.168.1.12/API_Android/public/";

    private static Retrofit retrofit;

    public static Retrofit getClient() {
        if (retrofit == null) {
            // Tambahkan OkHttpClient dengan timeout lebih panjang
            OkHttpClient okHttpClient = new OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)  // waktu koneksi max 60 detik
                    .readTimeout(60, TimeUnit.SECONDS)     // waktu baca data max 60 detik
                    .writeTimeout(60, TimeUnit.SECONDS)    // waktu kirim data max 60 detik
                    .retryOnConnectionFailure(true)        // otomatis retry kalau koneksi putus
                    .build();

            retrofit = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(okHttpClient)
                    .build();
        }
        return retrofit;
    }
}

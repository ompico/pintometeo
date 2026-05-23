package com.ompico.pintometeo

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// Estructuras de datos para mapear el JSON de Open-Meteo
data class OpenMeteoResponse(
    val hourly: HourlyData
)

data class HourlyData(
    val time: List<String>,
    val temperature_2m: List<Double>,
    val relative_humidity_2m: List<Double>,
    val wind_speed_10m: List<Double>,
    val precipitation: List<Double>
)

interface OpenMeteoService {
    // Endpoint para predicciones y datos recientes.
    // past_days permite recuperar días anteriores al actual (necesario para ayer y anteayer,
    // ya que el Archive tiene ~5 días de retraso y no cubre el periodo más reciente).
    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude")  lat: Double,
        @Query("longitude") lon: Double,
        @Query("past_days")  pastDays: Int,
        @Query("hourly")    variables: String = "temperature_2m,relative_humidity_2m,wind_speed_10m,precipitation",
        @Query("timezone")  timezone: String  = "auto"
    ): OpenMeteoResponse

    // Endpoint para el archivo histórico (datos disponibles hasta ~5 días antes de hoy)
    @GET("v1/archive")
    suspend fun getArchive(
        @Query("latitude")   lat: Double,
        @Query("longitude")  lon: Double,
        @Query("start_date") startDate: String,
        @Query("end_date")   endDate: String,
        @Query("hourly")     variables: String = "temperature_2m,relative_humidity_2m,wind_speed_10m,precipitation",
        @Query("timezone")   timezone: String  = "auto"
    ): OpenMeteoResponse
}

// Singletons de Retrofit: se crean una sola vez y se reutilizan en cada consulta.
object NetworkClient {
    private const val BASE_URL    = "https://api.open-meteo.com/"
    private const val ARCHIVE_URL = "https://archive-api.open-meteo.com/"

    val forecastService: OpenMeteoService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenMeteoService::class.java)
    }

    val archiveService: OpenMeteoService by lazy {
        Retrofit.Builder()
            .baseUrl(ARCHIVE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenMeteoService::class.java)
    }
}

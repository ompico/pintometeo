package com.ompico.pintometeo

import com.ompico.pintometeo.data.RegistroMeteorologico
import com.ompico.pintometeo.data.TipoRegistro
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Acceso a la red: obtiene y combina datos meteorológicos de Open-Meteo.
 *
 * Fuentes utilizadas:
 *  - Archive API  (archive-api.open-meteo.com): datos históricos medidos y validados
 *    mediante reanálisis ERA5. Solo disponibles hasta ~5 días antes de hoy.
 *  - Forecast API (api.open-meteo.com): salidas continuas del modelo de predicción.
 *    Cubre el pasado reciente (que el Archive aún no tiene) y el futuro.
 *
 * Cuando ambas fuentes tienen datos para la misma hora, prevalece siempre el histórico
 * del Archive, por ser una medición real frente a una estimación del modelo.
 *
 * IMPORTANTE: esta función NO genera el nodo "Ahora". La interpolación del momento
 * presente es responsabilidad exclusiva de ControladorMeteorologico, que lo mantiene
 * como estado observable separado para mostrarlo como fila fija en la UI.
 *
 * La lista resultante contiene únicamente registros horarios en punto (HISTORICO o
 * PREVISION), ordenados de más reciente a más antiguo (descendente).
 */
object NetworkRepository {

    suspend fun obtenerDatosCompletos(
        lat: Double,
        lon: Double,
        fechaInicioHistorial: String,
        fechaFinHistorial: String
    ): List<RegistroMeteorologico> {

        val sdfFecha = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val ahora    = Calendar.getInstance()

        // ── 1. CONSULTA HISTÓRICA (Archive API) ───────────────────────────────────
        // El Archive devuelve error si end_date supera su última fecha disponible
        // (~hoy − 5 días). Recortamos el límite superior para evitar ese error.
        // Si tras el recorte el rango queda invertido, omitimos la llamada al Archive.
        val calArchivoMax   = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -5) }
        val fechaArchivoMax = sdfFecha.format(calArchivoMax.time)
        val archivoFechaFinEfectiva =
            if (fechaFinHistorial > fechaArchivoMax) fechaArchivoMax else fechaFinHistorial

        val listaHistorico = mutableListOf<RegistroMeteorologico>()

        if (archivoFechaFinEfectiva >= fechaInicioHistorial) {
            try {
                val resp = NetworkClient.archiveService.getArchive(
                    lat, lon, fechaInicioHistorial, archivoFechaFinEfectiva
                )
                for (i in resp.hourly.time.indices) {
                    listaHistorico.add(
                        RegistroMeteorologico(
                            fechaCronologica = resp.hourly.time[i],
                            hora             = formatearHoraIso(resp.hourly.time[i]),
                            temperatura      = resp.hourly.temperature_2m[i],
                            velocidadViento  = resp.hourly.wind_speed_10m[i],
                            humedad          = resp.hourly.relative_humidity_2m[i].toDouble(),
                            precipitacion    = resp.hourly.precipitation[i],
                            tipo             = TipoRegistro.HISTORICO
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // ── 2. CONSULTA DE PREDICCIONES (Forecast API) ────────────────────────────
        // Calculamos cuántos días hacia atrás abarca el rango pedido para solicitarlos
        // todos como past_days. Esto cubre el hueco reciente que el Archive no tiene.
        // Máximo aceptado por Open-Meteo: 92 días.
        val fechaInicioDate = try { sdfFecha.parse(fechaInicioHistorial) } catch (_: Exception) { null }
        val diffDias = if (fechaInicioDate != null)
            ((ahora.timeInMillis - fechaInicioDate.time) / (1000L * 60 * 60 * 24)).toInt()
        else 0
        val pastDays = (diffDias + 1).coerceIn(1, 92)

        // Límite superior con sufijo horario para incluir todas las horas del último día.
        // La comparación de strings ISO funciona porque el límite inferior sin sufijo
        // es seguro gracias a la asimetría del operador >= con el carácter 'T'.
        val limiteFinForecast = fechaFinHistorial + "T23:59"

        val listaPredicciones = mutableListOf<RegistroMeteorologico>()

        try {
            val resp = NetworkClient.forecastService.getForecast(lat, lon, pastDays)
            for (i in resp.hourly.time.indices) {
                val horaIso = resp.hourly.time[i]
                // Solo añadimos registros dentro del rango pedido por el usuario
                if (horaIso in fechaInicioHistorial..limiteFinForecast) {
                    listaPredicciones.add(
                        RegistroMeteorologico(
                            fechaCronologica = horaIso,
                            hora             = formatearHoraIso(horaIso),
                            temperatura      = resp.hourly.temperature_2m[i],
                            velocidadViento  = resp.hourly.wind_speed_10m[i],
                            humedad          = resp.hourly.relative_humidity_2m[i].toDouble(),
                            precipitacion    = resp.hourly.precipitation[i],
                            tipo             = TipoRegistro.PREVISION
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // ── 3. COMBINACIÓN: prevalece el histórico cuando existe ──────────────────
        // Agrupamos por hora. Si para una misma hora existen ambos tipos de registro,
        // nos quedamos con el del Archive (HISTORICO); si no, con el del Forecast.
        val combinada = (listaHistorico + listaPredicciones)
            .groupBy { it.fechaCronologica }
            .map { (_, registros) ->
                registros.find { it.tipo == TipoRegistro.HISTORICO } ?: registros.first()
            }

        // Orden descendente: el registro más reciente aparece primero en la lista
        return combinada.sortedByDescending { it.fechaCronologica }
    }

    // Convierte "2026-05-25T14:00" en "25/05 14:00" para mostrar en la tabla
    private fun formatearHoraIso(fechaIso: String): String {
        return try {
            val parser    = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
            val formatter = SimpleDateFormat("dd/MM HH:mm",        Locale.getDefault())
            parser.parse(fechaIso)?.let { formatter.format(it) } ?: fechaIso
        } catch (_: Exception) { fechaIso }
    }
}

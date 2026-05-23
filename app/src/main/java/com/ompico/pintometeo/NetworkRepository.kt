package com.ompico.pintometeo

import com.ompico.pintometeo.data.RegistroMeteorologico
import com.ompico.pintometeo.data.TipoRegistro
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object NetworkRepository {

    suspend fun obtenerDatosCompletos(
        lat: Double,
        lon: Double,
        fechaInicioHistorial: String,
        fechaFinHistorial: String
    ): List<RegistroMeteorologico> {

        val sdfFecha = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val ahora    = Calendar.getInstance()

        val horaActualIsoStr  = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.getDefault()).format(ahora.time)
        val horaExactaStr     = SimpleDateFormat("HH:mm",              Locale.getDefault()).format(ahora.time)
        val diaMesActualStr   = SimpleDateFormat("dd/MM",              Locale.getDefault()).format(ahora.time)
        val claveAhoraIso     = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()).format(ahora.time)

        // ── 1. CONSULTA HISTÓRICA (Archive) ───────────────────────────────────────
        // El Archive de Open-Meteo solo tiene datos hasta ~5 días antes de hoy.
        // Limitamos end_date para no provocar un error de la API cuando el usuario
        // pide un rango que incluye días muy recientes.
        val calArchivoMax = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -5) }
        val fechaArchivoMaxStr = sdfFecha.format(calArchivoMax.time)

        // Usamos el mínimo entre la fecha fin pedida y el tope del Archive.
        val archivoFechaFinEfectiva =
            if (fechaFinHistorial > fechaArchivoMaxStr) fechaArchivoMaxStr
            else fechaFinHistorial

        val listaHistorico = mutableListOf<RegistroMeteorologico>()

        // Solo llamamos al Archive si el rango resultante sigue siendo válido.
        if (archivoFechaFinEfectiva >= fechaInicioHistorial) {
            try {
                val responseArchive = NetworkClient.archiveService.getArchive(
                    lat, lon, fechaInicioHistorial, archivoFechaFinEfectiva
                )
                val hourlyH = responseArchive.hourly
                for (i in hourlyH.time.indices) {
                    val horaIso = hourlyH.time[i]
                    listaHistorico.add(
                        RegistroMeteorologico(
                            fechaCronologica = horaIso,
                            hora             = formatearHoraIso(horaIso),
                            temperatura      = hourlyH.temperature_2m[i],
                            velocidadViento  = hourlyH.wind_speed_10m[i],
                            humedad          = hourlyH.relative_humidity_2m[i],
                            precipitacion    = hourlyH.precipitation[i],
                            tipo             = TipoRegistro.HISTORICO
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // ── 2. CONSULTA DE PREDICCIONES (Forecast) ────────────────────────────────
        // Calculamos cuántos días hacia atrás cubre el rango del usuario para pedirlos
        // todos al Forecast. Esto garantiza que ayer y anteayer aparezcan aunque el
        // Archive no los tenga todavía.
        val fechaInicioDate = try { sdfFecha.parse(fechaInicioHistorial) } catch (_: Exception) { null }
        val diffMs   = ahora.timeInMillis - (fechaInicioDate?.time ?: ahora.timeInMillis)
        val diffDias = (diffMs / (1000L * 60 * 60 * 24)).toInt()
        // +1 de margen; máximo que acepta Open-Meteo: 92
        val pastDays = (diffDias + 1).coerceIn(1, 92)

        val listaPredicciones = mutableListOf<RegistroMeteorologico>()
        var nodoT1: RegistroMeteorologico? = null
        var nodoT2: RegistroMeteorologico? = null

        try {
            val responseForecast = NetworkClient.forecastService.getForecast(lat, lon, pastDays)
            val hourlyF = responseForecast.hourly

            for (i in hourlyF.time.indices) {
                val horaIso          = hourlyF.time[i]
                val esHoraEnteraActual = horaIso.startsWith(horaActualIsoStr)

                val registroBase = RegistroMeteorologico(
                    fechaCronologica = horaIso,
                    hora             = formatearHoraIso(horaIso),
                    temperatura      = hourlyF.temperature_2m[i],
                    velocidadViento  = hourlyF.wind_speed_10m[i],
                    humedad          = hourlyF.relative_humidity_2m[i],
                    precipitacion    = hourlyF.precipitation[i],
                    tipo             = TipoRegistro.PREVISION
                )

                if (esHoraEnteraActual) {
                    nodoT1 = registroBase
                } else if (nodoT1 != null && nodoT2 == null) {
                    nodoT2 = registroBase
                }

                // Solo incluimos en el Forecast registros dentro del rango pedido por el usuario
                // o a partir de hoy (para las predicciones futuras).
                val limiteFinForecast = fechaFinHistorial + "T23:59"
                if (horaIso in fechaInicioHistorial..limiteFinForecast) {
                    listaPredicciones.add(registroBase)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // ── 3. COMBINACIÓN: prevalece el dato histórico cuando existe ──────────────
        val todosLosRegistros = mutableListOf<RegistroMeteorologico>()
        todosLosRegistros.addAll(listaHistorico)
        todosLosRegistros.addAll(listaPredicciones)

        val gruposPorHora = todosLosRegistros.groupBy { it.fechaCronologica }
        val listaNuevaCombinada = mutableListOf<RegistroMeteorologico>()

        for ((_, registros) in gruposPorHora) {
            val historico = registros.find { it.tipo == TipoRegistro.HISTORICO }
            listaNuevaCombinada.add(historico ?: registros.first())
        }

        // ── 4. INTERPOLACIÓN DEL NODO "AHORA" ─────────────────────────────────────
        // Eliminamos la entrada de la hora en punto actual si ya existe en la lista
        // combinada: el nodo interpolado es más preciso y debe reemplazarla.
        listaNuevaCombinada.removeAll { it.fechaCronologica == horaActualIsoStr }

        if (nodoT1 != null && nodoT2 != null) {
            val minutosActuales  = ahora.get(Calendar.MINUTE)
            val factor           = minutosActuales / 60.0

            listaNuevaCombinada.add(
                RegistroMeteorologico(
                    fechaCronologica = claveAhoraIso,
                    hora             = "$diaMesActualStr $horaExactaStr (Ahora)",
                    temperatura      = nodoT1.temperatura  + factor * (nodoT2.temperatura  - nodoT1.temperatura),
                    velocidadViento  = nodoT1.velocidadViento + factor * (nodoT2.velocidadViento - nodoT1.velocidadViento),
                    humedad          = nodoT1.humedad      + factor * (nodoT2.humedad      - nodoT1.humedad),
                    precipitacion    = nodoT1.precipitacion + factor * (nodoT2.precipitacion - nodoT1.precipitacion),
                    tipo             = TipoRegistro.ACTUAL
                )
            )
        } else if (nodoT1 != null) {
            listaNuevaCombinada.add(
                RegistroMeteorologico(
                    fechaCronologica = claveAhoraIso,
                    hora             = "$diaMesActualStr $horaExactaStr (Ahora)",
                    temperatura      = nodoT1.temperatura,
                    velocidadViento  = nodoT1.velocidadViento,
                    humedad          = nodoT1.humedad,
                    precipitacion    = nodoT1.precipitacion,
                    tipo             = TipoRegistro.ACTUAL
                )
            )
        }

        // Orden descendente: lo más reciente arriba
        return listaNuevaCombinada.sortedByDescending { it.fechaCronologica }
    }

    private fun formatearHoraIso(fechaIso: String): String {
        return try {
            val parser    = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
            val formatter = SimpleDateFormat("dd/MM HH:mm",        Locale.getDefault())
            parser.parse(fechaIso)?.let { formatter.format(it) } ?: fechaIso
        } catch (e: Exception) {
            fechaIso
        }
    }
}

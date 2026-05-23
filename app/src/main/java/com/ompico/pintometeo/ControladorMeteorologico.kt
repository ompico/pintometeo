package com.ompico.pintometeo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ompico.pintometeo.data.RegistroMeteorologico
import com.ompico.pintometeo.data.TipoRegistro
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

object ControladorMeteorologico {

    val datosMeteorologicos = mutableStateListOf<RegistroMeteorologico>()
    private var datosBaseOriginales: List<RegistroMeteorUniforme> = emptyList()

    private var ultimaTempManual: Double? = null
    private var ultimaHumManual: Double?  = null

    // Estado observable para que la UI sepa cuándo hay una consulta de red activa
    var cargando by mutableStateOf(false)
        private set

    // Empareja cada registro con su timestamp en milisegundos (para calcular distancias temporales)
    private data class RegistroMeteorUniforme(
        val registro: RegistroMeteorologico,
        val tiempoMs: Long?
    )

    suspend fun ejecutarConsulta(lat: Double, lon: Double, fechaInicio: String, fechaFin: String) {
        try {
            cargando = true
            val nuevosDatos = NetworkRepository.obtenerDatosCompletos(lat, lon, fechaInicio, fechaFin)
            datosBaseOriginales = reconstruirLineaTemporal(nuevosDatos)

            ultimaTempManual = null
            ultimaHumManual  = null

            datosMeteorologicos.clear()
            datosMeteorologicos.addAll(datosBaseOriginales.map { it.registro })
        } catch (e: Exception) {
            e.printStackTrace()
            datosBaseOriginales = emptyList()
            datosMeteorologicos.clear()
        } finally {
            cargando = false
        }
    }

    fun aplicarCambioTemperatura(nuevaTemp: Double?) {
        ultimaTempManual = nuevaTemp
        recalcularYActualizarListaCompleta()
    }

    fun aplicarCambioHumedad(nuevaHum: Double?) {
        ultimaHumManual = nuevaHum
        recalcularYActualizarListaCompleta()
    }

    private fun recalcularYActualizarListaCompleta() {
        if (datosBaseOriginales.isEmpty()) return

        val nodoActual = datosBaseOriginales.find { it.registro.tipo == TipoRegistro.ACTUAL }
        if (nodoActual == null || nodoActual.tiempoMs == null) {
            datosMeteorologicos.clear()
            datosMeteorologicos.addAll(datosBaseOriginales.map { it.registro })
            return
        }

        val tiempoActualMs       = nodoActual.tiempoMs
        val registroActualBase   = nodoActual.registro

        val errorTempMaximo = if (ultimaTempManual != null)
            ultimaTempManual!! - registroActualBase.temperatura
        else 0.0

        val errorHumMaximo = if (ultimaHumManual != null)
            ultimaHumManual!!.coerceIn(0.0, 100.0) - registroActualBase.humedad
        else 0.0


        // Calculamos el error en presión de vapor para propagar la humedad de forma física
        val eSatActualBase     = calcularPresionSaturacion(registroActualBase.temperatura)
        val eRealApiActual     = eSatActualBase * (registroActualBase.humedad / 100.0)
        val eSatActualMod      = calcularPresionSaturacion(registroActualBase.temperatura + errorTempMaximo)
        val eRealManual        = eSatActualMod  * ((registroActualBase.humedad + errorHumMaximo).coerceIn(0.0, 100.0) / 100.0)
        val errorPresionVapor  = eRealManual - eRealApiActual

        val listaModificada = datosBaseOriginales.map { nodoFila ->
            val reg          = nodoFila.registro
            val tiempoFilaMs = nodoFila.tiempoMs

            when {
                reg.tipo == TipoRegistro.ACTUAL -> {
                    val nuevaHum = (reg.humedad + errorHumMaximo).coerceIn(0.0, 100.0)
                    reg.copy(temperatura = reg.temperatura + errorTempMaximo, humedad = nuevaHum)
                }
                tiempoFilaMs == null -> reg
                else -> {
                    val difHoras = abs(tiempoFilaMs - tiempoActualMs).toDouble() / (1000.0 * 60.0 * 60.0)
                    if (difHoras <= 12.0) {
                        val factor    = (12.0 - difHoras) / 12.0
                        val nuevaTemp = reg.temperatura + (errorTempMaximo * factor)

                        val eSatFila    = calcularPresionSaturacion(reg.temperatura)
                        val eRealFila   = eSatFila * (reg.humedad / 100.0)
                        val eRealCorr   = (eRealFila + errorPresionVapor * factor).coerceAtLeast(0.0)
                        val eSatNueva   = calcularPresionSaturacion(nuevaTemp)
                        val nuevaHum    = ((eRealCorr / eSatNueva) * 100.0).coerceIn(0.0, 100.0)

                        reg.copy(temperatura = nuevaTemp, humedad = nuevaHum)
                    } else {
                        reg
                    }
                }
            }
        }

        datosMeteorologicos.clear()
        datosMeteorologicos.addAll(listaModificada)
    }

    private fun calcularPresionSaturacion(t: Double): Double {
        val a = 17.625
        val b = 243.04
        return 6.112 * exp((a * t) / (b + t))
    }

    /**
     * Convierte cada [RegistroMeteorologico] en un [RegistroMeteorUniforme] añadiendo su
     * timestamp en ms. Usa [RegistroMeteorologico.fechaCronologica] (formato ISO completo
     * con año) en lugar del campo visual [RegistroMeteorologico.hora], evitando ambigüedades
     * en cambios de año y simplificando el parseo.
     */
    private fun reconstruirLineaTemporal(datosInput: List<RegistroMeteorologico>): List<RegistroMeteorUniforme> {
        if (datosInput.isEmpty()) return emptyList()

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())

        return datosInput.map { reg ->
            val tiempoMs = try {
                sdf.parse(reg.fechaCronologica)?.time
            } catch (_: Exception) {
                null
            }
            RegistroMeteorUniforme(reg, tiempoMs)
        }
    }
}

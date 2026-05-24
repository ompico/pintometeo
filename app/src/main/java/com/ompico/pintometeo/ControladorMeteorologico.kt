package com.ompico.pintometeo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ompico.pintometeo.data.RegistroMeteorologico
import com.ompico.pintometeo.data.TipoRegistro
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

object ControladorMeteorologico {

    val datosMeteorologicos = mutableStateListOf<RegistroMeteorologico>()
    private var datosBaseOriginales: List<RegistroMeteorUniforme> = emptyList()

    private var ultimaTempManual: Double? = null
    private var ultimaHumManual: Double?  = null

    // ── Estado de carga observable por la UI ──────────────────────────────────
    var cargando           by mutableStateOf(false);  private set
    var cargandoMasAntiguo by mutableStateOf(false);  private set
    var cargandoMasReciente by mutableStateOf(false); private set

    // Número de items insertados al principio en la última ampliación hacia el futuro.
    // La UI lo consume para compensar el desplazamiento del scroll (el contenido
    // visible se desplaza N posiciones hacia abajo cuando se prepend N items).
    var itemsPrependedCount by mutableIntStateOf(0)

    // ── Rango actualmente cargado ─────────────────────────────────────────────
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var latActual: Double = 0.0
    private var lonActual: Double = 0.0
    private var fechaMinCargada: String = ""
    private var fechaMaxCargada: String = ""

    // Cada expansión de scroll carga este número de días adicionales
    private const val DIAS_EXPANSION = 7

    // ── Carga inicial centrada en Ahora (+1 día pasado, +1 día futuro) ─────────
    suspend fun inicializarVentana(lat: Double, lon: Double) {
        cargando  = true
        latActual = lat
        lonActual = lon

        val calInicio = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val calFin    = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, +1) }
        fechaMinCargada = sdf.format(calInicio.time)
        fechaMaxCargada = sdf.format(calFin.time)

        try {
            val datos = NetworkRepository.obtenerDatosCompletos(lat, lon, fechaMinCargada, fechaMaxCargada)
            aplicarNuevosBaseLimpios(datos)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cargando = false
        }
    }

    // ── Consulta explícita de rango (botón Ir / Consultar) ────────────────────
    suspend fun ejecutarConsulta(lat: Double, lon: Double, fechaInicio: String, fechaFin: String) {
        cargando  = true
        latActual = lat
        lonActual = lon
        fechaMinCargada = fechaInicio
        fechaMaxCargada = fechaFin

        try {
            val datos = NetworkRepository.obtenerDatosCompletos(lat, lon, fechaInicio, fechaFin)
            aplicarNuevosBaseLimpios(datos)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cargando = false
        }
    }

    // ── Ampliar hacia el pasado (usuario hace scroll hacia abajo) ─────────────
    // Los datos más antiguos se añaden al FINAL de la lista descendente.
    // No hay salto de scroll: insertar al final no desplaza los items visibles.
    suspend fun ampliarHaciaElPasado() {
        if (cargandoMasAntiguo || fechaMinCargada.isEmpty()) return

        val fechaMinDate = sdf.parse(fechaMinCargada) ?: return
        cargandoMasAntiguo = true

        val calNuevaFin = Calendar.getInstance().apply {
            time = fechaMinDate
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val calNuevaInicio = Calendar.getInstance().apply {
            time = calNuevaFin.time
            add(Calendar.DAY_OF_YEAR, -(DIAS_EXPANSION - 1))
        }
        val nuevaFechaInicio = sdf.format(calNuevaInicio.time)
        val nuevaFechaFin    = sdf.format(calNuevaFin.time)

        try {
            val nuevosDatos = NetworkRepository.obtenerDatosCompletos(
                latActual, lonActual, nuevaFechaInicio, nuevaFechaFin
            )
            fechaMinCargada = nuevaFechaInicio
            val nuevosUniformes = reconstruirLineaTemporal(nuevosDatos)
            // Datos más antiguos → van al FINAL (lista descendente: newer first)
            datosBaseOriginales = datosBaseOriginales + nuevosUniformes
            recalcularYActualizarListaCompleta()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cargandoMasAntiguo = false
        }
    }

    // ── Ampliar hacia el futuro (usuario hace scroll hacia arriba) ────────────
    // Los datos más nuevos se insertan al INICIO de la lista descendente.
    // Esto desplaza los items existentes N posiciones: se comunica a la UI
    // via itemsPrependedCount para que compense el scroll sin salto visual.
    suspend fun ampliarHaciaElFuturo() {
        if (cargandoMasReciente || fechaMaxCargada.isEmpty()) return

        val fechaMaxDate = sdf.parse(fechaMaxCargada) ?: return
        cargandoMasReciente = true

        val calNuevaInicio = Calendar.getInstance().apply {
            time = fechaMaxDate
            add(Calendar.DAY_OF_YEAR, +1)
        }
        val calNuevaFin = Calendar.getInstance().apply {
            time = calNuevaInicio.time
            add(Calendar.DAY_OF_YEAR, DIAS_EXPANSION - 1)
        }
        val nuevaFechaInicio = sdf.format(calNuevaInicio.time)
        val nuevaFechaFin    = sdf.format(calNuevaFin.time)

        try {
            val nuevosDatos = NetworkRepository.obtenerDatosCompletos(
                latActual, lonActual, nuevaFechaInicio, nuevaFechaFin
            )
            fechaMaxCargada = nuevaFechaFin
            val nuevosUniformes = reconstruirLineaTemporal(nuevosDatos)
            // Datos más nuevos → van al INICIO (lista descendente: newer first)
            // Notificamos cuántos se prependen para que la UI compense el scroll
            itemsPrependedCount = nuevosUniformes.size
            datosBaseOriginales = nuevosUniformes + datosBaseOriginales
            recalcularYActualizarListaCompleta()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cargandoMasReciente = false
        }
    }

    // ── Ajustes manuales ──────────────────────────────────────────────────────
    fun aplicarCambioTemperatura(nuevaTemp: Double?) {
        ultimaTempManual = nuevaTemp
        recalcularYActualizarListaCompleta()
    }

    fun aplicarCambioHumedad(nuevaHum: Double?) {
        ultimaHumManual = nuevaHum
        recalcularYActualizarListaCompleta()
    }

    // ── Helpers privados ──────────────────────────────────────────────────────

    private fun aplicarNuevosBaseLimpios(datos: List<RegistroMeteorologico>) {
        ultimaTempManual    = null
        ultimaHumManual     = null
        datosBaseOriginales = reconstruirLineaTemporal(datos)
        datosMeteorologicos.clear()
        datosMeteorologicos.addAll(datosBaseOriginales.map { it.registro })
    }

    private fun recalcularYActualizarListaCompleta() {
        if (datosBaseOriginales.isEmpty()) return

        val nodoActual = datosBaseOriginales.find { it.registro.tipo == TipoRegistro.ACTUAL }
        if (nodoActual == null || nodoActual.tiempoMs == null) {
            datosMeteorologicos.clear()
            datosMeteorologicos.addAll(datosBaseOriginales.map { it.registro })
            return
        }

        val tiempoActualMs     = nodoActual.tiempoMs
        val registroActualBase = nodoActual.registro

        val errorTempMaximo = ultimaTempManual?.let { it - registroActualBase.temperatura } ?: 0.0
        val errorHumMaximo  = ultimaHumManual?.let  { it - registroActualBase.humedad }    ?: 0.0

        // Calculamos el error en presión de vapor para propagar la humedad
        // de forma físicamente coherente con la termodinámica del aire húmedo
        val eSatActualBase    = calcularPresionSaturacion(registroActualBase.temperatura)
        val eRealApiActual    = eSatActualBase * (registroActualBase.humedad / 100.0)
        val eSatActualMod     = calcularPresionSaturacion(registroActualBase.temperatura + errorTempMaximo)
        val eRealManual       = eSatActualMod * ((registroActualBase.humedad + errorHumMaximo).coerceIn(0.0, 100.0) / 100.0)
        val errorPresionVapor = eRealManual - eRealApiActual

        val listaModificada = datosBaseOriginales.map { nodoFila ->
            val reg          = nodoFila.registro
            val tiempoFilaMs = nodoFila.tiempoMs

            when {
                reg.tipo == TipoRegistro.ACTUAL -> {
                    // Nodo Ahora: 100 % del error del usuario
                    val nuevaHum = (reg.humedad + errorHumMaximo).coerceIn(0.0, 100.0)
                    reg.copy(temperatura = reg.temperatura + errorTempMaximo, humedad = nuevaHum)
                }
                tiempoFilaMs == null -> reg
                else -> {
                    val difHoras = abs(tiempoFilaMs - tiempoActualMs).toDouble() / (1000.0 * 60.0 * 60.0)
                    if (difHoras <= 12.0) {
                        // Atenuación lineal: 100 % en Ahora → 0 % a 12 h de distancia
                        val factor    = (12.0 - difHoras) / 12.0
                        val nuevaTemp = reg.temperatura + errorTempMaximo * factor

                        val eSatFila  = calcularPresionSaturacion(reg.temperatura)
                        val eRealFila = eSatFila * (reg.humedad / 100.0)
                        val eRealCorr = (eRealFila + errorPresionVapor * factor).coerceAtLeast(0.0)
                        val eSatNueva = calcularPresionSaturacion(nuevaTemp)
                        val nuevaHum  = ((eRealCorr / eSatNueva) * 100.0).coerceIn(0.0, 100.0)

                        reg.copy(temperatura = nuevaTemp, humedad = nuevaHum)
                    } else reg
                }
            }
        }

        datosMeteorologicos.clear()
        datosMeteorologicos.addAll(listaModificada)
    }

    // Fórmula de Tetens para la presión de saturación de vapor (en mbar)
    private fun calcularPresionSaturacion(t: Double): Double {
        val a = 17.625
        val b = 243.04
        return 6.112 * exp((a * t) / (b + t))
    }

    // Empareja cada registro con su timestamp en ms para calcular distancias temporales
    private data class RegistroMeteorUniforme(
        val registro: RegistroMeteorologico,
        val tiempoMs: Long?
    )

    // Usa fechaCronologica (ISO completo con año) en lugar del campo visual 'hora'
    // para evitar ambigüedades en cambios de año y simplificar el parseo
    private fun reconstruirLineaTemporal(datosInput: List<RegistroMeteorologico>): List<RegistroMeteorUniforme> {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
        return datosInput.map { reg ->
            val tiempoMs = try { parser.parse(reg.fechaCronologica)?.time } catch (_: Exception) { null }
            RegistroMeteorUniforme(reg, tiempoMs)
        }
    }
}

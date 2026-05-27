package com.ompico.pintometeo

import androidx.compose.runtime.getValue
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

/**
 * Controlador central de la lógica meteorológica. Singleton de Compose.
 *
 * Responsabilidades:
 *  - [datosMeteorologicos]: lista observable de registros horarios en punto
 *    (HISTORICO o PREVISION) que alimenta la LazyColumn desplazable.
 *    El nodo ACTUAL nunca está en esta lista.
 *
 *  - [registroAhora]: nodo interpolado del momento presente, mantenido como
 *    estado observable separado. La UI lo muestra como fila fija inamovible
 *    bajo la cabecera, siempre visible independientemente del scroll.
 *    Vale null si el rango cargado no cubre el momento presente.
 *
 *  - Gestiona la carga inicial centrada en el presente (ayer → mañana) y las
 *    ampliaciones de scroll hacia el pasado y el futuro (bloques de 7 días).
 *
 *  - Aplica las correcciones manuales del usuario (termómetro e higrómetro)
 *    y las propaga linealmente en una ventana de ±12 horas alrededor del
 *    presente, trabajando en espacio de presión de vapor absoluta para
 *    mantener coherencia termodinámica con la temperatura corregida.
 */
object ControladorMeteorologico {

    // ── Lista observable para la LazyColumn ───────────────────────────────────
    // Solo contiene registros HISTORICO y PREVISION, nunca ACTUAL.
    val datosMeteorologicos = mutableStateListOf<RegistroMeteorologico>()

    // Mensaje de aviso cuando la API no devuelve más datos en una dirección.
    // La UI lo lee para mostrar un Toast y lo resetea a null tras hacerlo.
    var avisoLimiteApi by mutableStateOf<String?>(null)

    // ── Nodo "Ahora" como estado observable separado ──────────────────────────
    // Interpolación lineal entre la hora en punto actual (T1) y la siguiente (T2).
    // Se muestra como fila fija inamovible bajo la cabecera de la tabla.
    // Null si el rango cargado no incluye el momento presente.
    var registroAhora by mutableStateOf<RegistroMeteorologico?>(null)
        private set

    // Copia sin correcciones del nodo Ahora. Sirve de referencia para calcular
    // el error entre la medición del usuario y el valor base de la API.
    private var registroAhoraBase: RegistroMeteorologico? = null

    // Copia sin correcciones de la lista completa. Permite recalcular las
    // correcciones sin volver a llamar a la red cada vez que el usuario modifica
    // los campos del termómetro o del higrómetro.
    private var datosBaseOriginales: List<RegistroMeteorUniforme> = emptyList()

    // Últimas mediciones introducidas por el usuario (null = sin corrección activa)
    private var ultimaTempManual: Double? = null
    private var ultimaHumManual:  Double? = null

    // ── Estado de carga observable ────────────────────────────────────────────
    var cargando            by mutableStateOf(false);  private set  // Carga completa (bloquea la tabla)
    var cargandoMasAntiguo  by mutableStateOf(false);  private set  // Ampliación hacia el pasado
    var cargandoMasReciente by mutableStateOf(false);  private set  // Ampliación hacia el futuro

    // ── Rango actualmente cargado ─────────────────────────────────────────────
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var latActual:       Double = 0.0
    private var lonActual:       Double = 0.0
    private var fechaMinCargada: String = ""  // Fecha más antigua en memoria
    private var fechaMaxCargada: String = ""  // Fecha más reciente en memoria

    // Días que se añaden en cada expansión de scroll (en ambas direcciones)
    private const val DIAS_EXPANSION = 7

    // ─────────────────────────────────────────────────────────────────────────
    // CARGA INICIAL
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Carga la ventana inicial ayer → mañana, centrada en el presente.
     * Garantiza que T1 (hora en punto actual) y T2 (hora siguiente) siempre
     * estén disponibles para interpolar el nodo Ahora desde el arranque.
     * Llamado desde MainActivity al arrancar o al obtener coordenadas GPS.
     */
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

    // ─────────────────────────────────────────────────────────────────────────
    // AMPLIACIONES DE SCROLL (carga incremental bidireccional)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Añade [DIAS_EXPANSION] días más antiguos al final de la lista descendente.
     * Insertar al final no desplaza los items visibles, así que no hay salto de scroll.
     * Disparado automáticamente cuando el usuario llega a 5 items del fondo.
     */
    suspend fun ampliarHaciaElPasado() {
        if (cargandoMasAntiguo || fechaMinCargada.isEmpty()) return

        val fechaMinDate = sdf.parse(fechaMinCargada) ?: return
        cargandoMasAntiguo = true

        // El nuevo bloque termina el día antes de [fechaMinCargada]
        val calNuevaFin = Calendar.getInstance().apply {
            time = fechaMinDate
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val calNuevaInicio = Calendar.getInstance().apply {
            time = calNuevaFin.time
            add(Calendar.DAY_OF_YEAR, -(DIAS_EXPANSION - 1))
        }

        try {
            val nuevosDatos = NetworkRepository.obtenerDatosCompletos(
                latActual, lonActual,
                sdf.format(calNuevaInicio.time),
                sdf.format(calNuevaFin.time)
            )

            if (nuevosDatos.isEmpty()) {
                avisoLimiteApi = "No hay más datos históricos disponibles"
                return
            }
            
            fechaMinCargada = sdf.format(calNuevaInicio.time)
            // Datos más antiguos → van al FINAL (lista descendente: más reciente primero)
            datosBaseOriginales = datosBaseOriginales + reconstruirLineaTemporal(nuevosDatos)
            recalcularYActualizarListaCompleta()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cargandoMasAntiguo = false
        }
    }

    /**
     * Añade [DIAS_EXPANSION] días más recientes al inicio de la lista descendente.
     * LazyColumn con key estable (fechaCronologica) mantiene automáticamente la
     * posición del item visible al insertar al inicio, sin salto de scroll.
     * Disparado automáticamente cuando el usuario llega a 3 items del tope.
     */
    suspend fun ampliarHaciaElFuturo() {
        if (cargandoMasReciente || fechaMaxCargada.isEmpty()) return

        val fechaMaxDate = sdf.parse(fechaMaxCargada) ?: return
        cargandoMasReciente = true

        // El nuevo bloque empieza el día después de [fechaMaxCargada]
        val calNuevaInicio = Calendar.getInstance().apply {
            time = fechaMaxDate
            add(Calendar.DAY_OF_YEAR, +1)
        }
        val calNuevaFin = Calendar.getInstance().apply {
            time = calNuevaInicio.time
            add(Calendar.DAY_OF_YEAR, DIAS_EXPANSION - 1)
        }

        try {
            val nuevosDatos = NetworkRepository.obtenerDatosCompletos(
                latActual, lonActual,
                sdf.format(calNuevaInicio.time),
                sdf.format(calNuevaFin.time)
            )

            if (nuevosDatos.isEmpty()) {
                avisoLimiteApi = "No hay más datos de previsión disponibles"
                return
            }
            
            fechaMaxCargada = sdf.format(calNuevaFin.time)
            
            // Datos más recientes → van al INICIO (lista descendente: más reciente primero)
            datosBaseOriginales = reconstruirLineaTemporal(nuevosDatos) + datosBaseOriginales
            recalcularYActualizarListaCompleta()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cargandoMasReciente = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AJUSTES MANUALES (termómetro e higrómetro)
    // ─────────────────────────────────────────────────────────────────────────

    /** Actualiza la temperatura manual y recalcula la lista y el nodo Ahora. */
    fun aplicarCambioTemperatura(nuevaTemp: Double?) {
        ultimaTempManual = nuevaTemp
        recalcularYActualizarListaCompleta()
    }

    /** Actualiza la humedad manual y recalcula la lista y el nodo Ahora. */
    fun aplicarCambioHumedad(nuevaHum: Double?) {
        ultimaHumManual = nuevaHum
        recalcularYActualizarListaCompleta()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS PRIVADOS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sustituye los datos base con una carga nueva, resetea las correcciones
     * manuales y recalcula la lista visible y el nodo Ahora desde cero.
     */
    private fun aplicarNuevosBaseLimpios(datos: List<RegistroMeteorologico>) {
        ultimaTempManual    = null
        ultimaHumManual     = null
        datosBaseOriginales = reconstruirLineaTemporal(datos)
        registroAhoraBase   = calcularNodoAhora(datosBaseOriginales)
        registroAhora       = registroAhoraBase
        datosMeteorologicos.clear()
        datosMeteorologicos.addAll(datosBaseOriginales.map { it.registro })
    }

    /**
     * Recalcula la lista visible y el nodo Ahora aplicando las correcciones
     * manuales actuales sobre los datos base sin modificar de la API.
     *
     * Si no hay correcciones activas, se muestran los datos puros de la API.
     *
     * Propagación del error:
     *   error(t) = error_max × (12 − |t − t_ahora|) / 12   si |t − t_ahora| ≤ 12 h
     *   error(t) = 0                                         si |t − t_ahora| > 12 h
     */
    private fun recalcularYActualizarListaCompleta() {
        if (datosBaseOriginales.isEmpty()) return

        val ahoraBase = registroAhoraBase

        // Sin correcciones activas o sin nodo Ahora: mostramos los datos puros
        if (ahoraBase == null || (ultimaTempManual == null && ultimaHumManual == null)) {
            registroAhora = ahoraBase
            datosMeteorologicos.clear()
            datosMeteorologicos.addAll(datosBaseOriginales.map { it.registro })
            return
        }

        // Error absoluto entre la medición del usuario y el valor base de la API
        val errorTemp = ultimaTempManual?.let { it - ahoraBase.temperatura } ?: 0.0
        val errorHum  = ultimaHumManual?.let  { it - ahoraBase.humedad }    ?: 0.0

        // ── Error en presión de vapor absoluta ────────────────────────────────
        // La humedad se propaga en espacio de presión de vapor (no en porcentaje)
        // para mantener coherencia termodinámica con la temperatura corregida.
        // Fórmula: e_real = e_sat(T) × (RH / 100)
        val eSatBase          = calcularPresionSaturacion(ahoraBase.temperatura)
        val eRealApiBase      = eSatBase * (ahoraBase.humedad / 100.0)
        val eSatCorr          = calcularPresionSaturacion(ahoraBase.temperatura + errorTemp)
        val eRealManual       = eSatCorr * ((ahoraBase.humedad + errorHum).coerceIn(0.0, 100.0) / 100.0)
        val deltaPresionVapor = eRealManual - eRealApiBase

        // ── Nodo Ahora corregido: recibe el 100 % del error ───────────────────
        registroAhora = ahoraBase.copy(
            temperatura = ahoraBase.temperatura + errorTemp,
            humedad     = (ahoraBase.humedad + errorHum).coerceIn(0.0, 100.0)
        )

        // Timestamp del nodo Ahora para calcular distancias temporales en la lista
        val sdfIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
        val tiempoAhoraMs = try {
            sdfIso.parse(ahoraBase.fechaCronologica)?.time
        } catch (_: Exception) { null }

        // ── Propagación del error a la lista con atenuación lineal ────────────
        val listaCorregida = datosBaseOriginales.map { nodo ->
            val reg      = nodo.registro
            val tiempoMs = nodo.tiempoMs

            if (tiempoMs == null || tiempoAhoraMs == null) return@map reg

            val difHoras = abs(tiempoMs - tiempoAhoraMs).toDouble() / (1000.0 * 60.0 * 60.0)

            if (difHoras > 12.0) {
                reg  // Fuera de la ventana de 12 h: sin corrección
            } else {
                val factor    = (12.0 - difHoras) / 12.0
                val nuevaTemp = reg.temperatura + errorTemp * factor

                // Reconversión de la corrección de vapor a humedad relativa
                val eSatFila  = calcularPresionSaturacion(reg.temperatura)
                val eRealFila = eSatFila * (reg.humedad / 100.0)
                val eRealCorr = (eRealFila + deltaPresionVapor * factor).coerceAtLeast(0.0)
                val eSatNueva = calcularPresionSaturacion(nuevaTemp)
                val nuevaHum  = ((eRealCorr / eSatNueva) * 100.0).coerceIn(0.0, 100.0)

                reg.copy(temperatura = nuevaTemp, humedad = nuevaHum)
            }
        }

        datosMeteorologicos.clear()
        datosMeteorologicos.addAll(listaCorregida)
    }

    /**
     * Calcula el nodo "Ahora" interpolando entre T1 (hora en punto actual)
     * y T2 (hora siguiente), ponderado por los minutos transcurridos desde T1.
     *
     * Devuelve null si T1 no está en los datos cargados (el rango no cubre
     * el momento presente), en cuyo caso la fila fija no se muestra en la UI.
     */
    private fun calcularNodoAhora(datos: List<RegistroMeteorUniforme>): RegistroMeteorologico? {
        val ahora = Calendar.getInstance()

        val sdfHoraEnPunto = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.getDefault())
        val sdfIso         = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
        val sdfDiaMes      = SimpleDateFormat("dd/MM",              Locale.getDefault())
        val sdfHoraExacta  = SimpleDateFormat("HH:mm",              Locale.getDefault())

        // T1: hora en punto actual  (ej. "2026-05-25T11:00" si son las 11:23)
        val horaT1Iso = sdfHoraEnPunto.format(ahora.time)
        // T2: hora en punto siguiente (ej. "2026-05-25T12:00")
        val calT2     = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
        val horaT2Iso = sdfHoraEnPunto.format(calT2.time)

        // Si T1 no está en los datos cargados no podemos interpolar
        val nodoT1 = datos.find { it.registro.fechaCronologica == horaT1Iso }?.registro
            ?: return null

        val nodoT2 = datos.find { it.registro.fechaCronologica == horaT2Iso }?.registro

        // factor = fracción de hora transcurrida (0.0 en HH:00, ~1.0 en HH:59)
        val factor        = ahora.get(Calendar.MINUTE) / 60.0
        val claveAhoraIso = sdfIso.format(ahora.time)
        val etiquetaHora  = "${sdfDiaMes.format(ahora.time)} ${sdfHoraExacta.format(ahora.time)} (Ahora)"

        return if (nodoT2 != null) {
            // Interpolación lineal entre T1 y T2 ponderada por los minutos actuales
            RegistroMeteorologico(
                fechaCronologica = claveAhoraIso,
                hora             = etiquetaHora,
                temperatura      = nodoT1.temperatura     + factor * (nodoT2.temperatura     - nodoT1.temperatura),
                velocidadViento  = nodoT1.velocidadViento + factor * (nodoT2.velocidadViento - nodoT1.velocidadViento),
                humedad          = nodoT1.humedad          + factor * (nodoT2.humedad          - nodoT1.humedad),
                precipitacion    = nodoT1.precipitacion    + factor * (nodoT2.precipitacion    - nodoT1.precipitacion),
                tipo             = TipoRegistro.ACTUAL
            )
        } else {
            // T2 no disponible (borde del rango cargado): usamos T1 sin interpolación
            RegistroMeteorologico(
                fechaCronologica = claveAhoraIso,
                hora             = etiquetaHora,
                temperatura      = nodoT1.temperatura,
                velocidadViento  = nodoT1.velocidadViento,
                humedad          = nodoT1.humedad,
                precipitacion    = nodoT1.precipitacion,
                tipo             = TipoRegistro.ACTUAL
            )
        }
    }

    // Fórmula de Tetens: presión de saturación de vapor en milibares
    // e_sat(t) = 6.112 × exp((17.625 × t) / (243.04 + t))
    private fun calcularPresionSaturacion(t: Double): Double {
        val a = 17.625
        val b = 243.04
        return 6.112 * exp((a * t) / (b + t))
    }

    /**
     * Empareja cada registro con su timestamp en milisegundos para calcular
     * distancias temporales en la propagación del error. Usa [RegistroMeteorologico.fechaCronologica]
     * (formato ISO completo con año) y no el campo visual [RegistroMeteorologico.hora],
     * para evitar ambigüedades en cambios de año y simplificar el parseo.
     */
    private data class RegistroMeteorUniforme(
        val registro: RegistroMeteorologico,
        val tiempoMs: Long?
    )

    private fun reconstruirLineaTemporal(datos: List<RegistroMeteorologico>): List<RegistroMeteorUniforme> {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
        return datos.map { reg ->
            val tiempoMs = try { parser.parse(reg.fechaCronologica)?.time } catch (_: Exception) { null }
            RegistroMeteorUniforme(reg, tiempoMs)
        }
    }
}

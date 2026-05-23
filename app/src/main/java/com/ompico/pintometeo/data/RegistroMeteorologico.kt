package com.ompico.pintometeo.data

import java.util.Locale
import kotlin.math.ln

enum class TipoRegistro { HISTORICO, ACTUAL, PREVISION }

data class RegistroMeteorologico(
    val fechaCronologica: String, // Almacena el formato ISO "yyyy-MM-dd'T'HH:mm" indispensable para ordenar
    val hora: String,             // Almacena el formato visual "dd/MM HH:mm" para presentar en la tabla
    val temperatura: Double,
    val velocidadViento: Double,
    val humedad: Double,
    val precipitacion: Double,
    val tipo: TipoRegistro
) {
    val puntoRocio: Double
        get() = calcularPuntoRocioFórmula(temperatura, humidityCoerced)

    val esFavorableParaPintar: Boolean
        get() {
            val tempEstresada = temperatura - 1.5
            val humedadEstresada = (humidityCoerced + 6.0).coerceAtMost(100.0)

            val puntoRocioCritico = calcularPuntoRocioFórmula(tempEstresada, humedadEstresada)
            return temperatura >= (puntoRocioCritico + 3.0) && precipitacion == 0.0
        }

    private val humidityCoerced: Double
        get() = humedad.coerceIn(1.0, 100.0)

    private fun calcularPuntoRocioFórmula(t: Double, rh: Double): Double {
        val a = 17.625
        val b = 243.04
        val alpha = ln(rh / 100.0) + ((a * t) / (b + t))
        return (b * alpha) / (a - alpha)
    }

    fun formatDouble(value: Double): String {
        return String.format(Locale("es", "ES"), "%.1f", value)
    }
}
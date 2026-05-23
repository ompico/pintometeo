package com.ompico.pintometeo.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

import com.ompico.pintometeo.data.RegistroMeteorologico

object CsvExporter {
    fun compartirDatos(context: Context, lista: List<RegistroMeteorologico>) {
        val fileName = "registro_meteorologico.csv"
        val file = File(context.cacheDir, fileName)
        
        file.printWriter().use { out ->
            out.println("Hora;Temp. (ºC);Vel. Viento (km/h);Humedad (%);Precip. (mm);Punto Rocío (ºC)")
            
            lista.forEach { reg ->
                out.println(
                    "${reg.hora};" +
                    "${reg.formatDouble(reg.temperatura)};" +
                    "${reg.formatDouble(reg.velocidadViento)};" +
                    "${reg.formatDouble(reg.humedad)};" +
                    "${reg.formatDouble(reg.precipitacion)};" +
                    reg.formatDouble(reg.puntoRocio)
                )
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir CSV usando:"))
    }
}

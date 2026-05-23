package com.ompico.pintometeo

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.android.gms.location.LocationServices
import com.ompico.pintometeo.ui.PantallaMeteorologica
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val coroutineScope = rememberCoroutineScope()

                    val latitudDispositivo  = remember { mutableStateOf("36.9061") }
                    val longitudDispositivo = remember { mutableStateOf("-4.7631") }

                    val sdf      = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val calAyer  = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                    val calHoy   = Calendar.getInstance()
                    val ayer     = sdf.format(calAyer.time)
                    val hoy      = sdf.format(calHoy.time)

                    // Carga inicial con las coordenadas por defecto en cuanto arranca la app,
                    // sin esperar a los permisos de GPS. Si el GPS se concede después, se
                    // relanza la consulta con las coordenadas reales del dispositivo.
                    LaunchedEffect(Unit) {
                        coroutineScope.launch {
                            ControladorMeteorologico.ejecutarConsulta(
                                latitudDispositivo.value.toDouble(),
                                longitudDispositivo.value.toDouble(),
                                ayer, hoy
                            )
                        }
                    }

                    val locationPermissionState = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)

                    LaunchedEffect(locationPermissionState.status.isGranted) {
                        if (locationPermissionState.status.isGranted) {
                            obtenerUbicacion { lat, lon ->
                                latitudDispositivo.value  = lat.toString()
                                longitudDispositivo.value = lon.toString()

                                coroutineScope.launch {
                                    ControladorMeteorologico.ejecutarConsulta(lat, lon, ayer, hoy)
                                }
                            }
                        } else {
                            locationPermissionState.launchPermissionRequest()
                        }
                    }

                    PantallaMeteorologica(
                        datos                        = ControladorMeteorologico.datosMeteorologicos,
                        latitudInicial               = latitudDispositivo.value,
                        longitudInicial              = longitudDispositivo.value,
                        onConsultar                  = { lat, lon, fInicio, fFin ->
                            coroutineScope.launch {
                                ControladorMeteorologico.ejecutarConsulta(lat, lon, fInicio, fFin)
                            }
                        },
                        onCambioTemperaturaTermometro = { nuevaTempManual ->
                            ControladorMeteorologico.aplicarCambioTemperatura(nuevaTempManual)
                        },
                        onCambioHumedadHigrometro    = { nuevaHumManual ->
                            ControladorMeteorologico.aplicarCambioHumedad(nuevaHumManual)
                        }
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun obtenerUbicacion(onCoordenadasListas: (Double, Double) -> Unit) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                onCoordenadasListas(location.latitude, location.longitude)
            }
        }
    }
}

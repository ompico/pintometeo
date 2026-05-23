package com.ompico.pintometeo.ui

import android.app.DatePickerDialog
import android.content.res.Configuration
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

import com.ompico.pintometeo.ControladorMeteorologico
import com.ompico.pintometeo.data.RegistroMeteorologico
import com.ompico.pintometeo.data.TipoRegistro
import com.ompico.pintometeo.utils.CsvExporter
import java.text.SimpleDateFormat
import java.util.*

val ColorHistoricoPar = Color(0xFFF0F4F8)
val ColorHistoricoImpar = Color(0xFFFFFFFF)
val ColorActualBase = Color(0xFFFFF2CC)     
val ColorPrevisionPar = Color(0xFFE2F0D9) 
val ColorPrevisionImpar = Color(0xFFF2F9EE)

val ColorPintarVerde = Color(0xFF2E7D32)
val ColorNoPintarRojo = Color(0xFFC62828)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaMeteorologica(
    datos: List<RegistroMeteorologico>,
    latitudInicial: String,
    longitudInicial: String,
    onConsultar: (Double, Double, String, String) -> Unit,
    onCambioTemperaturaTermometro: (Double?) -> Unit,
    onCambioHumedadHigrometro: (Double?) -> Unit
) {
    val context = LocalContext.current
    val orientation = LocalConfiguration.current.orientation
    val scrollStateControlesVertical = rememberScrollState()
    val focusManager = LocalFocusManager.current
    
    val datosReactivos = remember(datos.size, datos.hashCode()) { datos }

    var menuDesplegado by remember { mutableStateOf(false) }
    var mostrarDialogoAyuda by remember { mutableStateOf(false) }
    var mostrarDialogoInformacion by remember { mutableStateOf(false) }

    var inputLat by remember { mutableStateOf(latitudInicial) }
    var inputLon by remember { mutableStateOf(longitudInicial) }

    LaunchedEffect(latitudInicial, longitudInicial) {
        inputLat = latitudInicial
        inputLon = longitudInicial
    }

    var textoTempEditable by remember { mutableStateOf("") }
    var textoHumEditable by remember { mutableStateOf("") }

    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val sdfVisual = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    val calendarAyer = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val ayerStr = sdf.format(calendarAyer.time)
    val calendarHoy = Calendar.getInstance()
    val hoyStr = sdf.format(calendarHoy.time)
    
    var fechaInicio by remember { mutableStateOf(ayerStr) }
    var fechaFin by remember { mutableStateOf(hoyStr) }

    fun formatearAVisual(fechaAnioMesDia: String): String {
        return try {
            val date = sdf.parse(fechaAnioMesDia)
            if (date != null) sdfVisual.format(date) else fechaAnioMesDia
        } catch (e: Exception) {
            fechaAnioMesDia
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("PintoMeteo", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                actions = {
                    Box {
                        IconButton(onClick = { menuDesplegado = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menú Opciones")
                        }
                        DropdownMenu(
                            expanded = menuDesplegado,
                            onDismissRequest = { menuDesplegado = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Ayuda") },
                                onClick = {
                                    menuDesplegado = false
                                    mostrarDialogoAyuda = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Información") },
                                onClick = {
                                    menuDesplegado = false
                                    mostrarDialogoInformacion = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.38f)
                            .fillMaxHeight()
                            .verticalScroll(scrollStateControlesVertical)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextField(
                            value = inputLat, 
                            onValueChange = { inputLat = it }, 
                            label = { Text("Latitud") }, 
                            singleLine = true, 
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextField(
                            value = inputLon, 
                            onValueChange = { inputLon = it }, 
                            label = { Text("Longitud") }, 
                            singleLine = true, 
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Button(
                            onClick = {
                                val cal = Calendar.getInstance().apply {
                                    try { sdf.parse(fechaInicio)?.let { time = it } } catch (_: Exception) {}
                                }
                                DatePickerDialog(context, { _, year, month, dayOfMonth ->
                                    val sel = Calendar.getInstance().apply { set(year, month, dayOfMonth) }
                                    fechaInicio = sdf.format(sel.time)
                                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                            }, 
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Desde: ${formatearAVisual(fechaInicio)}", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                val cal = Calendar.getInstance().apply {
                                    try { sdf.parse(fechaFin)?.let { time = it } } catch (_: Exception) {}
                                }
                                DatePickerDialog(context, { _, year, month, dayOfMonth ->
                                    val sel = Calendar.getInstance().apply { set(year, month, dayOfMonth) }
                                    fechaFin = sdf.format(sel.time)
                                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                            }, 
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Hasta: ${formatearAVisual(fechaFin)}", fontSize = 12.sp)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = textoTempEditable,
                                onValueChange = { 
                                    textoTempEditable = it
                                    onCambioTemperaturaTermometro(it.toDoubleOrNull())
                                },
                                label = { Text("Temp. (ºC)", fontSize = 11.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                            )

                            OutlinedTextField(
                                value = textoHumEditable,
                                onValueChange = { 
                                    textoHumEditable = it
                                    onCambioHumedadHigrometro(it.toDoubleOrNull()) 
                                },
                                label = { Text("Hum. (%)", fontSize = 11.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = {
                                val lat = inputLat.toDoubleOrNull()
                                val lon = inputLon.toDoubleOrNull()
                                if (lat != null && lon != null) onConsultar(lat, lon, fechaInicio, fechaFin)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Consultar")
                        }

                        Button(
                            onClick = { CsvExporter.compartirDatos(context, datosReactivos) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = datosReactivos.isNotEmpty() && !ControladorMeteorologico.cargando
                        ) {
                            Text("Compartir CSV")
                        }
                    }

                    BoxWithConstraints(modifier = Modifier.weight(0.62f).fillMaxHeight()) {
                        val anchoCalculado = maxWidth / 6
                        if (ControladorMeteorologico.cargando) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Actualizando datos de Open-Meteo...", fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        } else {
                            TablaMeteorologica(datosReactivos, anchoCalculado)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        TextField(
                            value = inputLat, onValueChange = { inputLat = it }, 
                            label = { Text("Latitud", fontSize = 11.sp) }, 
                            modifier = Modifier.weight(1f).padding(end = 4.dp), singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                        )
                        TextField(
                            value = inputLon, onValueChange = { inputLon = it }, 
                            label = { Text("Longitud", fontSize = 11.sp) }, 
                            modifier = Modifier.weight(1f).padding(start = 4.dp), singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), 
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val cal = Calendar.getInstance().apply {
                                    try { sdf.parse(fechaInicio)?.let { time = it } } catch (_: Exception) {}
                                }
                                DatePickerDialog(context, { _, year, month, dayOfMonth ->
                                    val sel = Calendar.getInstance().apply { set(year, month, dayOfMonth) }
                                    fechaInicio = sdf.format(sel.time)
                                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                            }, 
                            modifier = Modifier.weight(1f), 
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { 
                            Text("Inic: ${formatearAVisual(fechaInicio)}", fontSize = 10.sp) 
                        }

                        Button(
                            onClick = {
                                val cal = Calendar.getInstance().apply {
                                    try { sdf.parse(fechaFin)?.let { time = it } } catch (_: Exception) {}
                                }
                                DatePickerDialog(context, { _, year, month, dayOfMonth ->
                                    val sel = Calendar.getInstance().apply { set(year, month, dayOfMonth) }
                                    fechaFin = sdf.format(sel.time)
                                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                            }, 
                            modifier = Modifier.weight(1f), 
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { 
                            Text("Fin: ${formatearAVisual(fechaFin)}", fontSize = 10.sp) 
                        }

                        Button(
                            onClick = {
                                val lat = inputLat.toDoubleOrNull()
                                val lon = inputLon.toDoubleOrNull()
                                if (lat != null && lon != null) onConsultar(lat, lon, fechaInicio, fechaFin)
                            },
                            modifier = Modifier.weight(0.6f), contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text("Ir", fontSize = 11.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = textoTempEditable,
                            onValueChange = { 
                                textoTempEditable = it
                                onCambioTemperaturaTermometro(it.toDoubleOrNull())
                            },
                            label = { Text("Temp. Termómetro (ºC)", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                        )

                        OutlinedTextField(
                            value = textoHumEditable,
                            onValueChange = { 
                                textoHumEditable = it
                                onCambioHumedadHigrometro(it.toDoubleOrNull())
                            },
                            label = { Text("Hum. Aparato (%)", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                        )
                    }

                    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val anchoCalculado = maxWidth / 6
                        if (ControladorMeteorologico.cargando) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Actualizando datos de Open-Meteo...", fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        } else {
                            TablaMeteorologica(datosReactivos, anchoCalculado)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = { CsvExporter.compartirDatos(context, datosReactivos) }, 
                        modifier = Modifier.fillMaxWidth(), enabled = datosReactivos.isNotEmpty() && !ControladorMeteorologico.cargando,
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("Compartir en CSV", fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (mostrarDialogoAyuda) {
        AlertDialog(
            onDismissRequest = { mostrarDialogoAyuda = false },
            title = { Text("Ayuda de la Aplicación") },
            text = {
                Box(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewClient = WebViewClient()
                                settings.allowFileAccess = true
                                loadUrl("file:///android_asset/ayuda.html")
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { mostrarDialogoAyuda = false }) {
                    Text("Cerrar")
                }
            }
        )
    }

    if (mostrarDialogoInformacion) {
        AlertDialog(
            onDismissRequest = { mostrarDialogoInformacion = false },
            title = { Text("Información y Contacto") },
            text = {
                Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewClient = WebViewClient()
                                settings.allowFileAccess = true
                                loadUrl("file:///android_asset/informacion.html")
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { mostrarDialogoInformacion = false }) {
                    Text("Entendido")
                }
            }
        )
    }
}

@Composable
fun TablaMeteorologica(datos: List<RegistroMeteorologico>, anchoColumna: Dp) {
    Column(modifier = Modifier.fillMaxSize()) {
        CabeceraTabla(anchoColumna)
        if (datos.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No hay datos.", color = Color.Gray, fontSize = 12.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(datos) { index, registro ->
                    FilaMeteorologica(
                        index = index, 
                        registro = registro, 
                        anchoColumna = anchoColumna, 
                        sePuedePintar = registro.esFavorableParaPintar
                    )
                }
            }
        }
    }
}

@Composable
fun CabeceraTabla(anchoColumna: Dp) {
    Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(vertical = 6.dp)) {
        val campos = listOf("Hora" to "", "Temp." to "ºC", "Vto." to "km/h", "Hum." to "%", "Precip." to "mm", "Rocío" to "ºC")
        campos.forEach { (titulo, unidad) ->
            Column(modifier = Modifier.width(anchoColumna), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(titulo, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                if (unidad.isNotEmpty()) {
                    Text(unidad, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, textAlign = TextAlign.Center)
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun FilaMeteorologica(index: Int, registro: RegistroMeteorologico, anchoColumna: Dp, sePuedePintar: Boolean) {
    val backgroundColor = when (registro.tipo) {
        TipoRegistro.ACTUAL -> if (sePuedePintar) ColorPintarVerde else ColorNoPintarRojo
        TipoRegistro.HISTORICO -> if (index % 2 == 0) ColorHistoricoPar else ColorHistoricoImpar
        TipoRegistro.PREVISION -> if (index % 2 == 0) ColorPrevisionPar else ColorPrevisionImpar
    }
    val textColor = if (registro.tipo == TipoRegistro.ACTUAL) Color.White else Color.Black

    Row(
        modifier = Modifier.fillMaxWidth().background(backgroundColor).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val celdas = listOf(
            registro.hora,
            registro.formatDouble(registro.temperatura),
            registro.formatDouble(registro.velocidadViento),
            "${registro.humedad.toInt()}", 
            registro.formatDouble(registro.precipitacion),
            registro.formatDouble(registro.puntoRocio)
        )
        celdas.forEach { texto ->
            Text(
                text = texto, modifier = Modifier.width(anchoColumna), textAlign = TextAlign.Center, fontSize = 11.sp,
                color = textColor, fontWeight = if (registro.tipo == TipoRegistro.ACTUAL) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
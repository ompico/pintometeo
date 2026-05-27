package com.ompico.pintometeo.ui

import android.app.DatePickerDialog
import android.content.res.Configuration
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.foundation.clickable

import com.ompico.pintometeo.ControladorMeteorologico
import com.ompico.pintometeo.data.RegistroMeteorologico
import com.ompico.pintometeo.data.TipoRegistro
import com.ompico.pintometeo.utils.CsvExporter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ── Paleta de colores de la tabla ─────────────────────────────────────────────
val ColorHistoricoPar   = Color(0xFFF0F4F8)  // Filas pares de datos históricos
val ColorHistoricoImpar = Color(0xFFFFFFFF)  // Filas impares de datos históricos
val ColorPrevisionPar   = Color(0xFFE2F0D9)  // Filas pares de predicciones
val ColorPrevisionImpar = Color(0xFFF2F9EE)  // Filas impares de predicciones
val ColorPintarVerde    = Color(0xFF2E7D32)  // Fila Ahora: condiciones favorables para pintar
val ColorNoPintarRojo   = Color(0xFFC62828)  // Fila Ahora: condiciones desfavorables para pintar

// ─────────────────────────────────────────────────────────────────────────────
// PANTALLA PRINCIPAL
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Composable raíz de la aplicación. Gestiona la barra superior, los controles
 * de entrada (coordenadas, fechas, ajustes manuales) y la tabla meteorológica.
 *
 * La tabla tiene dos partes:
 *  1. Fila "Ahora" fija inamovible bajo la cabecera: siempre visible,
 *     proviene de [ControladorMeteorologico.registroAhora].
 *  2. LazyColumn desplazable con los registros horarios (HISTORICO / PREVISION).
 *
 * Los controles de fecha (Inic/Fin y Desde/Hasta) solo actúan al pulsar
 * el botón Ir/Consultar. La carga dinámica de scroll es independiente de ellos.
 */
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
    val context            = LocalContext.current
    val orientation        = LocalConfiguration.current.orientation
    val scrollControles    = rememberScrollState()
    val focusManager       = LocalFocusManager.current
    val coroutineScope     = rememberCoroutineScope()

    // Estado de scroll de la LazyColumn, necesario para la detección de extremos
    val listState = rememberLazyListState()

    val datosReactivos = remember(datos.size, datos.hashCode()) { datos }

    var menuDesplegado            by remember { mutableStateOf(false) }
    var mostrarDialogoAyuda       by remember { mutableStateOf(false) }
    var mostrarDialogoInformacion by remember { mutableStateOf(false) }

    var inputLat by remember { mutableStateOf(latitudInicial) }
    var inputLon by remember { mutableStateOf(longitudInicial) }

    // Sincroniza los campos de texto con las coordenadas cuando llegan del GPS
    LaunchedEffect(latitudInicial, longitudInicial) {
        inputLat = latitudInicial
        inputLon = longitudInicial
    }

    var textoTempEditable by remember { mutableStateOf("") }
    var textoHumEditable  by remember { mutableStateOf("") }

    val sdf       = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val sdfVisual = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

    // Valores por defecto de los selectores de fecha: ayer y hoy.
    // Coinciden con la ventana que carga inicializarVentana(), así que los botones
    // muestran información coherente con lo que hay en la tabla al arrancar.
    // Estos valores solo importan cuando el usuario pulsa Ir/Consultar.
    var fechaInicio by remember { mutableStateOf(sdf.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time)) }
    var fechaFin    by remember { mutableStateOf(sdf.format(Calendar.getInstance().time)) }

    fun formatearAVisual(f: String): String = try {
        sdf.parse(f)?.let { sdfVisual.format(it) } ?: f
    } catch (_: Exception) { f }

    // Callbacks de carga incremental: los lanza la TablaMeteorologica cuando
    // detecta que el usuario llega al extremo superior o inferior del scroll.
    val onNecesitaMasAntiguo: () -> Unit = {
        coroutineScope.launch { ControladorMeteorologico.ampliarHaciaElPasado() }
    }
    val onNecesitaMasReciente: () -> Unit = {
        coroutineScope.launch { ControladorMeteorologico.ampliarHaciaElFuturo() }
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
                                onClick = { menuDesplegado = false; mostrarDialogoAyuda = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Información") },
                                onClick = { menuDesplegado = false; mostrarDialogoInformacion = true }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor         = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor      = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                // ── Modo horizontal ───────────────────────────────────────────
                Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {

                    // Panel izquierdo (38 %): controles desplazables verticalmente
                    Column(
                        modifier = Modifier
                            .weight(0.38f).fillMaxHeight()
                            .verticalScroll(scrollControles)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextField(
                            value = inputLat, onValueChange = { inputLat = it },
                            label = { Text("Latitud") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextField(
                            value = inputLon, onValueChange = { inputLon = it },
                            label = { Text("Longitud") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                val cal = Calendar.getInstance().apply {
                                    try { sdf.parse(fechaInicio)?.let { time = it } } catch (_: Exception) {}
                                }
                                DatePickerDialog(context, { _, y, m, d ->
                                    fechaInicio = sdf.format(Calendar.getInstance().apply { set(y, m, d) }.time)
                                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Desde: ${formatearAVisual(fechaInicio)}", fontSize = 12.sp) }

                        Button(
                            onClick = {
                                val cal = Calendar.getInstance().apply {
                                    try { sdf.parse(fechaFin)?.let { time = it } } catch (_: Exception) {}
                                }
                                DatePickerDialog(context, { _, y, m, d ->
                                    fechaFin = sdf.format(Calendar.getInstance().apply { set(y, m, d) }.time)
                                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Hasta: ${formatearAVisual(fechaFin)}", fontSize = 12.sp) }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = textoTempEditable,
                                onValueChange = { textoTempEditable = it; onCambioTemperaturaTermometro(it.toDoubleOrNull()) },
                                label = { Text("Temp. (ºC)", fontSize = 11.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                singleLine = true, modifier = Modifier.weight(1f),
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                            )
                            OutlinedTextField(
                                value = textoHumEditable,
                                onValueChange = { textoHumEditable = it; onCambioHumedadHigrometro(it.toDoubleOrNull()) },
                                label = { Text("Hum. (%)", fontSize = 11.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                singleLine = true, modifier = Modifier.weight(1f),
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
                        ) { Text("Consultar") }

                        Button(
                            onClick = { CsvExporter.compartirDatos(context, datosReactivos) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = datosReactivos.isNotEmpty() && !ControladorMeteorologico.cargando
                        ) { Text("Compartir CSV") }
                    }

                    // Panel derecho (62 %): tabla meteorológica
                    BoxWithConstraints(modifier = Modifier.weight(0.62f).fillMaxHeight()) {
                        val anchoCalculado = maxWidth / 6
                        if (ControladorMeteorologico.cargando) {
                            IndicadorCarga()
                        } else {
                            TablaMeteorologica(
                                datos                 = datosReactivos,
                                anchoColumna          = anchoCalculado,
                                listState             = listState,
                                onNecesitaMasAntiguo  = onNecesitaMasAntiguo,
                                onNecesitaMasReciente = onNecesitaMasReciente
                            )
                        }
                    }
                }

            } else {
                // ── Modo vertical ─────────────────────────────────────────────
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {

                    // Fila de coordenadas
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

                    // Fila de fechas y botón Ir
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
                                DatePickerDialog(context, { _, y, m, d ->
                                    fechaInicio = sdf.format(Calendar.getInstance().apply { set(y, m, d) }.time)
                                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Inic: ${formatearAVisual(fechaInicio)}", fontSize = 10.sp) }

                        Button(
                            onClick = {
                                val cal = Calendar.getInstance().apply {
                                    try { sdf.parse(fechaFin)?.let { time = it } } catch (_: Exception) {}
                                }
                                DatePickerDialog(context, { _, y, m, d ->
                                    fechaFin = sdf.format(Calendar.getInstance().apply { set(y, m, d) }.time)
                                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Fin: ${formatearAVisual(fechaFin)}", fontSize = 10.sp) }

                        // El botón Ir es el único que dispara una consulta de rango explícita
                        Button(
                            onClick = {
                                val lat = inputLat.toDoubleOrNull()
                                val lon = inputLon.toDoubleOrNull()
                                if (lat != null && lon != null) onConsultar(lat, lon, fechaInicio, fechaFin)
                            },
                            modifier = Modifier.weight(0.6f),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Ir", fontSize = 11.sp) }
                    }

                    // Fila de ajustes manuales del termómetro e higrómetro
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = textoTempEditable,
                            onValueChange = { textoTempEditable = it; onCambioTemperaturaTermometro(it.toDoubleOrNull()) },
                            label = { Text("Temp. Termómetro (ºC)", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            singleLine = true, modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                        )
                        OutlinedTextField(
                            value = textoHumEditable,
                            onValueChange = { textoHumEditable = it; onCambioHumedadHigrometro(it.toDoubleOrNull()) },
                            label = { Text("Hum. Aparato (%)", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            singleLine = true, modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                        )
                    }

                    // Área de la tabla: ocupa todo el espacio restante
                    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val anchoCalculado = maxWidth / 6
                        if (ControladorMeteorologico.cargando) {
                            IndicadorCarga()
                        } else {
                            TablaMeteorologica(
                                datos                 = datosReactivos,
                                anchoColumna          = anchoCalculado,
                                listState             = listState,
                                onNecesitaMasAntiguo  = onNecesitaMasAntiguo,
                                onNecesitaMasReciente = onNecesitaMasReciente
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { CsvExporter.compartirDatos(context, datosReactivos) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = datosReactivos.isNotEmpty() && !ControladorMeteorologico.cargando,
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) { Text("Compartir en CSV", fontSize = 13.sp) }
                }
            }
        }
    }

    // ── Diálogos ──────────────────────────────────────────────────────────────

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
                TextButton(onClick = { mostrarDialogoAyuda = false }) { Text("Cerrar") }
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
                TextButton(onClick = { mostrarDialogoInformacion = false }) { Text("Entendido") }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TABLA METEOROLÓGICA
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Composable que agrupa la cabecera fija, la fila "Ahora" fija y la lista
 * desplazable de registros horarios.
 *
 * Estructura visual (de arriba a abajo):
 *   CabeceraTabla          ← siempre visible, nunca se desplaza
 *   FilaMeteorologica(Ahora) ← siempre visible si el rango cubre el presente
 *   LazyColumn             ← desplazable, contiene HISTORICO y PREVISION
 *
 * La detección de los extremos del scroll dispara las cargas incrementales:
 *   · Llegar a 5 items del fondo → [onNecesitaMasAntiguo] (más pasado)
 *   · Llegar a 3 items del tope  → [onNecesitaMasReciente] (más futuro)
 */
@Composable
fun TablaMeteorologica(
    datos: List<RegistroMeteorologico>,
    anchoColumna: Dp,
    listState: LazyListState,
    onNecesitaMasAntiguo: () -> Unit,
    onNecesitaMasReciente: () -> Unit
) {

    var registroSeleccionado by remember { mutableStateOf<RegistroMeteorologico?>(null) }
    
    // ── Detección de scroll cerca del fondo → cargar más pasado ───────────────
    // snapshotFlow convierte el estado de scroll en un Flow observable.
    // distinctUntilChanged + filter garantizan un único disparo al entrar en
    // la zona, no en cada frame mientras el usuario permanece en ella.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info        = listState.layoutInfo
            val total       = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 5
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                // La guarda cargandoMasAntiguo evita lanzar consultas duplicadas
                if (!ControladorMeteorologico.cargandoMasAntiguo && !ControladorMeteorologico.cargando) {
                    onNecesitaMasAntiguo()
                }
            }
    }

    // ── Detección de scroll cerca del tope → cargar más futuro ────────────────
    // drop(1) descarta el valor inicial al suscribirse para evitar un disparo
    // espurio antes de que el usuario haya interactuado con la lista.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .drop(1)
            .distinctUntilChanged()
            .filter { it <= 3 }
            .collect {
                if (!ControladorMeteorologico.cargandoMasReciente && !ControladorMeteorologico.cargando) {
                    onNecesitaMasReciente()
                }
            }
    }

    val context = LocalContext.current  // ya existe, no duplicar

    LaunchedEffect(ControladorMeteorologico.avisoLimiteApi) {
        val mensaje = ControladorMeteorologico.avisoLimiteApi
        if (mensaje != null) {
            Toast.makeText(context, mensaje, Toast.LENGTH_LONG).show()
            ControladorMeteorologico.avisoLimiteApi = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // Cabecera con los nombres de columna: siempre visible
        CabeceraTabla(anchoColumna)

        // Fila "Ahora" fija: siempre visible bajo la cabecera si el rango lo incluye.
        // Al estar fuera de la LazyColumn no se mezcla con el scroll ni puede
        // aparecer en posiciones incorrectas al cargar nuevos datos.
        ControladorMeteorologico.registroAhora?.let { ahora ->
            FilaMeteorologica(
                registro      = ahora,
                anchoColumna  = anchoColumna,
                sePuedePintar = ahora.esFavorableParaPintar
                onClick       = { registroSeleccionado = ahora }
            )
        }

        // Lista desplazable con los registros horarios (HISTORICO y PREVISION)
        if (datos.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No hay datos.", color = Color.Gray, fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                state    = listState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                // Indicador de carga pequeño al tope de la lista mientras se amplía hacia el futuro
                if (ControladorMeteorologico.cargandoMasReciente) {
                    item(key = "__loading_future__") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                // Los items usan key estable (fechaCronologica) para que LazyColumn
                // identifique correctamente cada elemento al insertar o reordenar,
                // evitando saltos de scroll y animaciones incorrectas.
                items(items = datos, key = { it.fechaCronologica }) { registro ->
                    FilaMeteorologica(
                        registro      = registro,
                        anchoColumna  = anchoColumna,
                        sePuedePintar = registro.esFavorableParaPintar
                    )
                }

                // Indicador de carga pequeño al fondo de la lista mientras se amplía hacia el pasado
                if (ControladorMeteorologico.cargandoMasAntiguo) {
                    item(key = "__loading_past__") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }

    registroSeleccionado?.let { reg ->
        DialogoDetalleRegistro(
            registro   = reg,
            onDismiss  = { registroSeleccionado = null }
        )
    }
    
}

// ─────────────────────────────────────────────────────────────────────────────
// COMPONENTES DE APOYO
// ─────────────────────────────────────────────────────────────────────────────

/** Indicador circular centrado mientras [ControladorMeteorologico.cargando] es true. */
@Composable
private fun IndicadorCarga() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Actualizando datos de Open-Meteo...", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

/** Fila de cabecera con los nombres y unidades de cada columna. */
@Composable
fun CabeceraTabla(anchoColumna: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(vertical = 6.dp)
    ) {
        val columnas = listOf(
            "Hora"    to "",
            "Temp."   to "ºC",
            "Vto."    to "km/h",
            "Hum."    to "%",
            "Precip." to "mm",
            "Rocío"   to "ºC"
        )
        columnas.forEach { (titulo, unidad) ->
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

/**
 * Fila de datos meteorológicos para un registro horario.
 *
 * El color de fondo depende del tipo de registro:
 *  - ACTUAL (nodo Ahora): verde si se puede pintar, rojo si no.
 *  - HISTORICO: blanco/gris azulado alterno.
 *  - PREVISION: verde claro alterno.
 *
 * La alternancia par/impar se calcula a partir de la hora en punto de
 * [fechaCronologica] en lugar de un índice de lista, para que sea estable
 * independientemente de cuántos items se inserten o eliminen a su alrededor.
 */
@Composable
fun FilaMeteorologica(
    registro: RegistroMeteorologico,
    anchoColumna: Dp,
    sePuedePintar: Boolean
    onClick: () -> Unit
) {
    // La hora siempre termina en ":00" para HISTORICO y PREVISION.
    // Tomamos el dígito de las decenas de la hora para alternar colores
    // de forma consistente: horas pares (00, 02, 04…) y horas impares (01, 03…).
    val esPar = try {
        registro.fechaCronologica.substring(11, 13).toInt() % 2 == 0
    } catch (_: Exception) { false }

    val backgroundColor = when (registro.tipo) {
        TipoRegistro.ACTUAL    -> if (sePuedePintar) ColorPintarVerde else ColorNoPintarRojo
        TipoRegistro.HISTORICO -> if (esPar) ColorHistoricoPar else ColorHistoricoImpar
        TipoRegistro.PREVISION -> if (esPar) ColorPrevisionPar else ColorPrevisionImpar
    }
    val textColor  = if (registro.tipo == TipoRegistro.ACTUAL) Color.White else Color.Black
    val fontWeight = if (registro.tipo == TipoRegistro.ACTUAL) FontWeight.Bold else FontWeight.Normal

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(backgroundColor)
            .padding(vertical = 10.dp),
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
                text       = texto,
                modifier   = Modifier.width(anchoColumna),
                textAlign  = TextAlign.Center,
                fontSize   = 11.sp,
                color      = textColor,
                fontWeight = fontWeight
            )
        }
    }
}

@Composable
fun DialogoDetalleRegistro(
    registro: RegistroMeteorologico,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text       = registro.hora,
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetalleLinea("Temperatura",   "${registro.formatDouble(registro.temperatura)} ºC")
                DetalleLinea("Viento",        "${registro.formatDouble(registro.velocidadViento)} km/h")
                DetalleLinea("Humedad",       "${registro.humedad.toInt()} %")
                DetalleLinea("Precipitación", "${registro.formatDouble(registro.precipitacion)} mm")
                DetalleLinea("Punto de Rocío","${registro.formatDouble(registro.puntoRocio)} ºC")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    )
}

@Composable
private fun DetalleLinea(etiqueta: String, valor: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(etiqueta, fontSize = 15.sp, color = Color.Gray)
        Text(valor,    fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}


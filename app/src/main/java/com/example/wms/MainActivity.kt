package com.example.wms

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.*
import com.google.accompanist.permissions.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

// --- 1. Data Layer (資料層) ---
@Entity(tableName = "inventory")
data class InventoryItem(@PrimaryKey val id: String, val name: String, val quantity: Int, val location: String)

@Entity(tableName = "orders")
data class Order(@PrimaryKey val id: String, val customer: String, val status: String, val itemsSummary: String)

@Dao
interface WmsDao {
    @Query("SELECT * FROM inventory ORDER BY id DESC")
    fun getAllInventory(): Flow<List<InventoryItem>>

    @Query("SELECT * FROM orders")
    fun getAllOrders(): Flow<List<Order>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInventory(item: InventoryItem): Long

    @Query("UPDATE inventory SET quantity = quantity + :addedQty WHERE id = :skuId")
    suspend fun addStock(skuId: String, addedQty: Int)

    @Transaction
    suspend fun upsertStock(item: InventoryItem) {
        if (insertInventory(item) == -1L) addStock(item.id, item.quantity)
    }
}

@Database(entities = [InventoryItem::class, Order::class], version = 7, exportSchema = false)
abstract class WmsDatabase : RoomDatabase() {
    abstract fun wmsDao(): WmsDao
    companion object {
        @Volatile private var INSTANCE: WmsDatabase? = null
        fun getDatabase(ctx: Context): WmsDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(ctx.applicationContext, WmsDatabase::class.java, "wms_v7_db")
                .fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}

// --- 2. Logic Layer (邏輯層) ---
class WmsViewModel(private val dao: WmsDao) : ViewModel() {
    val inventory = dao.getAllInventory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val orders = dao.getAllOrders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun scanIncoming(raw: String, onDone: (String) -> Unit) {
        val cleanData = raw.trim().uppercase()
        // 優先過濾 SKU- 格式，若無則直接使用原始字串(條碼數字)
        val sku = if (cleanData.contains("SKU-")) {
            Regex("SKU-[A-Z0-9-]+").find(cleanData)?.value ?: cleanData
        } else {
            cleanData
        }

        if (sku.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertStock(InventoryItem(sku, "掃描入庫: $sku", 1, "臨時位"))
            withContext(Dispatchers.Main) { onDone(sku) }
        }
    }
}

// --- 3. UI Layer (介面層) ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            var vm by remember { mutableStateOf<WmsViewModel?>(null) }

            LaunchedEffect(Unit) {
                val dao = withContext(Dispatchers.IO) { WmsDatabase.getDatabase(ctx).wmsDao() }
                vm = WmsViewModel(dao)
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    vm?.let { MainContent(it) } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
fun MainContent(vm: WmsViewModel) {
    var tab by remember { mutableIntStateOf(1) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                val tabs = listOf("進貨" to Icons.Default.CameraAlt, "訂單" to Icons.AutoMirrored.Filled.Assignment, "庫存" to Icons.Default.Warehouse)
                tabs.forEachIndexed { i, (l, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(l) })
                }
            }
        }
    ) { p ->
        Box(Modifier.padding(p)) {
            when(tab) {
                0 -> InboundScanView(vm)
                1 -> OrderListView(vm)
                2 -> InventoryListView(vm)
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun InboundScanView(vm: WmsViewModel) {
    val perm = rememberPermissionState(Manifest.permission.CAMERA)
    var active by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    if (active && perm.status.isGranted) {
        Box(Modifier.fillMaxSize()) {
            CameraPreviewAllInOne { result ->
                vm.scanIncoming(result) {
                    active = false
                    Toast.makeText(ctx, "儲存成功: $it", Toast.LENGTH_SHORT).show()
                }
            }
            Button(onClick = { active = false }, Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Text("返回")
            }
        }
    } else {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Button(onClick = { if(perm.status.isGranted) active = true else perm.launchPermissionRequest() }) {
                Text("啟動強力掃描 (支援條碼/文字)")
            }
        }
    }
}

@Composable
fun CameraPreviewAllInOne(onDetected: (String) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val exec = remember { Executors.newSingleThreadExecutor() }
    val ocrClient = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val barcodeScanner = remember { BarcodeScanning.getClient() }

    AndroidView(factory = { ctx ->
        PreviewView(ctx).apply {
            ProcessCameraProvider.getInstance(ctx).addListener({
                val provider = ProcessCameraProvider.getInstance(ctx).get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also {
                        it.setAnalyzer(exec) { proxy ->
                            val media = proxy.image
                            if (media != null) {
                                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                                // 雙引擎邏輯：優先條碼，次要文字
                                barcodeScanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        if (barcodes.isNotEmpty()) {
                                            barcodes.firstOrNull()?.rawValue?.let { onDetected(it) }
                                        } else {
                                            ocrClient.process(image).addOnSuccessListener { res ->
                                                if (res.text.isNotBlank()) onDetected(res.text)
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            } else proxy.close()
                        }
                    }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
        }
    }, Modifier.fillMaxSize())
}

@Composable
fun OrderListView(vm: WmsViewModel) {
    val orders by vm.orders.collectAsState()
    if (orders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("目前無訂單資料") }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(orders) { o ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    ListItem(headlineContent = { Text(o.id) }, supportingContent = { Text(o.customer) })
                }
            }
        }
    }
}

@Composable
fun InventoryListView(vm: WmsViewModel) {
    val items by vm.inventory.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        items(items) { i ->
            ListItem(
                headlineContent = { Text(i.id, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                supportingContent = { Text(i.name) },
                trailingContent = { Text("${i.quantity} PCS", color = MaterialTheme.colorScheme.primary) }
            )
        }
    }
}
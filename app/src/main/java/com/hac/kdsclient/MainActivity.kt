package com.hac.kdsclient

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.Firebase
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.database
import com.google.firebase.initialize
import com.hac.kdsclient.ui.theme.KdsClientTheme
import com.hac.protobuf.client.FirebaseVerticalSync
import com.hac.protobuf.client.KdsRpc
import com.hac.protobuf.client.KdsServer
import com.ncr.proto.gen.Order
import com.ncr.proto.gen.SyncRequest
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class Mode {
    Server,
    Client,
    Offline
}

class MainActivity : ComponentActivity() {
    //    private val kdsClient = KdsClient()
    //    private val default: SyncResponse = kdsClient.defaultResponse
    //    private var uri = Uri.parse("http://localhost:50078/")
    //    private val uri by lazy { Uri.parse("http://192.168.0.207:50078/") }
    private val uri by lazy { Uri.parse("http://10.0.2.2:50078/") }
    private lateinit var clientService : KdsRpc
    private lateinit var ref: DatabaseReference
    private lateinit var server: KdsServer
    private lateinit var deviceName: String
    private val mode = mutableStateOf(Mode.Client)
    private val orderSnapshotStateMap = mutableStateMapOf<String, Order>()

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Firebase.initialize(this)
        val db = Firebase.database
        db.setPersistenceEnabled(true)
        val currentDate = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE)
        deviceName = Settings.Global.getString(application.contentResolver, "device_name")
        ref = db.getReference(
            "data/${currentDate}"
        )
        clientService = KdsRpc(uri, ref)
        enableEdgeToEdge()
        setContent {
            KdsClientTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    val (isSelectionRequired, updateSelection) = remember {
                        mutableStateOf(true)
                    }
                    when (isSelectionRequired) {
                        true -> SelectionScreen(updateSelection)
                        false -> MainScreen()
                    }
                }
            }
        }
    }

    @SuppressLint("CoroutineCreationDuringComposition")
    @Composable
    private fun MainScreen() {
        if (mode.value == Mode.Offline){
            FirebaseVerticalSync.startVerticalSync(ref)
            val scope = rememberCoroutineScope()
            val rem = remember {
                orderSnapshotStateMap
            }
            scope.launch { FirebaseVerticalSync.replicationFlow.collect { order ->
                orderSnapshotStateMap[order.orderId] = order
                }
            }
            DisplayData(rem = rem)
            return
        }
        val (isConnected, updateConnection) = remember { mutableStateOf(false) }
        Column(modifier = Modifier.padding(top = 50.dp)) {
            Text(
                text = "Running as ${mode.value}",
                fontSize = 20.sp,
                fontStyle = FontStyle.Italic
            )
            if (isConnected) Replicator(updateConnection)
            else WaitForServer(updateConnection)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startServer() {
        GlobalScope.launch {
            try {
                mode.value = Mode.Server
                server = KdsServer(ref)
                server.start()
            } catch (e: Exception) {
                println("Error starting server")
            }
        }
    }

    @Composable
    private fun SelectionScreen(updateSelection: (Boolean) -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedButton(onClick = {
                mode.value = Mode.Server
                updateSelection(false)
                startServer()
            }) {
                Text("Run As Server", fontSize = 30.sp)
            }
            OutlinedButton(onClick = {
                updateSelection(false)
            }) {
                Text("Run As Client", fontSize = 30.sp)
            }
            OutlinedButton(onClick = {
                mode.value = Mode.Offline
                updateSelection(false)
            }) {
                Text("Run Offline", fontSize = 30.sp)
            }
        }
    }

    private var retry = 0

    @Composable
    private fun WaitForServer(updateConnection: (Boolean) -> Unit) {
        LaunchedEffect(key1 = true) {
            var shouldDoRetry = true

            while (shouldDoRetry) {
                if (deviceName == "client1" && retry > 5) {
                    startServer()
                }
                launch {
                    try {
                        shouldDoRetry = false
                        clientService.replicate()
                    } catch (e: Exception) {
                        clientService.close()
                        clientService = KdsRpc(uri, ref)
                        delay(1000)
                        shouldDoRetry = true
                    }
                }
                delay(1000)
                retry += 1
                updateConnection(true)
            }
        }

        Box(
            modifier = Modifier
                .background(Color.Black)
                .fillMaxWidth()
                .fillMaxHeight()
        )
        {
            Text("Waiting for server...", color = Color.White, textAlign = TextAlign.Center)
        }
    }

    @SuppressLint("CoroutineCreationDuringComposition")
    @Composable
    fun Replicator(updateConnection: (Boolean) -> Unit) {
        val scope = rememberCoroutineScope()
        val rem = remember {
            orderSnapshotStateMap
        }
        val orderId = remember { mutableStateOf("1") }
        val itemCount = remember { mutableStateOf("0") }
        val isCompleted = remember { mutableStateOf("false") }

        LaunchedEffect(key1 = true) {
            try {
                scope.launch {
                    clientService.replicationFlow.collect { (key, value) ->
                        orderSnapshotStateMap[key] = value
                    }
                }
                clientService.replicate()
            } catch (e: Exception) {
                updateConnection(false)
            }
        }

        Column(modifier = Modifier.padding(start = 20.dp)) {
            OutlinedTextField(value = orderId.value,
                onValueChange = { orderId.value = it },
                label = { Text("OrderId", fontSize = 20.sp) }
            )
            OutlinedTextField(value = itemCount.value,
                onValueChange = { itemCount.value = it },
                label = { Text("itemCount", fontSize = 20.sp) }
            )
            Row {
                Text(text = "IsCompleted: ", fontSize = 20.sp)
                Checkbox(checked = isCompleted.value == "true", onCheckedChange = {
                    isCompleted.value = it.toString()
                })
            }

            OutlinedTextField(value = isCompleted.value,
                onValueChange = { isCompleted.value = it },
                label = { Text("isCompleted", fontSize = 20.sp) }
            )

            Button(onClick = {
                scope.launch {
                    clientService.requestFlow.emit(
                        SyncRequest.newBuilder()
                            .setOrderId(orderId.value.toInt())
                            .setItemCount(itemCount.value.toInt())
                            .setIsCompleted(isCompleted.value.toBoolean())
                            .build()
                    )
                }
            }) {
                Text("Click to send request!")
            }
        }
        DisplayData(rem)
    }

    @Composable
    private fun DisplayData(rem: SnapshotStateMap<String, Order>) {
        LazyColumn {
            var toggleColor = true
            for (i in rem.keys) {
                val data = rem[i]!!
                item {
                    toggleColor = !toggleColor
                    Text(
                        "OrderId: $i, itemCount: ${data.itemCount}, FinishedTime: ${data.finishedTime} ",
                        color = if (toggleColor) Color.Red else Color.Blue
                    )
                }
            }
        }
    }

}


package com.hac.kdsclient

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.Firebase
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.database
import com.google.firebase.initialize
import com.hac.kdsclient.ui.theme.KdsClientTheme
import com.hac.protobuf.client.KdsRpc
import com.hac.protobuf.client.KdsServer
import com.ncr.proto.gen.SyncRequest
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    //    private val kdsClient = KdsClient()
//    private val default: SyncResponse = kdsClient.defaultResponse
//    private val uri by lazy { Uri.parse("http://localhost:50051/") }
    private val uri by lazy { Uri.parse("http://192.168.0.207:50078/") }

    //    private val uri by lazy { Uri.parse("http://10.0.2.2:50051/") }
    private val service by lazy { KdsRpc(uri) }
    private lateinit var ref: DatabaseReference

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Firebase.initialize(this)
        ref = Firebase.database.getReference("data")
        val name = Settings.Global.getString(application.contentResolver, "device_name")
        Log.i("MainActivity", "onCreate $name")
        if (name != "client") {
            val server = KdsServer(ref)
            GlobalScope.launch {
                server.start()
            }
        }
        enableEdgeToEdge()
        setContent {
            KdsClientTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    Column(modifier = Modifier.padding(top = 50.dp)) {
                        if (name != "client") {
                            Text(text = "Running as Server")
                        }
//                        Greeter(service)
                        Replicator(service)
                    }
                }
            }
        }
    }
}


@SuppressLint("CoroutineCreationDuringComposition")
@Composable
fun Greeter(kdsRpc: KdsRpc) {
    val response = remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    scope.launch {
        kdsRpc.responseFlow.collect { it ->
            response.value = it
        }
    }

    val nodeId = remember { mutableStateOf("") }
    val lastRead = remember { mutableStateOf("") }
    val lastSent = remember { mutableStateOf("") }
    Column {
        OutlinedTextField(value = nodeId.value,
            onValueChange = { nodeId.value = it },
            label = { Text("NodeId") }
        )
        OutlinedTextField(value = lastRead.value,
            onValueChange = { lastRead.value = it },
            label = { Text("lastRead") }
        )
        OutlinedTextField(value = lastSent.value,
            onValueChange = { lastSent.value = it },
            label = { Text("lastSent") }
        )

        Button(onClick = {
            scope.launch { kdsRpc.sync(nodeId.value, lastSent.value, lastRead.value) }
        }) {
            Text("Click to send request!")
        }
        Text("Response is ${response.value}")
    }
}

@SuppressLint("CoroutineCreationDuringComposition")
@Composable
fun Replicator(client: KdsRpc) {
    val replicate = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    val nodeId = remember { mutableStateOf("") }
    val lastRead = remember { mutableStateOf("") }
    val lastSent = remember { mutableStateOf("") }

    scope.launch {
        client.replicate()
    }
    scope.launch {
        client.replicationFlow.collect { (key, value) ->
            replicate[key] = value
        }
    }
    Column {
        OutlinedTextField(value = nodeId.value,
            onValueChange = { nodeId.value = it },
            label = { Text("NodeId") }
        )
        OutlinedTextField(value = lastRead.value,
            onValueChange = { lastRead.value = it },
            label = { Text("lastRead") }
        )
        OutlinedTextField(value = lastSent.value,
            onValueChange = { lastSent.value = it },
            label = { Text("lastSent") }
        )

        Button(onClick = {
            scope.launch {
                client.requestFlow.emit(
                    SyncRequest.newBuilder()
                        .setLastRead(lastRead.value)
                        .setLastSent(lastSent.value)
                        .setNodeId(nodeId.value)
                        .build()
                )
            }
        }) {
            Text("Click to send request!")
        }
    }
    LazyColumn {
        for (i in replicate.keys) {
            val data = replicate[i]
            item { Text("Node: $i, Value: $data") }
        }
    }
}



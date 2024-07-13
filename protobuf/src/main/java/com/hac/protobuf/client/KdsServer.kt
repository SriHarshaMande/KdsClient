package com.hac.protobuf.client

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.ncr.proto.gen.KdsServiceGrpcKt
import com.ncr.proto.gen.MyVoid
import com.ncr.proto.gen.ReplicateData
import com.ncr.proto.gen.SyncRequest
import com.ncr.proto.gen.SyncResponse
import io.grpc.Grpc
import io.grpc.InsecureServerCredentials
import io.grpc.Server
import io.grpc.ServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.coroutines.EmptyCoroutineContext


class KdsServer(ref: DatabaseReference) {
    val port: Int = 50078
    private val serverBuilder: ServerBuilder<*> =
        Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
    val server: Server = serverBuilder
        .addService(KdsController(ref))
        .build()

    fun start() {
        server.start()
        println("Server started, listening on $port")
        Runtime.getRuntime().addShutdownHook(
            Thread {
                println("*** shutting down gRPC server since JVM is shutting down")
                this@KdsServer.stop()
                println("*** server shut down")
            },
        )
    }

    private fun stop() {
        server.shutdown()
    }

    fun blockUntilShutdown() {
        server.awaitTermination()
    }
}


class KdsController(private val ref: DatabaseReference) :
    KdsServiceGrpcKt.KdsServiceCoroutineImplBase() {

    private val scope = CoroutineScope(EmptyCoroutineContext)
//    private val replicationFlow = MutableSharedFlow<ReplicateData>()
    private val someList = mutableMapOf<String, ReplicateData>()

    init {
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                scope.launch {
                    val data = snapshot.value
                    if (data != null) {
                        val dd = data as Map<*, *>
                        Log.i("Firebase", "Value is: $dd")

                        dd.forEach { (key, value) ->
                            val response2: ReplicateData = ReplicateData.newBuilder()
                                .setValue("Replication ${value}")
                                .setNodeId(key.toString())
                                .build()
                            someList[response2.nodeId] = response2
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                println("Error in Firebase: $error")
            }
        })
    }

    override suspend fun sync(request: SyncRequest): SyncResponse {
        println("sync")
        println(request)
        println(request.nodeId)
        val currentTime = LocalDateTime.now()
        val response: SyncResponse = SyncResponse.newBuilder()
            .setValue("Sync completed $currentTime")
            .build()
        try {
            ref.child(request.nodeId).setValue(currentTime.format(DateTimeFormatter.ofLocalizedTime(
                FormatStyle.MEDIUM)))
        } catch (e: Exception) {
            Log.e("Firebase", "Error in Firebase $e")
        }

        return response
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun replicate(requests: Flow<SyncRequest>): Flow<ReplicateData> {
//        return replicationFlow.asSharedFlow()
        GlobalScope.launch {
            requests.collect{ request ->
                ref.child(request.nodeId).setValue(mapOf("lastRead" to request.lastRead, "lastSent" to request.lastSent))
            }
        }

        return flow {
            while(true) {
//                delay(10)
//                if(someList.isNotEmpty()){
//                emit(someList.first())
//                someList.removeAt(0)
//                }

                delay(100)
                someList.forEach { emit(it.value) }
            }
        }
    }
}
@file:Suppress("OPT_IN_USAGE")

package com.hac.protobuf.client

import com.google.firebase.database.DatabaseReference
import com.ncr.proto.gen.KdsServiceGrpcKt
import com.ncr.proto.gen.Order
import com.ncr.proto.gen.SyncRequest
import io.grpc.Grpc
import io.grpc.InsecureServerCredentials
import io.grpc.Server
import io.grpc.ServerBuilder
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


class KdsServer(ref: DatabaseReference) {
    private val port: Int = 50078
    private val serverBuilder: ServerBuilder<*> =
        Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
    private val server: Server = serverBuilder
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

    init {
        FirebaseVerticalSync.startVerticalSync(ref)
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun replicate(requests: Flow<SyncRequest>): Flow<Order> {
        GlobalScope.launch {
            requests.collect { request ->
                val order = ref.child(request.orderId.toString())
                if (order.key == null) {
                    ref.child(request.orderId.toString()).setValue(
                        mapOf(
                            "itemCount" to request.itemCount,
                            "isCompleted" to request.isCompleted,
                            "receivedTime" to System.currentTimeMillis().toString(),
                            "finishedTime" to ""
                        )
                    )
                } else {
                    ref.child(request.orderId.toString()).updateChildren(
                        mapOf(
                            "itemCount" to request.itemCount,
                            "isCompleted" to request.isCompleted,
                            "receivedTime" to "someTime",
                            "finishedTime" to if (request.isCompleted) LocalDateTime.now()
                                .format(DateTimeFormatter.ISO_TIME)
                                .toString() else "0"
                        )
                    )
                }
            }
        }

        return FirebaseVerticalSync.replicationFlow
    }
}
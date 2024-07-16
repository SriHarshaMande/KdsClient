package com.hac.protobuf.client

import android.net.Uri
import com.google.firebase.database.DatabaseReference
import com.ncr.proto.gen.KdsServiceGrpcKt
import com.ncr.proto.gen.Order
import com.ncr.proto.gen.SyncRequest
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.Closeable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


class KdsRpc(uri: Uri, private val reference: DatabaseReference) : Closeable {
    val requestFlow = MutableSharedFlow<SyncRequest>()
//    val requestFlow = MutableSharedFlow<SyncRequest>(SyncRequest.newBuilder().setOrderId(123).setItemCount(0).setIsCompleted(false).build())
    val replicationFlow = MutableSharedFlow<Pair<String, Order>>(replay = 10)
    private val channel = let {
        println("Connecting to ${uri.host}:${uri.port}")

        val builder = ManagedChannelBuilder.forAddress(uri.host, uri.port)
        if (uri.scheme == "https") {
            builder.useTransportSecurity()
        } else {
            builder.usePlaintext()
        }

        builder.executor(Dispatchers.IO.asExecutor()).build()
    }

    private val stub = KdsServiceGrpcKt.KdsServiceCoroutineStub(channel)

    suspend fun replicate() {
        try {
            val response = stub.replicate(requestFlow)
            response.collect{
                println("Replicate -> ${it.orderId}")
                replicationFlow.emit(it.orderId to it)
                reference.child(it.orderId.toString()).updateChildren(
                    mapOf(
                        "itemCount" to it.itemCount,
                        "isCompleted" to (it.finishedTime != "0"),
                        "receivedTime" to it.receivedTime,
                        "finishedTime" to it.finishedTime
                    )
                )
            }
        } catch (e: Exception) {
            throw e
        }
    }

    override fun close() {
        channel.shutdownNow()
    }
}
package com.hac.protobuf.client

import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.ncr.proto.gen.KdsServiceGrpcKt
import com.ncr.proto.gen.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

object  FirebaseVerticalSync {
    val replicationFlow = MutableSharedFlow<Order>(replay = 50)
    val configFlow = MutableSharedFlow<String>(replay = 1)

    fun startVerticalSync(reference: DatabaseReference) {
        val scope = CoroutineScope(EmptyCoroutineContext)
        reference.addChildEventListener( object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    notifyChangesViaFlow(snapshot)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    notifyChangesViaFlow(snapshot)
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                TODO("Not yet implemented")
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                TODO("Not yet implemented")
            }

            override fun onCancelled(error: DatabaseError) {
                TODO("Not yet implemented")
            }
        })
    }

    fun startConfigSync(reference: DatabaseReference) {
        val scope = CoroutineScope(EmptyCoroutineContext)
        reference.addChildEventListener( object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    configFlow.emit(snapshot.value.toString())
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    configFlow.emit(snapshot.value.toString())
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                println("The child removed: " + snapshot.key)
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                println("The child moved: " + snapshot.key)
            }

            override fun onCancelled(error: DatabaseError) {
                println("The read failed: " + error.code)
            }
        })
    }


    private suspend fun notifyChangesViaFlow(snapshot: DataSnapshot) {
        val data = snapshot.value
        if (data != null) {
            val dd = data as Map<*, *>
            Log.i("Firebase", "Value is: $dd")

            val orderId = snapshot.key
            val itemCount = dd["itemCount"].toString()
            val receivedTime = dd["receivedTime"].toString()
            val finishedTime = dd["finishedTime"].toString()

            val response2: Order = Order.newBuilder()
                .setOrderId(orderId)
                .setItemCount(itemCount.toInt())
                .setReceivedTime(receivedTime)
                .setFinishedTime(finishedTime)
                .build()
            replicationFlow.emit(response2)
        }
    }
}
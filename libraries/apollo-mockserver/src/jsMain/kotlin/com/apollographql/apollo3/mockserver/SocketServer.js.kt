package com.apollographql.apollo3.mockserver

import js.typedarrays.toUint8Array
import kotlinx.coroutines.channels.Channel
import node.buffer.Buffer
import node.events.Event
import node.net.AddressInfo
import node.net.Server
import node.net.createServer
import okio.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import node.net.Socket as NetSocket

internal class NodeSocket(private val netSocket: NetSocket) : Socket {
  private val readQueue = Channel<ByteArray>(Channel.UNLIMITED)
  init {
    netSocket.on(Event.DATA) { chunk ->
      val bytes = when (chunk) {
        is String -> chunk.encodeToByteArray()
        is Buffer -> chunk.toByteArray()
        else -> error("Unexpected chunk type: ${chunk::class}")
      }
      readQueue.trySend(bytes)
    }

    netSocket.on(Event.CLOSE) { _ ->
      readQueue.close(IOException("The socket was closed"))
    }
  }

  override suspend fun receive(): ByteArray {
    return readQueue.receive()
  }

  // XXX: flow control
  override fun write(data: ByteArray): Boolean {
    return netSocket.write(data.toUint8Array())
  }

  override fun close() {
    readQueue.close()
    netSocket.destroy()
  }
}

internal class NodeSocketServer : SocketServer {
  private var server: Server? = null
  private var address: Address? = null


  override fun start(block: (socket: Socket) -> Unit) {
    server = createServer { netSocket ->
      block(NodeSocket(netSocket))
    }

    server!!.listen()
  }

  override suspend fun address(): Address {
    check(server != null) {
      "You need to call start() before calling port()"
    }

    return address ?: suspendCoroutine { cont ->
      server!!.on(Event.LISTENING) {
        val address = server!!.address().unsafeCast<AddressInfo>()

        this.address = Address(address.address, address.port.toInt())
        cont.resume(this.address!!)
      }
    }
  }

  override fun close() {
    check(server != null) {
      "server is not started"
    }
    server!!.close()
  }
}

internal actual fun SocketServer(acceptDelayMillis: Int): SocketServer = NodeSocketServer()
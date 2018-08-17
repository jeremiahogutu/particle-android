package io.particle.particlemesh.meshsetup.connection

import io.particle.particlemesh.common.QATool
import io.particle.particlemesh.common.toHex
import io.particle.particlemesh.meshsetup.connection.security.AesCcmDelegate
import io.particle.particlemesh.meshsetup.putUntilFull
import io.particle.particlemesh.meshsetup.readByteArray
import io.particle.particlemesh.meshsetup.readUint16LE
import io.particle.particlemesh.meshsetup.writeUint16LE
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.launch
import mu.KotlinLogging
import okio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder


// There's no known max size, but data larger than 10KB seems absurd, so we're going with that.
internal const val MAX_FRAME_SIZE = 10240


// this class exists exclusively to make the pipeline more type safe,
// and resistant to being improperly constructed
class InboundFrame(val frameData: ByteArray)


class OutboundFrame(val frameData: ByteArray, val payloadSize: Int)


// (see above re: type safety)
class BlePacket(val data: ByteArray)


class OutboundFrameWriter(
        private val byteSink: (ByteArray) -> Unit
) {

    // Externally mutable state is less than awesome.  Patches welcome.
    var cryptoDelegate: AesCcmDelegate? = null

    private val buffer = ByteBuffer.allocate(MAX_FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    private val log = KotlinLogging.logger {}

    @Synchronized
    fun writeFrame(frame: OutboundFrame) {
        buffer.clear()

        buffer.writeUint16LE(frame.payloadSize)

        val fml = Buffer()
        fml.writeShortLe(frame.payloadSize)
        val additionalData = fml.readByteArray()

        val data = cryptoDelegate?.encrypt(frame.frameData, additionalData) ?: frame.frameData
        log.info { "Sending frame with ${data.size} byte payload " }
        buffer.put(data)
        buffer.flip()

        val finalFrame = buffer.readByteArray()
        byteSink(finalFrame)
        log.info { "Sent frame with ${finalFrame.size} total bytes" }
    }

}


class InboundFrameReader {

    val inboundFrameChannel = Channel<InboundFrame>(128)
    // Externally mutable state is less than awesome.  Patches welcome.
    var cryptoDelegate: AesCcmDelegate? = null
    // Externally mutable state is less than awesome.  Patches welcome.
    // Number of header bytes beyond just the "size" field
    var extraHeaderBytes: Int = 0

    private val log = KotlinLogging.logger {}

    private var inProgressFrame: InProgressFrame? = null

    @Synchronized
    fun receivePacket(blePacket: BlePacket) {
        log.debug { "Processing packet: ${blePacket.data.toHex()}" }
        try {
            if (inProgressFrame == null) {
                inProgressFrame = InProgressFrame(extraHeaderBytes)
            }

            val bytesForNextFrame = inProgressFrame!!.writePacket(blePacket.data)

            if (inProgressFrame!!.isComplete) {
                handleCompleteFrame()
            }

            if (bytesForNextFrame != null) {
                log.info { "Processing bytes from next frame as new packet: ${bytesForNextFrame.toHex()}" }
                receivePacket(BlePacket(bytesForNextFrame))
            }

        } catch (ex: Exception) {
            inProgressFrame = null // toss out old frame to reset us for the next one
            QATool.report(ex)
        }
    }

    private fun handleCompleteFrame() {
        val ipf = inProgressFrame!!
        val frameData = ipf.consumeFrameData()
        log.info { "Handling complete frame with ${frameData.size} bytes: ${frameData.toHex()}" }
        val payloadSize = frameData.size - extraHeaderBytes
        val additionalData = Buffer().writeShortLe(payloadSize).readByteArray()
        val completeFrame = InboundFrame(
                cryptoDelegate?.decrypt(frameData, additionalData) ?: frameData
        )
        inProgressFrame = null
        launch {
            inboundFrameChannel.send(completeFrame)
        }

    }
}


class InProgressFrame(private val extraHeaderBytes: Int) {

    var isComplete = false
        private set

    private var frameSize: Int? = null // Int instead of Short because it's sent as a uint16_t
    private var finalFrameData: ByteArray? = null

    private val packetBuffer = ByteBuffer.allocate(MAX_FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    // note that this field gets its limit set below
    private val frameDataBuffer = ByteBuffer.allocate(MAX_FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    private var isConsumed = false

    @Synchronized
    fun writePacket(packet: ByteArray): ByteArray? {  // returns the "extra" bytes
        ensureFrameIsNotReused()

        packetBuffer.put(packet)
        packetBuffer.flip()

        if (frameSize == null) {
            onFirstPacket()
        }

        frameDataBuffer.putUntilFull(packetBuffer)

        var bytesForNextFrame: ByteArray? = null
        if (!frameDataBuffer.hasRemaining()) {
            isComplete = true
            onComplete()
            bytesForNextFrame = getBytesForNextFrame()
        }
        packetBuffer.clear()

        return bytesForNextFrame
    }

    @Synchronized
    fun consumeFrameData(): ByteArray {
        require(isComplete) { "Cannot consume data, frame is not complete!" }

        ensureFrameIsNotReused()
        isConsumed = true

        return finalFrameData!!
    }

    private fun onFirstPacket() {
        // add the header bytes because, unfortunately, the size field here reflects
        // the *payload size*, not the *remaining frame size*, and the messaging stack
        // has to handle the header bytes inbetween for itself...
        val payloadSize = packetBuffer.short
        frameSize = extraHeaderBytes + payloadSize
        require(frameSize!! >= 0) { "Invalid frame size: $frameSize" }
        frameDataBuffer.limit(frameSize!!.toInt())
    }

    private fun onComplete() {
        frameDataBuffer.flip()
        finalFrameData = frameDataBuffer.readByteArray(frameSize!!.toInt())
    }

    private fun getBytesForNextFrame(): ByteArray? {
        if (packetBuffer.remaining() < 2) { // do we have a size header?
            return null
        }

        val nextFrameSize = packetBuffer.readUint16LE()
        if (nextFrameSize == 0) {
            // remainder of the frame must be zeros; bail
            return null
        }

        val remaining = packetBuffer.readByteArray()

        packetBuffer.clear()

        packetBuffer.writeUint16LE(nextFrameSize)
        packetBuffer.put(remaining)

        packetBuffer.flip()

        return packetBuffer.readByteArray()
    }

    private fun ensureFrameIsNotReused() {
        if (isConsumed) {
            throw IllegalStateException("Frame already consumed!  Instances cannot be reused!")
        }
    }
}

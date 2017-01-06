package graphics.scenery.scenery.backends.vulkan

import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkDevice
import java.nio.ByteBuffer

class VulkanBuffer(val device: VkDevice, var memory: Long = -1L, var buffer: Long = -1L, var data: Long = -1L) {
    private var currentPosition = 0L
    private var currentPointer: PointerBuffer? = null
    var maxSize: Long = 512 * 2048L
    var alignment: Long = 256
    private var mapped = false

    var stagingBuffer = memAlloc(maxSize.toInt())

    fun getPointerBuffer(size: Int): ByteBuffer {
        if (currentPointer == null) {
            this.map()
        }

        val buffer = memByteBuffer(currentPointer!!.get(0) + currentPosition, size)
        currentPosition += size * 1L

        return buffer
    }

    fun getCurrentOffset(): Int {
        if (currentPosition % alignment != 0L) {
            currentPosition += alignment - (currentPosition % alignment)
            stagingBuffer.position(currentPosition.toInt())
        }
        return currentPosition.toInt()
    }

    fun advance(align: Long = this.alignment): Int {
        val pos = stagingBuffer.position()
        val rem = pos % align

        if(rem != 0L) {
            stagingBuffer.position(pos + align.toInt() - rem.toInt())
        }

        return stagingBuffer.position()
    }

    fun reset() {
        stagingBuffer.position(0)
        currentPosition = 0L
    }

    fun copyFrom(data: ByteBuffer) {
        val dest = memAllocPointer(1)
        vkMapMemory(device, memory, 0, maxSize * 1L, 0, dest)
        memCopy(memAddress(data), dest.get(0), data.remaining())
        vkUnmapMemory(device, memory)
        memFree(dest)
    }

    fun copyTo(dest: ByteBuffer) {
        val src = memAllocPointer(1)
        vkMapMemory(device, memory, 0, maxSize * 1L, 0, src)
        memCopy(src.get(0), memAddress(dest), dest.remaining())
        vkUnmapMemory(device, memory)
        memFree(src)
    }

    fun map(): PointerBuffer {
        val dest = memAllocPointer(1)
        vkMapMemory(device, memory, 0, maxSize * 1L, 0, dest)

        currentPointer = dest
        mapped = true
        return dest
    }

    fun unmap() {
        mapped = false
        vkUnmapMemory(device, memory)
    }

    fun copyFromStagingBuffer() {
        stagingBuffer.flip()
        copyFrom(stagingBuffer)
    }

    fun close() {
        if(mapped) {
            vkUnmapMemory(device, memory)
        }
        memFree(stagingBuffer)
        vkFreeMemory(device, memory, null)
        vkDestroyBuffer(device, buffer, null)
    }
}

import java.io.RandomAccessFile

class Sunday(
    private val raf: RandomAccessFile,
    initOffset: Long,
    private val pattern: ByteArray,
    private val bufSize: Int = 1 shl 20
) {
    private val toSkip = IntArray(1 shl 8)
    private val buf: ByteArray
    private var rafOffSet: Long

    init {
        toSkip.fill(pattern.size)
        pattern.forEachIndexed { idx, b ->
            toSkip[b.toUByte().toInt()] = pattern.size - idx
        }
        rafOffSet = initOffset
        buf = ByteArray(bufSize)
    }

    val sequence
        get() = sequence {
            var next: Long
            while (getNextOffSet().also { next = it } > 0) {
                yield(next)
            }
        }

    @Suppress("MemberVisibilityCanBePrivate")
    fun getNextOffSet(): Long {
        if (rafOffSet >= raf.length()) return -1L
        outer@
        while (rafOffSet <= raf.length()) {
            raf.seek(rafOffSet)
            val readBufLen = raf.read(buf)
            if (readBufLen == -1) return -1L
            var bufOffset = 0
            inner@
            while (bufOffset + pattern.size <= readBufLen) {
                for (i in pattern.indices) {
                    if (buf[bufOffset + i] != pattern[i]) {
                        if (bufOffset + pattern.size >= readBufLen) break@inner
                        bufOffset += toSkip[buf[bufOffset + pattern.size].toUByte().toInt()]
                        continue@inner
                    }
                }
                val result = rafOffSet + bufOffset
                rafOffSet += bufOffset + 1
                return result
            }
            rafOffSet += bufSize - pattern.size
        }
        return -1L
    }
}
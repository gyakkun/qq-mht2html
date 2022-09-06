import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consume
import java.io.File
import java.io.RandomAccessFile
import java.util.*
import kotlin.random.Random

object Mht2Html {
    lateinit var raf: RandomAccessFile
    lateinit var BOUNDARY: String
    val offsetOfNextPartChannel = Channel<Triple<UUID, Long, Long>>()
    val FILE_LOCATION = "E:\\QQ-MHT2HTML-WORKING\\咕咕瓜的避难窝.mht"
    val IMG_OUTPUT_PATH = "E:\\QQ-MHT2HTML-WORKING\\img"
    val BUF_SIZE = 1 shl 20

    fun job() {
        raf = RandomAccessFile(FILE_LOCATION, "r")
        var fileOffset: Long = -1
        val buf = ByteArray(BUF_SIZE)
        val imgOutputPath = File(IMG_OUTPUT_PATH)
        if (imgOutputPath.exists() && !imgOutputPath.isDirectory && imgOutputPath.walk().iterator().hasNext()) {
            System.err.println("Img output dir exists and is not empty!")
        } else if (!imgOutputPath.exists()) {
            imgOutputPath.mkdirs()
        }
        var line: String
        while (raf.readLine().also { line = it } != null) {
            if (line.contains("boundary=\"")) {
                BOUNDARY = "--" + line.substring(line.indexOf("=") + 2, line.length - 1)
                fileOffset = raf.filePointer
                System.err.println(fileOffset)
                break
            }
        }

        System.err.println(BOUNDARY)
        val sunday = Sunday(raf, 0L, BOUNDARY.toByteArray())
        var counter = 0
        var limit = 10
        var nextOffset = -1L
        while (/*counter++ < limit &&*/ (sunday.getNextOffSet().also { nextOffset = it } > 0L)) {
            System.err.println(nextOffset)
        }

    }

    fun consumer() {
        offsetOfNextPartChannel.consume {

        }
    }

    suspend fun producer() {
        offsetOfNextPartChannel.send(Triple(UUID.randomUUID(), Random.nextLong(), Random.nextLong()))
    }

}
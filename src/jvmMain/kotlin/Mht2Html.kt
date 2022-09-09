import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.launch
import org.apache.commons.codec.binary.Base64
import org.apache.commons.imaging.Imaging
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.collections.ArrayList
import kotlin.random.Random

object Mht2Html {
    lateinit var raf: RandomAccessFile
    lateinit var BOUNDARY: String
    val offsetOfNextPartChannel = Channel<Triple<UUID, Long, Long>>()
    val FILE_LOCATION = "U:\\纯洁的DD隔离病院.mht"
    val IMG_OUTPUT_PATH = "U:\\byr_img"

    fun job() {
        var timing: Long = System.currentTimeMillis()
        raf = RandomAccessFile(FILE_LOCATION, "r")
        var fileOffset: Long = -1
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
        val offsetList: MutableList<Long> = ArrayList()
        val latchList = ArrayList<CountDownLatch>()
        // val base64Decoder = Base64.
        while (/*counter < limit &&*/ (sunday.getNextOffSet().also { nextOffset = it } > 0L)) {
            offsetList.add(nextOffset)
            val ls = offsetList.size
            if (ls > 2) {
                val latch = CountDownLatch(1)
                latchList.add(latch)
                GlobalScope.launch {
                    val offset = offsetList[ls - 2]
                    val thisScope = offsetList[ls - 1] - offsetList[ls - 2]
                    var line: String = ""
                    var tmpRaf = RandomAccessFile(FILE_LOCATION, "r")
                    var uuid: String = "INVALID"
                    tmpRaf.seek(offset)
                    while (tmpRaf.readLine().also { line = it } != null) {
                        if (line.contains("Content-Location")) {
                            uuid = line.substring("Content-Location:{".length, line.indexOf("}.dat"))
                            // System.err.println(uuid)
                            tmpRaf.readLine()
                            break
                        }
                    }

//                    val sb = StringBuffer()
//                    while (tmpRaf.readLine().also { line = it }.isNotBlank()) {
//                        sb.append(line)
//                    }

                    val beginOffsetOfB64 = tmpRaf.filePointer
                    val endOffsetOfB64 = offsetList[ls - 1]
                    val b64Len = endOffsetOfB64 - beginOffsetOfB64
                    val ba = ByteArray(b64Len.toInt())
                    tmpRaf.seek(beginOffsetOfB64)
                    tmpRaf.read(ba)
                    val decode = Base64.decodeBase64(ba)
                    val baos = ByteArrayOutputStream()
                    baos.write(decode)
                    val fileExt = Imaging.guessFormat(decode).extension

//                    val decode = base64Decoder.decode(sb.toString())
//                    val baos = ByteArrayOutputStream()
//                    baos.write(decode)
//                    val fileExt = Imaging.guessFormat(decode).extension

                    val fos = FileOutputStream(imgOutputPath.resolve("$uuid.$fileExt"))
                    fos.write(decode)
                    fos.flush()
                    fos.close()
                    latch.countDown()
                }
            }
            counter++
        }
        for (latch in latchList) latch.await()
        timing = System.currentTimeMillis() - timing
        System.err.println("TOTAL: $counter offset detected. Timing: $timing ms")
    }

    fun consumer() {
        offsetOfNextPartChannel.consume {

        }
    }

    suspend fun producer() {
        offsetOfNextPartChannel.send(Triple(UUID.randomUUID(), Random.nextLong(), Random.nextLong()))
    }

}
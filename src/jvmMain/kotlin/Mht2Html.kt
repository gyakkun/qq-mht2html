import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import org.apache.commons.codec.binary.Base64
import org.apache.commons.imaging.Imaging
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

object Mht2Html {
    lateinit var raf: RandomAccessFile
    lateinit var BOUNDARY: String
    val FILE_LOCATION = "U:\\纯洁的DD隔离病院.mht"
    val IMG_OUTPUT_PATH = "A:\\byr_img"
    val THREAD_COUNT = 6
    val tp = newFixedThreadPoolContext(THREAD_COUNT, "mth2html")

    fun job() = runBlocking(tp) {
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

        val producer = produceOffSet(sunday, ArrayList())
        repeat(THREAD_COUNT) {
            launchConsumer(it,/*latch,*/ producer)
        }
        timing = System.currentTimeMillis() - timing
        System.err.println("TOTAL:  Timing: $timing ms")
    }

    private fun CoroutineScope.launchConsumer(id: Int, channel: ReceiveChannel<Triple<Long, Long, String>>) = launch {
        val lRaf = RandomAccessFile(FILE_LOCATION, "r")
        for (msg in channel) {
            val beginOffsetOfB64 = msg.first
            val endOffsetOfB64 = msg.second
            val uuid = msg.third
            val b64Len = endOffsetOfB64 - beginOffsetOfB64
            val ba = ByteArray(b64Len.toInt())
            lRaf.seek(beginOffsetOfB64)
            lRaf.read(ba)
            val decode = Base64.decodeBase64(ba)
            val fileExt = Imaging.guessFormat(decode).extension

            val fos = FileOutputStream(File(IMG_OUTPUT_PATH).resolve("$uuid.$fileExt"))
            fos.write(decode)
            fos.flush()
            fos.close()
        }
    }

    private fun CoroutineScope.produceOffSet(sunday: Sunday, offsetList: ArrayList<Long>) =
        produce {
            val lRaf = RandomAccessFile(FILE_LOCATION, "r")
            var nextOffset: Long
            while (/*counter < limit &&*/ (sunday.getNextOffSet().also { nextOffset = it } > 0L)) {
                offsetList.add(nextOffset)
                val ls = offsetList.size
                if (ls > 2) {
                    val offset = offsetList[ls - 2]
                    var line: String = ""
                    var uuid: String = "INVALID"
                    lRaf.seek(offset)
                    while (lRaf.readLine().also { line = it } != null) {
                        if (line.contains("Content-Location")) {
                            uuid = line.substring("Content-Location:{".length, line.indexOf("}.dat"))
                            // System.err.println(uuid)
                            lRaf.readLine()
                            break
                        }
                    }
                    val beginOffsetOfB64 = lRaf.filePointer
                    val endOffsetOfB64 = offsetList[ls - 1]
                    send(Triple(beginOffsetOfB64, endOffsetOfB64, uuid))
                }
            }
        }
}
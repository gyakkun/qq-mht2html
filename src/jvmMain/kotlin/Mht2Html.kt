import androidx.compose.material.AlertDialog
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import org.apache.commons.codec.binary.Base64
import org.apache.commons.imaging.Imaging
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch

@ExperimentalMaterialApi
@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
object Mht2Html {
    lateinit var BOUNDARY: String
    val FILE_LOCATION = "U:\\纯洁的DD隔离病院.mht"
    val IMG_OUTPUT_PATH = "A:\\byr_img"
    val THREAD_COUNT = 3

    @Composable
    fun job() = doJob(FILE_LOCATION, IMG_OUTPUT_PATH)

    @Composable
    private fun doJob(
        fileLocation: String,
        imgOutputPath: String,
        threadCount: Int = THREAD_COUNT
    ) {
        val tp = newFixedThreadPoolContext(threadCount + 1, "mht2html") // 1 more thread for producer
        runBlocking(tp) {
            var timing: Long = System.currentTimeMillis()
            val raf = RandomAccessFile(fileLocation, "r")
            var fileOffset: Long
            val imgOutputFolder = File(imgOutputPath)
            if (imgOutputFolder.exists() && !imgOutputFolder.isDirectory) {
                val errMsg = "Img output dir exists and is not a folder!"
                System.err.println(errMsg)
                AlertDialog(
                    title = {
                        Text("Critical Error")
                    },
                    text = {
                        Text(errMsg)
                    }, buttons = {}, onDismissRequest = {}
                )
                return@runBlocking
            } else if (!imgOutputFolder.exists()) {
                imgOutputFolder.mkdirs()
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
            val latchList = ArrayList<CountDownLatch>(threadCount)
            repeat(threadCount) {
                latchList.add(CountDownLatch(1))
            }

            System.err.println(BOUNDARY)
            val sunday = Sunday(raf, 0L, BOUNDARY.toByteArray())

            val producer = produceOffSet(fileLocation, sunday, ArrayList()) // The 1 more thread
            repeat(threadCount) {
                launchConsumer(it, fileLocation, imgOutputFolder, latchList[it], producer)
            }
            for (latch in latchList) latch.await()
            System.err.println("TOTAL: Timing: ${System.currentTimeMillis() - timing} ms")
        }
    }

    private fun CoroutineScope.launchConsumer(
        id: Int,
        fileLocation: String,
        imgOutputFolder: File,
        latch: CountDownLatch,
        channel: ReceiveChannel<Triple<Long, Long, String>>
    ) = launch {
        val lRaf = RandomAccessFile(fileLocation, "r")
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

            val fos = FileOutputStream(imgOutputFolder.resolve("$uuid.$fileExt"))
            fos.write(decode)
            fos.flush()
            fos.close()
        }
        latch.countDown()
    }

    private fun CoroutineScope.produceOffSet(
        fileLocation: String,
        sunday: Sunday,
        offsetList: ArrayList<Long>
    ) =
        produce {
            val raf = RandomAccessFile(fileLocation, "r")
            var nextOffset: Long
            while (sunday.getNextOffSet().also { nextOffset = it } > 0L) {
                offsetList.add(nextOffset)
                val ls = offsetList.size
                if (ls <= 2) continue

                val offset = offsetList[ls - 2]
                var line: String
                var uuid = "INVALID"
                raf.seek(offset)
                while (raf.readLine().also { line = it } != null) {
                    if (line.contains("Content-Location")) {
                        uuid = line.substring("Content-Location:{".length, line.indexOf("}.dat"))
                        // System.err.println(uuid)
                        raf.readLine()
                        break
                    }
                }
                val beginOffsetOfB64 = raf.filePointer
                val endOffsetOfB64 = offsetList[ls - 1]
                send(Triple(beginOffsetOfB64, endOffsetOfB64, uuid))
            }
            this.channel.close()
        }
}
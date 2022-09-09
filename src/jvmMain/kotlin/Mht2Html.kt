import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import org.apache.commons.codec.binary.Base64
import org.apache.commons.imaging.Imaging
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.lang.StringBuilder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

@ExperimentalMaterialApi
@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
object Mht2Html {
    private lateinit var BOUNDARY: String
    const val THREAD_COUNT = 3
    var showAlert: MutableState<Boolean>? = null
    var errMsg: MutableState<String>? = null
    var progress: MutableState<Float>? = null


    fun doJob(
        fileLocation: String,
        fileOutputPath: String,
        imgOutputPath: String,
        threadCount: Int = THREAD_COUNT,
        showAlert: MutableState<Boolean>?,
        errMsg: MutableState<String>?,
        progress: MutableState<Float>?
    ) = GlobalScope.launch {
        Mht2Html.showAlert = showAlert
        Mht2Html.errMsg = errMsg
        Mht2Html.progress = progress
        System.err.println("Thread count: $threadCount")

        showInfoBar(showAlert, errMsg, "Processing Images...", 5_000)

        val tp = newFixedThreadPoolContext(threadCount + 1, "mht2html") // 1 more thread for producer
        var timing: Long = System.currentTimeMillis()
        val raf = RandomAccessFile(fileLocation, "r")
        var fileOffset: Long
        val imgOutputFolder = File(imgOutputPath)
        if (imgOutputFolder.exists() && !imgOutputFolder.isDirectory) {
            val tmpErrMsg = "Img output dir exists and is not a folder!"
            showAlert?.value = true
            errMsg?.value = tmpErrMsg
            System.err.println(tmpErrMsg)
            return@launch
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
            if (raf.filePointer > 1000) {
                showInfoBar(
                    showAlert,
                    errMsg,
                    "Boundary not found in the first 1000 Bytes. MHT file may be not valid.",
                    3_000
                )
                return@launch
            }
        }
        val latchList = ArrayList<CountDownLatch>(threadCount)
        repeat(threadCount) {
            latchList.add(CountDownLatch(1))
        }

        System.err.println(BOUNDARY)
        val sunday = Sunday(raf, 0L, BOUNDARY.toByteArray())
        val offsetList = ArrayList<Long>()
//        GlobalScope.launch(tp) {
//            val producer = produceOffSet(fileLocation, sunday, offsetList) // The 1 more thread
//            repeat(threadCount) {
//                launchConsumer(it, fileLocation, imgOutputFolder, latchList[it], producer)
//            }
//        }

//        for (latch in latchList) latch.await()
        showInfoBar(showAlert, errMsg, "Processing HTML...", 5_000L)
        processHtml(fileLocation, ArrayList<Long>().apply { add(sunday.getNextOffSet()) }, fileOutputPath)
        val timingMsg = "TOTAL: Timing: ${System.currentTimeMillis() - timing} ms"
        showInfoBar(showAlert, errMsg, timingMsg, 60_000L)
    }

    private fun processHtml(fileLocation: String, offsetList: java.util.ArrayList<Long>, fileOutputPath: String) {
        val dateRegex = Regex("日期 (\\d{4}-\\d{2}-\\d{2})")
        val styleClassNameMap = ConcurrentHashMap<String, String>()
        var lineCounter = 0
        val endOfFile = "</table></body></html>"
        val raf = RandomAccessFile(fileLocation, "r")
        val firstBoundaryOffset = offsetList[0]
        raf.seek(firstBoundaryOffset)
        var line: String
        var firstLineOffset = -1L
        while (raf.readLine().also { line = it } != null) {
            if (line.isEmpty()) {
                firstLineOffset = raf.filePointer
                break;
            }
        }
        var counter = 0
        raf.seek(firstLineOffset)
        while (counter++ < 10) {
            val line = raf.readLine()
            extractAndReplaceStyle(line, styleClassNameMap)
        }
        TODO("Not yet implemented")
    }

    private fun extractAndReplaceStyle(line: String, styleClassNameMap: MutableMap<String, String>): String {
        val stylePrefix = "style="
        val stylePrefixWithQuote = "style=\""
        val suffix = ">"
        val suffixWithQuote = "\""
        var prevIndex = 0
        val sb = StringBuilder()
        var tmpIndex = 0
        var isWithQuote = false
        while (line.indexOf(stylePrefix, prevIndex).also { tmpIndex = it } > 0) {
            val startOfStyleAttribute = tmpIndex
            isWithQuote = line[tmpIndex + stylePrefix.length] == '"'
            var endOfStyleAttribute: Int
            var styleSheet = if (isWithQuote) {
                endOfStyleAttribute =
                    line.indexOf(suffixWithQuote, startOfStyleAttribute + stylePrefixWithQuote.length)
                line.substring(startOfStyleAttribute + stylePrefixWithQuote.length, endOfStyleAttribute)
            } else {
                endOfStyleAttribute = line.indexOf(suffix, startOfStyleAttribute + stylePrefix.length)
                line.substring(startOfStyleAttribute + stylePrefix.length, endOfStyleAttribute)
            }
            if (!styleClassNameMap.contains(styleSheet)) {
                val className = "stl-" + (styleClassNameMap.size + 1)
                styleClassNameMap.put(styleSheet, className)
            }
            sb.append(line.substring(prevIndex, startOfStyleAttribute))
            sb.append("class=\"${styleClassNameMap[styleSheet]!!}\"")
            prevIndex = endOfStyleAttribute
        }
        sb.append(line.substring(prevIndex))
        return sb.toString().also { System.err.println(it) }
    }

    private suspend fun CoroutineScope.showInfoBar(
        showAlert: MutableState<Boolean>?,
        errMsg: MutableState<String>?,
        msg: String,
        delayMs: Long = 1_000L
    ) = launch {
        showAlert?.value = true
        errMsg?.value = msg
        System.err.println(msg)
        delay(delayMs)
        showAlert?.value = false
        errMsg?.value = ""
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
                progress?.value = (nextOffset.toFloat() / raf.length().toFloat())
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
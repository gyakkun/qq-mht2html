import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import org.apache.commons.codec.binary.Base64
import org.apache.commons.imaging.Imaging
import java.io.*
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import kotlin.text.Charsets.UTF_8

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
        while (raf.readLineInUtf8().also { line = it } != null) {
            if (line.contains("boundary=\"")) {
                BOUNDARY = "--" + line.substring(line.indexOf("=") + 2, line.length - 1)
                fileOffset = raf.filePointer
                System.err.println("First boundary offset: $fileOffset")
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

        System.err.println("Boundary: $BOUNDARY")
        val sunday = Sunday(raf, 0L, BOUNDARY.toByteArray())
        val offsetList = ArrayList<Long>()
        GlobalScope.launch(tp) {
            val producer = produceOffSet(fileLocation, sunday, offsetList) // The 1 more thread
            repeat(threadCount) {
                launchConsumer(it, fileLocation, imgOutputFolder, latchList[it], producer)
            }
        }
//
        for (latch in latchList) latch.await()
        showInfoBar(showAlert, errMsg, "Processing HTML...", 5_000L)
        processHtml(
            fileLocation,
            offsetList,
            fileOutputPath,
            imgOutputPath
        )
        val timingMsg = "TOTAL: Timing: ${System.currentTimeMillis() - timing} ms"
        showInfoBar(showAlert, errMsg, timingMsg, 60_000L)
    }

    val DATE_REGEX = Regex(".*日期: (\\d{4}-\\d{2}-\\d{2}).*")
    fun RandomAccessFile.readLineInUtf8() = run {
        val rawLine: String = readLine()
        String(rawLine.toByteArray(Charset.forName("ISO-8859-1")), UTF_8)
    }

    const val END_OF_HTML = "</table></body></html>"
    private fun processHtml(
        fileLocation: String,
        offsetList: java.util.ArrayList<Long>,
        fileOutputPath: String,
        imgOutputPath: String,
        lineLimit: Int = 7500
    ) {
        val styleClassNameMap = ConcurrentHashMap<String, String>()
        val lineDeque = ConcurrentLinkedDeque<String>()
        val raf = RandomAccessFile(fileLocation, "r")
        val imgOutputFolder = File(imgOutputPath)
        val imgFileNameExtensionMap: Map<String, String> = getImgFileNameExtensionMap(imgOutputFolder)
        val firstBoundaryOffset = offsetList[0]
        raf.seek(firstBoundaryOffset)
        var line: String
        var firstLineOffset = -1L
        while (raf.readLineInUtf8().also { line = it } != null) {
            if (line.isEmpty()) {
                firstLineOffset = raf.filePointer
                break
            }
        }

        raf.seek(firstLineOffset)
        val firstLine = raf.readLineInUtf8()
        val (remainOfFirstLine, htmlHeadTemplate, globalStyleSheet, startDateInUTC, dateLineWithPlaceHolder) = handleFirstLine(
            firstLine,
            styleClassNameMap,
            imgFileNameExtensionMap,
            imgOutputFolder
        )
        // BufferedReader(FileReader(fileLocation))

        var dateForHtmlHead = startDateInUTC
        var currentDate = startDateInUTC
        var fileCounter = 0

        lineDeque.offer(remainOfFirstLine)
        var tmpLine: String?
        while (raf.readLineInUtf8().also { tmpLine = it } != null) {
            val line = tmpLine!!
            val (refactoredLine, newDate) = extractLineAndReplaceStyle(
                line,
                styleClassNameMap,
                currentDate,
                imgFileNameExtensionMap,
                imgOutputFolder
            )
            progress?.value = (raf.filePointer.toFloat()) / (offsetList[1].toFloat())
            if (raf.filePointer > offsetList[1]) break;
            // System.err.println(refactoredLine)
            // System.err.println(newDate?.toInstant()?.toString() ?: "")
            if (newDate != null) { // Time to write file
                if (lineDeque.size > lineLimit) {
                    writeFragmentFile(
                        fileLocation,
                        ++fileCounter,
                        fileOutputPath,
                        htmlHeadTemplate,
                        globalStyleSheet,
                        styleClassNameMap,
                        dateForHtmlHead,
                        lineDeque,
                        END_OF_HTML
                    )
                    dateForHtmlHead = newDate!!
                }
                currentDate = newDate!!
            } else {
                lineDeque.offer(refactoredLine)
            }
        }
        writeFragmentFile(
            fileLocation,
            ++fileCounter,
            fileOutputPath,
            htmlHeadTemplate,
            globalStyleSheet,
            styleClassNameMap,
            dateForHtmlHead,
            lineDeque,
            END_OF_HTML
        )
    }

    private fun writeFragmentFile(
        fileLocation: String,
        fileCounter: Int,
        fileOutputPath: String,
        htmlHeadTemplate: String,
        globalStyleSheet: String,
        styleClassNameMap: ConcurrentHashMap<String, String>,
        dateForHtmlHead: Date,
        lineDeque: ConcurrentLinkedDeque<String>,
        endOfHtml: String
    ) {
        val fragmentFileName = File(fileLocation).nameWithoutExtension + "_${"%03d".format(fileCounter)}.html"
        val fragmentFile = File(fileOutputPath).resolve(fragmentFileName)
        if (fragmentFile.exists()) {
            System.err.println("$fragmentFileName exists! Overwriting")
        }
        fragmentFile.createNewFile()
        val fw = FileWriter(fragmentFile, UTF_8)
        val bfw = BufferedWriter(fw)
        val fileHead = htmlHeadTemplate.replace(
            STYLE_PLACEHOLDER,
            globalStyleSheet + styleClassNameMap.map { ".${it.value}{$it.key}" }.joinToString(separator = "") { it })
            .replace(DATE_PLACEHOLDER, YYYY_MM_DD_DATE_FORMATTER_UTC.format(dateForHtmlHead))
        bfw.write(fileHead)
        while (lineDeque.isNotEmpty()) {
            bfw.write(lineDeque.poll())
        }
        bfw.write(endOfHtml)
        bfw.flush()
        bfw.close()
    }

    private fun getImgFileNameExtensionMap(imgOutputFolder: File): Map<String, String> {
        if (!imgOutputFolder.isDirectory) {
            throw RuntimeException("Image output path is not a folder!")
        }
        val m = imgOutputFolder.listFiles().groupBy({ it.nameWithoutExtension }, { it.extension })
        val result = HashMap<String, String>()
        for (e in m.entries) {
            result[e.key] = e.value[0]
        }
        return result
    }

    private const val STYLE_PLACEHOLDER = "#STYLE_PLACEHOLDER"
    private const val DATE_PLACEHOLDER = "#DATE_PLACEHOLDER"
    private val YYYY_MM_DD_DATE_FORMATTER_UTC =
        SimpleDateFormat("yyyy-MM-dd").apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val YYYY_MM_DD_HH_MM_SS_Z_FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")

    const val CLOSING_TITLE_TAG = "</title>"
    const val OPENNING_STYLE_TAG_FOR_FIRSTLINE = "<style type=\"text/css\">"
    const val CLOSING_STYLE_TAG = "</style>"
    const val BEFORE_DATE_TEXT = "&nbsp;</div></td></tr>"
    const val AFTER_DATE_TEXT = "</td></tr>"
    const val CRLF = "\r\n"
    private fun handleFirstLine(
        rawFirstLine: String,
        styleClassNameMap: MutableMap<String, String>,
        imgFileNameExtensionMap: Map<String, String>,
        imgOutputFolder: File
    ): ExtractFirstLineResult {
        val (firstLine, _) = extractLineAndReplaceStyle(
            rawFirstLine,
            styleClassNameMap,
            Date(),
            imgFileNameExtensionMap,
            imgOutputFolder,
            isConvertTimeToDate = false
        )
        var sb = StringBuilder()
        val startOfStyleTag = firstLine.indexOf(CLOSING_TITLE_TAG) + CLOSING_TITLE_TAG.length
        val endOfStyleTagPlusOne = firstLine.indexOf(CLOSING_STYLE_TAG, startOfStyleTag) + CLOSING_STYLE_TAG.length
        val styleWithTag = firstLine.substring(startOfStyleTag, endOfStyleTagPlusOne)
        val globalStyleSheet =
            styleWithTag.substring(
                OPENNING_STYLE_TAG_FOR_FIRSTLINE.length,
                styleWithTag.length - CLOSING_STYLE_TAG.length
            )
        val startOfDateCell = firstLine.indexOf(BEFORE_DATE_TEXT) + BEFORE_DATE_TEXT.length
        val endOfDateCellPlusOne = firstLine.indexOf(AFTER_DATE_TEXT, startOfDateCell) + AFTER_DATE_TEXT.length
        val dateCell = firstLine.substring(startOfDateCell, endOfDateCellPlusOne)
        sb.append(firstLine.substring(0, startOfStyleTag))
        sb.append(CRLF)
        sb.append("$OPENNING_STYLE_TAG_FOR_FIRSTLINE$STYLE_PLACEHOLDER$CLOSING_STYLE_TAG")
        sb.append(firstLine.substring(endOfStyleTagPlusOne, startOfDateCell))


        assert(DATE_REGEX.matches(firstLine))
        var startDate = Date()
        var startDateStr = ""
        if (DATE_REGEX.matches(firstLine)) {
            startDateStr = extractDateFromLine(firstLine).first
            startDate = extractDateFromLine(firstLine).second
        }

        val dateLineWithPlaceHolder = dateCell.replace(startDateStr, DATE_PLACEHOLDER)
        sb.append(CRLF)
        sb.append(dateLineWithPlaceHolder)
        val htmlHeadTemplate = sb.toString()

        return ExtractFirstLineResult(
            firstLine.substring(endOfDateCellPlusOne),
            htmlHeadTemplate,
            globalStyleSheet,
            startDate,
            dateLineWithPlaceHolder
        )
    }

    data class ExtractFirstLineResult(
        val remainOfFirstLine: String,
        val htmlHeadTemplate: String,
        val globalStyleSheet: String,
        val date: Date,
        val dateLine: String
    )

    private fun extractDateFromLine(firstLine: String): Pair<String, Date> {
        val yyyyMmDd = DATE_REGEX.find(firstLine)!!.groupValues[1]
        return Pair(yyyyMmDd, YYYY_MM_DD_DATE_FORMATTER_UTC.parse(yyyyMmDd))
    }

    val TIME_REGEX = Regex(".*</div>(\\d+:\\d{2}:\\d{2})</div>.*")
    const val IMG_TAG_OPENING = "<IMG src=\"{"
    const val IMG_TAG_CLOSING = "}.dat\">"
    const val IMG_FILENAME_LENGTH = "96F1308E-DDB6-44b1-98D1-16EE42C52F27".length
    const val STYLE_PREFIX = "style="
    const val STYLE_PREFIX_WITH_QUOTE = "style=\""
    const val STYLE_SUFFIX = ">"
    const val STYLE_SUFFIX_WITH_QUOTE = "\""

    private fun extractLineAndReplaceStyle(
        line: String,
        styleClassNameMap: MutableMap<String, String>,
        date: Date,
        imgFileNameExtensionMap: Map<String, String>,
        imgOutputFolder: File,
        isConvertTimeToDate: Boolean = true
    ): PerLineExtractResult {
        var currentDate = date
        var newDate: Date? = null
        var prevIndex = 0
        var sb = StringBuilder()
        var tmpIndex: Int
        var isWithQuote: Boolean
        val imgRelativeFolder = imgOutputFolder.name

        // Handling Style
        while (line.indexOf(STYLE_PREFIX, prevIndex).also { tmpIndex = it } > 0) {
            val startOfStyleAttribute = tmpIndex
            isWithQuote = line[tmpIndex + STYLE_PREFIX.length] == '"'
            var endOfStyleAttribute: Int
            var styleSheet = if (isWithQuote) {
                endOfStyleAttribute =
                    line.indexOf(STYLE_SUFFIX_WITH_QUOTE, startOfStyleAttribute + STYLE_PREFIX_WITH_QUOTE.length)
                line.substring(startOfStyleAttribute + STYLE_PREFIX_WITH_QUOTE.length, endOfStyleAttribute)
            } else {
                endOfStyleAttribute = line.indexOf(STYLE_SUFFIX, startOfStyleAttribute + STYLE_PREFIX.length)
                line.substring(startOfStyleAttribute + STYLE_PREFIX.length, endOfStyleAttribute)
            }
            if (!styleClassNameMap.contains(styleSheet)) {
                val className = "stl-" + (styleClassNameMap.size + 1)
                styleClassNameMap[styleSheet] = className
            }
            sb.append(line.substring(prevIndex, startOfStyleAttribute))
            sb.append("class=\"${styleClassNameMap[styleSheet]!!}\"")
            prevIndex = endOfStyleAttribute
        }
        sb.append(line.substring(prevIndex))

        // Handling Date
        if (DATE_REGEX.matches(line)) {
            newDate = extractDateFromLine(line).second
            currentDate = newDate
        }

        val refactoredLineWithoutDateConverting = sb.toString()
        var refactoredLine = refactoredLineWithoutDateConverting
        if (isConvertTimeToDate && TIME_REGEX.matches(refactoredLineWithoutDateConverting)) {
            val time = TIME_REGEX.find(refactoredLineWithoutDateConverting)!!.groupValues[1]
            val convertedDate = getConvertedDate(time, currentDate)
            refactoredLine =
                refactoredLineWithoutDateConverting.replaceFirst(
                    time, YYYY_MM_DD_HH_MM_SS_Z_FORMATTER.format(convertedDate)
                            + "<div class=\"qqts\" hidden>${convertedDate.time}</div>"
                )
        }

        // Handling IMG tags
        var tmpIdxForImgTag = 0
        var prevIdxForImgTag = 0
        sb = StringBuilder()
        while (refactoredLine.indexOf(IMG_TAG_OPENING, prevIdxForImgTag).also { tmpIdxForImgTag = it } >= 0) {
            sb.append(refactoredLine.substring(prevIdxForImgTag, tmpIdxForImgTag))
            val filename = refactoredLine.substring(
                tmpIdxForImgTag + IMG_TAG_OPENING.length,
                tmpIdxForImgTag + IMG_TAG_OPENING.length + IMG_FILENAME_LENGTH
            )
            if (imgFileNameExtensionMap[filename] == null) {
                System.err.println("IMG $filename doesn't exist!")
            }
            sb.append("<img src=\"$imgRelativeFolder/$filename.${imgFileNameExtensionMap[filename] ?: "dat"}\" loading=\"lazy\"/>")
            prevIdxForImgTag =
                tmpIdxForImgTag + IMG_TAG_OPENING.length + IMG_FILENAME_LENGTH + IMG_TAG_CLOSING.length
        }
        sb.append(refactoredLine.substring(prevIdxForImgTag))
        refactoredLine = sb.toString()

        // Point Bracket > (Greater-than mark)
        refactoredLine = refactoredLine.replace("&get;", "&gt;")

        return PerLineExtractResult(refactoredLine, newDate)
    }

    private val H_MM_SS_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm:ss")
    private fun getConvertedDate(time: String, currentDate: Date): Date {
        val localTime = LocalTime.parse(time, H_MM_SS_DATE_TIME_FORMATTER)

        val calIns = Calendar.getInstance()
        calIns.time = currentDate
        calIns.timeZone = TimeZone.getDefault() // Redundant
        calIns.set(Calendar.HOUR_OF_DAY, localTime.hour)
        calIns.set(Calendar.MINUTE, localTime.minute)
        calIns.set(Calendar.SECOND, localTime.second)
        return calIns.time
    }

    data class PerLineExtractResult(val extractedAndReplaced: String, val newDate: Date?)

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
                while (raf.readLineInUtf8().also { line = it } != null) {
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
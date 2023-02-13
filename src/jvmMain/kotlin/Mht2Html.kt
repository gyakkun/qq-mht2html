import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import org.apache.commons.codec.binary.Base64
import org.apache.commons.imaging.Imaging
import org.apache.commons.lang3.StringEscapeUtils
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.text.Charsets.UTF_8

@ExperimentalMaterialApi
@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
object Mht2Html {
    private lateinit var BOUNDARY: String
    private const val DEFAULT_THREAD_COUNT = 3
    const val DEFAULT_LINE_LIMIT = 7500
    var showAlert: MutableState<Boolean>? = null
    var errMsg: MutableState<String>? = null
    var progress: MutableState<Float>? = null
    val LOGGER = LoggerFactory.getLogger(Mht2Html::class.java)

    fun doJob(
        fileLocation: String,
        fileOutputPath: String,
        imgOutputPath: String,
        threadCount: Int = DEFAULT_THREAD_COUNT,
        lineLimit: Int = DEFAULT_LINE_LIMIT,
        showAlert: MutableState<Boolean>?,
        errMsg: MutableState<String>?,
        progress: MutableState<Float>?,
        noHtml: Boolean = false,
        noImage: Boolean = false
    ) = GlobalScope.launch {
        Mht2Html.showAlert = showAlert
        Mht2Html.errMsg = errMsg
        Mht2Html.progress = progress
        LOGGER.info("Thread count: $threadCount")

        showInfoBar(showAlert, errMsg, "Processing Images...", -1L)
        FILENAME_COUNTER.clear()

        val tp = newFixedThreadPoolContext(threadCount + 1, "mht2html") // 1 more thread for producer
        val timing: Long = System.currentTimeMillis()
        val raf = RandomAccessFile(fileLocation, "r")
        val latchList = ArrayList<CountDownLatch>(threadCount)
        val offsetList = ArrayList<Long>()

        val processImgResult =
            processImage(
                raf,
                imgOutputPath,
                showAlert,
                errMsg,
                threadCount,
                latchList,
                tp,
                fileLocation,
                offsetList,
                noImage = noImage
            )

        if (!processImgResult) return@launch

        for (latch in latchList) latch.await()

        showInfoBar(showAlert, errMsg, "Processing HTML...", -1L)

        if (!noHtml) {
            processHtml(
                fileLocation,
                offsetList,
                fileOutputPath,
                imgOutputPath,
                lineLimit
            )
        }

        val timingMsg = "TOTAL: Timing: ${System.currentTimeMillis() - timing} ms"

        showInfoBar(showAlert, errMsg, timingMsg, -1L)
        progress!!.value = 1.0F
        tp.close()
    }

    private suspend fun processImage(
        raf: RandomAccessFile,
        imgOutputPath: String,
        showAlert: MutableState<Boolean>?,
        errMsg: MutableState<String>?,
        threadCount: Int,
        latchList: ArrayList<CountDownLatch>,
        tp: ExecutorCoroutineDispatcher,
        fileLocation: String,
        offsetList: ArrayList<Long>,
        noImage: Boolean = false
    ): Boolean {
        raf.use {
            val fileOffset: Long
            val imgOutputFolder = File(imgOutputPath)
            if (!imgOutputFolder.exists()) {
                imgOutputFolder.mkdirs()
            }
            var line: String
            while (raf.readLineInUtf8().also { line = it } != null) {
                if (line.contains("boundary=\"")) {
                    BOUNDARY = "--" + line.substring(line.indexOf("=") + 2, line.length - 1)
                    fileOffset = raf.filePointer
                    LOGGER.info("First boundary offset: $fileOffset")
                    break
                }
                if (raf.filePointer > 1000) {
                    showInfoBar(
                        showAlert,
                        errMsg,
                        "Boundary not found in the first 1000 Bytes. MHT file may be not valid.",
                        -1L
                    )
                    return false
                }
            }
            repeat(threadCount) {
                latchList.add(CountDownLatch(1))
            }

            if (noImage) {
                showInfoBar(
                    showAlert,
                    errMsg,
                    "No Image option checked. Skipping Image process.",
                    -1L
                )
            }

            LOGGER.info("Boundary: $BOUNDARY")
            GlobalScope.launch(tp) {
                val producer = produceOffSet(fileLocation, offsetList, noImage = noImage) // The 1 more thread
                repeat(threadCount) {
                    launchConsumer(fileLocation, imgOutputFolder, latchList[it], producer)
                }
            }
            return true
        }
    }

    private val DATE_REGEX = Regex(".*日期: (\\d{4}-\\d{2}-\\d{2}).*")
    private val CHARSET_ISO_8859_1 = Charset.forName("ISO-8859-1")
    private fun RandomAccessFile.readLineInUtf8() = run {
        val rawLine: String = readLine()
        String(rawLine.toByteArray(CHARSET_ISO_8859_1), UTF_8)
    }

    private const val CRLF = "\r\n"
    private const val END_OF_HTML = "</table></body></html>"
    private const val MSG_OBJECT_PLACEHOLDER = "#MSG_OBJECT_PLACEHOLDER"
    private const val MSG_OBJECT_STR = "消息对象"
    private const val AFTER_MSG_OBJECT_INDICATOR = "<tr><td><div class=\"stl-3\"><div class=\"stl-4\">"
    private val AFTER_MSG_OBJECT_INDICATOR_REGEX =
        Regex("消息对象.*(<tr><td><div class=\"stl-[0-9]+\"><div class=\"stl-[0-9]+\">)")
    private const val IMG_MAX_WIDTH_HEIGHT_STYLE = "img {max-width: 66% !important;max-height: 512px;}"
    private val MSG_OBJECT_REGEX = Regex(".*消息对象:(.*?)</div>")
    private val MSG_OBJECT_COUNT_MAP = HashMap<String, Int>()
    private suspend fun processHtml(
        fileLocation: String,
        offsetList: ArrayList<Long>,
        fileOutputPath: String,
        imgOutputPath: String,
        lineLimit: Int
    ) {
        val styleClassNameMap = ConcurrentHashMap<String, String>()
        val lineDeque = ConcurrentLinkedDeque<String>()
        val raf = RandomAccessFile(fileLocation, "r")
        val imgOutputFolder = File(imgOutputPath)
        val imgFileNameExtensionMap: Map<String, String> = getImgFileNameExtensionMap(imgOutputFolder)
        val firstBoundaryOffset = offsetList[0]


        var remainOfFirstLine: String
        var htmlHeadTemplate: String
        var globalStyleSheet: String
        var startDateInUTC: Date
        val firstLine: String
        val outerLineCounter = AtomicInteger(0)

        try {
            raf.use {
                it.seek(firstBoundaryOffset)
                var line: String
                var firstLineOffset = -1L
                LOGGER.info("Seeking first HTML line offset.")
                while (it.readLineInUtf8().also { line = it } != null) {
                    if (line.isEmpty()) {
                        firstLineOffset = it.filePointer
                        break
                    }
                }
                LOGGER.info("First line offset {}", firstLineOffset)
                it.seek(firstLineOffset)
                firstLine = it.readLineInUtf8()
                val firstLineExtractResult = handleFirstLine(
                    firstLine,
                    styleClassNameMap,
                    imgFileNameExtensionMap,
                    imgOutputFolder
                )
                LOGGER.info("First line extracted: {}", firstLineExtractResult)
                remainOfFirstLine = firstLineExtractResult.remainOfFirstLine
                htmlHeadTemplate = firstLineExtractResult.htmlHeadTemplate
                globalStyleSheet = firstLineExtractResult.globalStyleSheet + CRLF
                globalStyleSheet += IMG_MAX_WIDTH_HEIGHT_STYLE
                startDateInUTC = firstLineExtractResult.date
            }

            // Count Lines

            // 1) How many lines from the head of file to start point of the HTML part
            val lineNoEndOfHtml = countLineOfFileUntilTarget(fileLocation, END_OF_HTML)

            // 2) How many lines from the head of file to start point of the HTML part
            val lineNoStartOfHtml = countLineOfFileUntilTarget(fileLocation, "<html")

            // 3) Get how many lines of the html part

            val totalLineOfHtml = lineNoEndOfHtml - lineNoStartOfHtml + 1
            var msgObject = MSG_OBJECT_REGEX.find(firstLine)!!.groupValues[1]
            MSG_OBJECT_COUNT_MAP[msgObject] = 1

            FileReader(fileLocation, UTF_8).use { fr ->
                BufferedReader(fr).use { bfr ->
                    repeat(lineNoStartOfHtml) {
                        bfr.readLine()
                        outerLineCounter.incrementAndGet()
                    }
                    LOGGER.info("Skipped to the first line of html: {} lines skipped", outerLineCounter.get())

                    var dateForHtmlHead = startDateInUTC
                    var currentDate = startDateInUTC
                    var lineCounter = 0

                    lineDeque.offer(remainOfFirstLine)
                    var tmpLine: String?
                    while (bfr.readLine().also { tmpLine = it } != null) {
                        try {
                            lineCounter++
                            outerLineCounter.incrementAndGet()
                            LOGGER.debug("Handling line {}", outerLineCounter.get())
                            progress?.value = lineCounter.toFloat() / totalLineOfHtml.toFloat()
                            if (lineCounter >= totalLineOfHtml - 1) { // The last line is END_OF_HTML line
                                break
                            }

                            val line = tmpLine!!
                            val (refactoredLine, newDate) = extractLineAndReplaceStyle(
                                line,
                                styleClassNameMap,
                                currentDate,
                                imgFileNameExtensionMap,
                                imgOutputFolder
                            )

                            if (refactoredLine.contains(MSG_OBJECT_STR)) {
                                var newMsgObject = MSG_OBJECT_REGEX.find(refactoredLine)!!.groupValues[1]
                                if (MSG_OBJECT_COUNT_MAP[newMsgObject] != null) {
                                    MSG_OBJECT_COUNT_MAP[newMsgObject] = MSG_OBJECT_COUNT_MAP[newMsgObject]!! + 1
                                    newMsgObject = newMsgObject + "#" + MSG_OBJECT_COUNT_MAP[newMsgObject]
                                } else {
                                    MSG_OBJECT_COUNT_MAP[newMsgObject] = 1
                                }
                                if (newMsgObject != msgObject) { // Bypass the first html line of mht file
                                    writeFragmentFile(
                                        fileOutputPath,
                                        htmlHeadTemplate.replace(MSG_OBJECT_PLACEHOLDER, msgObject),
                                        globalStyleSheet,
                                        styleClassNameMap,
                                        dateForHtmlHead,
                                        lineDeque,
                                        msgObject
                                    )
                                    msgObject = newMsgObject
                                    assert(newDate != null)
                                    if (newDate != null) {
                                        dateForHtmlHead = newDate
                                    }
                                    lineDeque.offer(
                                        refactoredLine.substring(
                                            AFTER_MSG_OBJECT_INDICATOR_REGEX.find(refactoredLine)?.groups?.get(1)?.range?.first
                                                ?: 0
                                        )
                                    )
                                }
                            } else if (newDate != null) { // Time to write file
                                if (lineDeque.size > lineLimit) {
                                    writeFragmentFile(
                                        fileOutputPath,
                                        htmlHeadTemplate.replace(MSG_OBJECT_PLACEHOLDER, msgObject),
                                        globalStyleSheet,
                                        styleClassNameMap,
                                        dateForHtmlHead,
                                        lineDeque,
                                        msgObject
                                    )
                                    dateForHtmlHead = newDate
                                }
                                currentDate = newDate
                                lineDeque.offer(refactoredLine)
                            } else {
                                lineDeque.offer(refactoredLine)
                            }
                        } catch (innerEx: Exception) {
                            LOGGER.error("Failed to handle line ${outerLineCounter.get()}. Line content: ${tmpLine}.", innerEx)
                            LOGGER.error("Please raise a Github issue and attach the log file where you think the exception you hit is important.")
                            LOGGER.error("Please remember to remove any sensitive message in the previous lines of log.")
                        }
                    }
                    writeFragmentFile(
                        fileOutputPath,
                        htmlHeadTemplate.replace(MSG_OBJECT_PLACEHOLDER, msgObject),
                        globalStyleSheet,
                        styleClassNameMap,
                        dateForHtmlHead,
                        lineDeque,
                        msgObject
                    )
                }
            }
        } catch (ex: Exception) {
            LOGGER.error("Exception occurred while handling HTML at line {} ", outerLineCounter.get(), ex)
            showInfoBar(
                showAlert,
                errMsg,
                "Exception occurred at line ${outerLineCounter.get()}. Please check log and raise a Github issue for support.",
                -1L
            )
        }
    }

    private val ILLEGAL_CHAR_IN_FILENAME_REGEX = Regex("[%&{}<>*$@`'+=:/\\\\?|\"]")
    private val WHITE_SPACE_REGEX = Regex("\\p{Z}")
    private fun sanitizeFilename(filename: String): String {
        return StringEscapeUtils.unescapeHtml4(filename)
            .replace(ILLEGAL_CHAR_IN_FILENAME_REGEX, "-")
            .replace(WHITE_SPACE_REGEX, "_")
    }

    private fun countLineOfFileUntilTarget(fileLocation: String, targetOffset: String): Int {
        var tmpLine: String
        var lineCount = 0

        FileReader(fileLocation).use { fr ->
            BufferedReader(fr).use { bfr ->
                while (bfr.readLine().also { tmpLine = it } != null) {
                    lineCount++
                    if (tmpLine.startsWith(targetOffset)) break
                }
            }
        }
        return lineCount
    }

    private val FILENAME_COUNTER = HashMap<String, Int>()

    private fun writeFragmentFile(
        fileOutputPath: String,
        htmlHeadTemplate: String,
        globalStyleSheet: String,
        styleClassNameMap: ConcurrentHashMap<String, String>,
        dateForHtmlHead: Date,
        lineDeque: ConcurrentLinkedDeque<String>,
        msgObject: String
    ) {
        val sanitizedFilenamePrefix = sanitizeFilename(msgObject)
        FILENAME_COUNTER.putIfAbsent(sanitizedFilenamePrefix, 0)
        FILENAME_COUNTER[sanitizedFilenamePrefix] = FILENAME_COUNTER[sanitizedFilenamePrefix]!! + 1
        val fileCount = FILENAME_COUNTER[sanitizedFilenamePrefix]
        val fragmentFileName =
            sanitizedFilenamePrefix + "_${"%03d".format(FILENAME_COUNTER[sanitizedFilenamePrefix]!!)}.html"
        val fragmentFile = File(fileOutputPath).resolve(fragmentFileName)
        if (fragmentFile.exists()) {
            LOGGER.info("$fragmentFileName exists! Overwriting")
        }
        fragmentFile.createNewFile()
        FileWriter(fragmentFile, UTF_8).use { fw ->
            BufferedWriter(fw).use { bfw ->
                var fileHead = htmlHeadTemplate.replace(
                    STYLE_PLACEHOLDER,
                    globalStyleSheet + styleClassNameMap.map { "." + it.value + "{" + it.key + "}" }
                        .joinToString(separator = CRLF) { it })
                    .replace(DATE_PLACEHOLDER, YYYY_MM_DD_DATE_FORMATTER_UTC.format(dateForHtmlHead))
                if (fileCount != 1) {
                    fileHead = fileHead.replaceFirst(">日期", " hidden>日期")
                }
                bfw.write(fileHead)
                bfw.write(CRLF)
                while (lineDeque.isNotEmpty()) {
                    bfw.write(lineDeque.poll())
                    bfw.write(CRLF)
                }
                bfw.write(END_OF_HTML)
            }
        }
    }

    private fun getImgFileNameExtensionMap(imgOutputFolder: File): Map<String, String> {
        if (!imgOutputFolder.isDirectory) {
            throw RuntimeException("Image output path is not a folder!")
        }
        val m = imgOutputFolder.listFiles()?.groupBy({ it.nameWithoutExtension }, { it.extension }) ?: emptyMap()
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

    private const val CLOSING_TITLE_TAG = "</title>"
    private const val OPENING_STYLE_TAG_FOR_FIRST_LINE = "<style type=\"text/css\">"
    private const val CLOSING_STYLE_TAG = "</style>"
    private const val BEFORE_DATE_TEXT = "&nbsp;</div></td></tr>"
    private const val AFTER_DATE_TEXT = "</td></tr>"
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
        val sb = StringBuilder()
        val startOfStyleTag = firstLine.indexOf(CLOSING_TITLE_TAG) + CLOSING_TITLE_TAG.length
        val endOfStyleTagPlusOne = firstLine.indexOf(CLOSING_STYLE_TAG, startOfStyleTag) + CLOSING_STYLE_TAG.length
        val styleWithTag = firstLine.substring(startOfStyleTag, endOfStyleTagPlusOne)
        val globalStyleSheet =
            styleWithTag.substring(
                OPENING_STYLE_TAG_FOR_FIRST_LINE.length,
                styleWithTag.length - CLOSING_STYLE_TAG.length
            )
        val startOfDateCell = firstLine.indexOf(BEFORE_DATE_TEXT) + BEFORE_DATE_TEXT.length
        val endOfDateCellPlusOne = firstLine.indexOf(AFTER_DATE_TEXT, startOfDateCell) + AFTER_DATE_TEXT.length
        val dateCell = firstLine.substring(startOfDateCell, endOfDateCellPlusOne)
        sb.append(firstLine.substring(0, startOfStyleTag))
        sb.append(CRLF)
        sb.append("$OPENING_STYLE_TAG_FOR_FIRST_LINE$STYLE_PLACEHOLDER$CLOSING_STYLE_TAG")
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
        var htmlHeadTemplate = sb.toString()
        val msgObj = MSG_OBJECT_REGEX.find(htmlHeadTemplate)!!.groupValues[1]
        htmlHeadTemplate = htmlHeadTemplate.replace(msgObj, MSG_OBJECT_PLACEHOLDER)

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

    private val TIME_REGEX_CN = Regex(".*</div>(\\d+:\\d{2}:\\d{2})</div>.*")
    private val TIME_REGEX_EN = Regex(".*</div>(\\d+:\\d{2}:\\d{2}&nbsp;(AM|PM))</div>.*")
    private const val IMG_TAG_OPENING = "<IMG src=\"{"
    private const val IMG_TAG_CLOSING = "}.dat\">"
    private const val IMG_FILENAME_LENGTH = "96F1308E-DDB6-44b1-98D1-16EE42C52F27".length
    private const val STYLE_PREFIX = "style="
    private const val STYLE_PREFIX_WITH_QUOTE = "style=\""
    private const val STYLE_SUFFIX = ">"
    private const val STYLE_SUFFIX_WITH_QUOTE = "\""

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
            val styleSheet = if (isWithQuote) {
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
        if (isConvertTimeToDate) {
            var timeStr = ""
            var convertedDate = Date()
            var isWriteDate = true
            if (TIME_REGEX_CN.matches(refactoredLineWithoutDateConverting)) {
                timeStr = TIME_REGEX_CN.find(refactoredLineWithoutDateConverting)!!.groupValues[1]
                convertedDate = getConvertedDate(timeStr, currentDate)
            } else if (TIME_REGEX_EN.matches(refactoredLineWithoutDateConverting)) {
                timeStr = TIME_REGEX_EN.find(refactoredLineWithoutDateConverting)!!.groupValues[1]
                val trimmedTimeStr = timeStr.replaceFirst("&nbsp;", " ")
                convertedDate = getConvertedDate(trimmedTimeStr, currentDate, isAmPm = true)
            } else {
                isWriteDate = false
            }
            if (isWriteDate) {
                refactoredLine =
                    refactoredLineWithoutDateConverting.replaceFirst(
                        timeStr, YYYY_MM_DD_HH_MM_SS_Z_FORMATTER.format(convertedDate)
                                + "<div class=\"qqts\" hidden>${convertedDate.time}</div>"
                    )
            }
        }

        // Handling IMG tags
        var tmpIdxForImgTag: Int
        var prevIdxForImgTag = 0
        sb = StringBuilder()
        while (refactoredLine.indexOf(IMG_TAG_OPENING, prevIdxForImgTag).also { tmpIdxForImgTag = it } >= 0) {
            sb.append(refactoredLine.substring(prevIdxForImgTag, tmpIdxForImgTag))
            val filename = refactoredLine.substring(
                tmpIdxForImgTag + IMG_TAG_OPENING.length,
                tmpIdxForImgTag + IMG_TAG_OPENING.length + IMG_FILENAME_LENGTH
            )
            if (imgFileNameExtensionMap[filename] == null) {
                LOGGER.info("IMG $filename doesn't exist!")
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
    private val H_MM_SS_AA_DATE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH)

    private fun getConvertedDate(time: String, currentDate: Date, isAmPm: Boolean = false): Date {
        val localTime =
            LocalTime.parse(time, if (isAmPm) H_MM_SS_AA_DATE_TIME_FORMATTER else H_MM_SS_DATE_TIME_FORMATTER)
        val calIns = Calendar.getInstance()
        calIns.time = currentDate
        calIns.timeZone = TimeZone.getDefault() // Redundant
        calIns.set(Calendar.HOUR_OF_DAY, localTime.hour)
        calIns.set(Calendar.MINUTE, localTime.minute)
        calIns.set(Calendar.SECOND, localTime.second)
        return calIns.time
    }

    data class PerLineExtractResult(val extractedAndReplaced: String, val newDate: Date?)

    private fun CoroutineScope.launchConsumer(
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
            val fileExt = kotlin.runCatching { Imaging.guessFormat(decode).defaultExtension }
                .onFailure {
                    LOGGER.info(
                        "Exception occurs when guessing image format: uuid=$uuid," +
                                " beginOffset=$beginOffsetOfB64, endOffset=$endOffsetOfB64"
                    )
                    LOGGER.info(it.message)
                }.getOrDefault("DAT")

            FileOutputStream(imgOutputFolder.resolve("$uuid.$fileExt")).use { fos ->
                BufferedOutputStream(fos).use { bfos ->
                    bfos.write(decode)
                    bfos.flush()
                }
            }
        }
        latch.countDown()
    }

    private fun CoroutineScope.produceOffSet(
        fileLocation: String,
        offsetList: ArrayList<Long>,
        noImage: Boolean = false
    ) =
        produce {
            val raf = RandomAccessFile(fileLocation, "r")
            var nextOffset: Long
            val sunday = Sunday(raf, 0L, BOUNDARY.toByteArray())
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
                        // LOGGER.info(uuid)
                        raf.readLine()
                        break
                    }
                }
                val beginOffsetOfB64 = raf.filePointer
                val endOffsetOfB64 = offsetList[ls - 1]
                if (noImage) break
                send(Triple(beginOffsetOfB64, endOffsetOfB64, uuid))
            }
            this.channel.close()
        }
}
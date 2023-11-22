import Mht2Html.Constants.CharsetRelated.CHARSET_ISO_8859_1
import Mht2Html.Constants.DateRelated.AFTER_DATE_TEXT
import Mht2Html.Constants.DateRelated.BEFORE_DATE_TEXT
import Mht2Html.Constants.DateRelated.DATE_PLACEHOLDER
import Mht2Html.Constants.DateRelated.DATE_REGEX
import Mht2Html.Constants.DateRelated.STYLE_PLACEHOLDER
import Mht2Html.Constants.DateRelated.YYYY_MM_DD_DATE_FORMATTER_UTC
import Mht2Html.Constants.DateRelated.YYYY_MM_DD_HH_MM_SS_Z_FORMATTER
import Mht2Html.Constants.DefaultConfig.DEFAULT_LINE_LIMIT
import Mht2Html.Constants.DefaultConfig.DEFAULT_THREAD_COUNT
import Mht2Html.Constants.FilenameSanitize.BETTER_NOT_TO_HAVE_IN_FILENAME_REGEX
import Mht2Html.Constants.FilenameSanitize.FILE_NAME_LENGTH_THRESHOLD
import Mht2Html.Constants.FilenameSanitize.ILLEGAL_CHAR_IN_FILENAME_REGEX
import Mht2Html.Constants.HtmlFragments.AFTER_MSG_CONTACT_INDICATOR_REGEX
import Mht2Html.Constants.HtmlFragments.CLOSING_STYLE_TAG
import Mht2Html.Constants.HtmlFragments.CLOSING_TITLE_TAG
import Mht2Html.Constants.HtmlFragments.CRLF
import Mht2Html.Constants.HtmlFragments.END_OF_HTML
import Mht2Html.Constants.HtmlFragments.IMG_FILENAME_LENGTH
import Mht2Html.Constants.HtmlFragments.IMG_MAX_WIDTH_HEIGHT_STYLE
import Mht2Html.Constants.HtmlFragments.IMG_TAG_CLOSING
import Mht2Html.Constants.HtmlFragments.IMG_TAG_OPENING
import Mht2Html.Constants.HtmlFragments.MSG_CONTACT_PLACEHOLDER
import Mht2Html.Constants.HtmlFragments.MSG_CONTACT_REGEX
import Mht2Html.Constants.HtmlFragments.MSG_CONTACT_STR
import Mht2Html.Constants.HtmlFragments.OPENING_STYLE_TAG_FOR_FIRST_LINE
import Mht2Html.Constants.HtmlFragments.STYLE_PREFIX
import Mht2Html.Constants.HtmlFragments.STYLE_PREFIX_WITH_QUOTE
import Mht2Html.Constants.HtmlFragments.STYLE_SUFFIX
import Mht2Html.Constants.HtmlFragments.STYLE_SUFFIX_WITH_QUOTE
import Mht2Html.Constants.TimeRelated.H_MM_SS_AA_DATE_TIME_FORMATTER
import Mht2Html.Constants.TimeRelated.H_MM_SS_DATE_TIME_FORMATTER
import Mht2Html.Constants.TimeRelated.TIME_REGEX_CN
import Mht2Html.Constants.TimeRelated.TIME_REGEX_EN
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.codec.binary.Base64
import org.apache.commons.imaging.Imaging
import org.apache.commons.text.StringEscapeUtils
import org.jetbrains.skiko.MainUIDispatcher
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
import kotlin.math.max
import kotlin.text.Charsets.UTF_8


private val LOGGER = LoggerFactory.getLogger(Mht2Html::class.java)

class Mht2Html(
    private val showAlert: MutableState<Boolean>? = null,
    private val errMsg: MutableState<String>? = null,
    private val progress: MutableState<Float>? = null,
    private val fileLocation: String,
    private val fileOutputPath: String,
    private val imgOutputPath: String,
    private val threadCount: Int = DEFAULT_THREAD_COUNT,
    private val lineLimit: Int = DEFAULT_LINE_LIMIT,
    private val noHtml: Boolean = false,
    private val noImage: Boolean = false
) {

    private lateinit var boundaryText: String
    private val filenameCounter = HashMap<String, Int>()
    private val msgContactCountMap = HashMap<String, Int>()
    private val leadingOffsetSet = ConcurrentHashMap.newKeySet<Long>()
    private val imgOutputFolder = File(imgOutputPath)
        .also {
            if (it.exists() && it.isFile) {
                val errStr = "The image output folder path exists and is a file!"
                runBlocking { showInfoBar(showAlert, errMsg, errStr) }
                throw IllegalArgumentException(errStr)
            }
        }
        .also { if (!it.exists()) it.mkdirs() }

    object Constants {
        object DefaultConfig {
            const val DEFAULT_LINE_LIMIT = 7500
            const val DEFAULT_THREAD_COUNT = 3
        }

        object CharsetRelated {
            val CHARSET_ISO_8859_1: Charset = Charset.forName("ISO-8859-1")
        }

        object HtmlFragments {
            const val CRLF = "\r\n"
            const val END_OF_HTML = "</table></body></html>"
            const val MSG_CONTACT_PLACEHOLDER = "#MSG_CONTACT_PLACEHOLDER"
            const val MSG_CONTACT_STR = "消息对象"
            const val AFTER_MSG_CONTACT_INDICATOR = "<tr><td><div class=\"stl-3\"><div class=\"stl-4\">"
            val AFTER_MSG_CONTACT_INDICATOR_REGEX =
                Regex("消息对象.*(<tr><td><div class=\"stl-[0-9]+\"><div class=\"stl-[0-9]+\">)")
            const val IMG_MAX_WIDTH_HEIGHT_STYLE = "img {max-width: 66% !important;max-height: 512px;}"
            val MSG_CONTACT_REGEX = Regex(".*消息对象:(.*?)</div>")

            const val CLOSING_TITLE_TAG = "</title>"
            const val OPENING_STYLE_TAG_FOR_FIRST_LINE = "<style type=\"text/css\">"
            const val CLOSING_STYLE_TAG = "</style>"

            const val IMG_TAG_OPENING = "<IMG src=\"{"
            const val IMG_TAG_CLOSING = "}.dat\">"
            const val IMG_FILENAME_LENGTH = "96F1308E-DDB6-44b1-98D1-16EE42C52F27".length
            const val STYLE_PREFIX = "style="
            const val STYLE_PREFIX_WITH_QUOTE = "style=\""
            const val STYLE_SUFFIX = ">"
            const val STYLE_SUFFIX_WITH_QUOTE = "\""
        }

        object FilenameSanitize {
            val ILLEGAL_CHAR_IN_FILENAME_REGEX = Regex("[%&{}<>*$@`'+=:/\\\\?|\"]")

            // See: https://www.unicode.org/reports/tr44/#GC_Values_Table
            val BETTER_NOT_TO_HAVE_IN_FILENAME_REGEX = Regex("[\\p{C}\\p{Z}\\p{M}\\p{S}]")
            const val FILE_NAME_LENGTH_THRESHOLD = 32
        }

        object DateRelated {
            val DATE_REGEX = Regex(".*日期: (\\d{4}-\\d{2}-\\d{2}).*")

            const val STYLE_PLACEHOLDER = "#STYLE_PLACEHOLDER"
            const val DATE_PLACEHOLDER = "#DATE_PLACEHOLDER"
            val YYYY_MM_DD_DATE_FORMATTER_UTC =
                SimpleDateFormat("yyyy-MM-dd").apply { timeZone = TimeZone.getTimeZone("UTC") }
            val YYYY_MM_DD_HH_MM_SS_Z_FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")

            const val BEFORE_DATE_TEXT = "&nbsp;</div></td></tr>"
            const val AFTER_DATE_TEXT = "</td></tr>"
        }

        object TimeRelated {
            val TIME_REGEX_CN = Regex(".*</div>(\\d+:\\d{2}:\\d{2})</div>.*")
            val TIME_REGEX_EN = Regex(".*</div>(\\d+:\\d{2}:\\d{2}&nbsp;(AM|PM))</div>.*")

            val H_MM_SS_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm:ss")
            val H_MM_SS_AA_DATE_TIME_FORMATTER: DateTimeFormatter =
                DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun doJob() = CoroutineScope(Dispatchers.IO).launch {
        LOGGER.info("Start to process input file: $fileLocation")
        LOGGER.info("Output location: $fileOutputPath")
        LOGGER.info("Img folder: $imgOutputPath")
        LOGGER.info("Line limit: $lineLimit")
        LOGGER.info("Thread count: $threadCount")

        showInfoBar(showAlert, errMsg, "Processing Images...", -1L)
        filenameCounter.clear()

        val tp = newFixedThreadPoolContext(threadCount + 1, "mht2html") // 1 more thread for producer
        val timing: Long = System.currentTimeMillis()
        val raf = RandomAccessFile(fileLocation, "r")
        val latch = CountDownLatch(threadCount)

        val processImgResult = processImage(
            randomAccessFile = raf,
            latch = latch,
            coroutineContext = null,
        )

        if (!processImgResult) return@launch

        latch.await()

        LOGGER.info("Image: Timing: ${System.currentTimeMillis() - timing} ms")

        showInfoBar(showAlert, errMsg, "Processing HTML...", -1L)

        if (!noHtml) {
            CoroutineScope(Dispatchers.IO).launch {
                processHtml()
            }
        }

        val timingMsg = "TOTAL: Timing: ${System.currentTimeMillis() - timing} ms"
        LOGGER.info(timingMsg)

        showInfoBar(showAlert, errMsg, timingMsg, -1L)
        updateProgress(1L, 1L)
        tp.close()
    }

    private suspend fun processImage(
        randomAccessFile: RandomAccessFile,
        latch: CountDownLatch,
        coroutineContext: ExecutorCoroutineDispatcher? = null,
    ): Boolean {
        randomAccessFile.use {
            val fileLen = it.length()
            val fileOffset: Long
            var line: String
            // Find boundary text line
            while (randomAccessFile.readLineInUtf8().also { line = it } != null) {
                if (line.contains("boundary=\"")) {
                    boundaryText = "--" + line.substring(line.indexOf("=") + 2, line.length - 1)
                    fileOffset = randomAccessFile.filePointer
                    LOGGER.info("First boundary offset: $fileOffset")
                    break
                }
                if (randomAccessFile.filePointer > 1000) {
                    LOGGER.error("Boundary not found in the first 1000 Bytes. MHT file may be not valid.")
                    LOGGER.error("Last line read: $line")
                    showInfoBar(
                        showAlert,
                        errMsg,
                        "Boundary not found in the first 1000 Bytes. MHT file may be not valid.",
                        -1L
                    )
                    return false
                }
            }


            if (noImage) {
                LOGGER.warn("No Image option checked. Skipping Image process.")
                showInfoBar(
                    showAlert,
                    errMsg,
                    "No Image option checked. Skipping Image process.",
                    -1L
                )
            }

            LOGGER.info("Boundary: $boundaryText")
            CoroutineScope(coroutineContext ?: Dispatchers.IO).launch {
                val totalProducerCount = threadCount / 4
                var consumerCount = threadCount - totalProducerCount
                repeat(totalProducerCount) { rank ->
                    val initOffset: Long = rank.toLong() * (fileLen / totalProducerCount.toLong())
                    val thresholdOffset: Long = (rank + 1).toLong() * (fileLen / totalProducerCount.toLong())
                    val chan = produceOffSet(
                        rank,
                        totalProducerCount,
                        initOffset,
                        thresholdOffset
                    )
                    latch.countDown()
                    repeat(3) {
                        consumerCount--
                        launchConsumer(rank, it, latch, chan)
                    }
                    if (rank == totalProducerCount - 1) {
                        repeat(consumerCount) {
                            consumerCount--
                            launchConsumer(rank, 3 + it, latch, chan)
                        }
                    }
                }
                assert(consumerCount == 0)
            }
            return true
        }
    }


    private fun RandomAccessFile.readLineInUtf8() = run {
        val rawLine: String = readLine()
        String(rawLine.toByteArray(CHARSET_ISO_8859_1), UTF_8)
    }


    private suspend fun processHtml() {
        LOGGER.info("Start processing html.")

        val styleClassNameMap = ConcurrentHashMap<String, String>()
        val lineDeque = ConcurrentLinkedDeque<String>()
        val raf = withContext(Dispatchers.IO) {
            RandomAccessFile(fileLocation, "r")
        }
        val imgFileNameExtensionMap: Map<String, String> = getImgFileNameExtensionMap()

        var remainOfFirstLine: String
        var htmlHeadTemplate: String
        var globalStyleSheet: String
        var startDateInUTC: Date
        var firstLine: String = ""
        val outerLineCounter = AtomicInteger(0)

        try {
            raf.use {
                var line: String
                var prevOffset = -1L
                var firstLineOffset: Long = -1L
                LOGGER.info("Seeking first HTML line.")
                while (it.readLineInUtf8().also { line = it } != null) {
                    if (line.startsWith("<html")) {
                        firstLine = line
                        firstLineOffset = prevOffset
                        break
                    }
                    prevOffset = it.filePointer
                }
                LOGGER.info("End of first line offset {}", it.filePointer)
                it.seek(firstLineOffset)
                val firstLineExtractResult = handleFirstLine(
                    firstLine,
                    styleClassNameMap,
                    imgFileNameExtensionMap,
                )
                LOGGER.info("First line extracted: {}", firstLineExtractResult)
                remainOfFirstLine = firstLineExtractResult.remainOfFirstLine
                htmlHeadTemplate = firstLineExtractResult.htmlHeadTemplate
                globalStyleSheet = firstLineExtractResult.globalStyleSheet + CRLF
                globalStyleSheet += IMG_MAX_WIDTH_HEIGHT_STYLE
                startDateInUTC = firstLineExtractResult.date
            }

            // Count Lines

            // 1) How many lines from the head of file to end point of the HTML part
            val lineNoEndOfHtml = countLineOfFileUntilTarget(fileLocation, END_OF_HTML)
            LOGGER.info("Q: How many lines from the head of file to end point of the HTML part? A: $lineNoEndOfHtml")

            // 2) How many lines from the head of file to start point of the HTML part
            val lineNoStartOfHtml = countLineOfFileUntilTarget(fileLocation, "<html")
            LOGGER.info("Q: How many lines from the head of file to start point of the HTML part? A: $lineNoStartOfHtml")

            // 3) Get how many lines of the html part

            val totalLineOfHtml = lineNoEndOfHtml - lineNoStartOfHtml + 1
            LOGGER.info("Total line of html: $totalLineOfHtml")

            var msgContact = MSG_CONTACT_REGEX.find(firstLine)!!.groupValues[1]
            LOGGER.info("FIRST MSG CONTACT NAME: $msgContact")
            msgContactCountMap[msgContact] = 1

            withContext(Dispatchers.IO) {
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
                                updateProgress(lineCounter.toLong(), totalLineOfHtml.toLong())
                                if (lineCounter >= totalLineOfHtml - 1) { // The last line is END_OF_HTML line
                                    break
                                }

                                val line = tmpLine!!
                                val (refactoredLine, newDate) = extractLineAndReplaceStyle(
                                    line,
                                    styleClassNameMap,
                                    currentDate,
                                    imgFileNameExtensionMap,
                                )

                                if (refactoredLine.contains(MSG_CONTACT_STR)) {
                                    var newMsgContact = MSG_CONTACT_REGEX.find(refactoredLine)!!.groupValues[1]
                                    if (msgContactCountMap[newMsgContact] != null) {
                                        msgContactCountMap[newMsgContact] = msgContactCountMap[newMsgContact]!! + 1
                                        newMsgContact = newMsgContact + "#" + msgContactCountMap[newMsgContact]
                                    } else {
                                        msgContactCountMap[newMsgContact] = 1
                                    }
                                    if (newMsgContact != msgContact) { // Bypass the first html line of mht file
                                        LOGGER.info("NEW MSG CONTACT NAME: $newMsgContact. Writing to new file.")
                                        writeFragmentFileWithCatch(
                                            htmlHeadTemplate.replace(MSG_CONTACT_PLACEHOLDER, msgContact),
                                            globalStyleSheet,
                                            styleClassNameMap,
                                            dateForHtmlHead,
                                            lineDeque,
                                            msgContact
                                        )
                                        msgContact = newMsgContact
                                        assert(newDate != null)
                                        if (newDate != null) {
                                            dateForHtmlHead = newDate
                                        }
                                        lineDeque.offer(
                                            refactoredLine.substring(
                                                AFTER_MSG_CONTACT_INDICATOR_REGEX.find(refactoredLine)?.groups?.get(1)?.range?.first
                                                    ?: 0
                                            )
                                        )
                                    }
                                } else if (newDate != null) { // Time to write file
                                    if (lineDeque.size > lineLimit) {
                                        LOGGER.info("Over line limit: ${lineDeque.size} > $lineLimit. Writing to new file.")
                                        writeFragmentFileWithCatch(
                                            htmlHeadTemplate.replace(MSG_CONTACT_PLACEHOLDER, msgContact),
                                            globalStyleSheet,
                                            styleClassNameMap,
                                            dateForHtmlHead,
                                            lineDeque,
                                            msgContact
                                        )
                                        dateForHtmlHead = newDate
                                    }
                                    currentDate = newDate
                                    lineDeque.offer(refactoredLine)
                                } else {
                                    lineDeque.offer(refactoredLine)
                                }
                            } catch (innerEx: Exception) {
                                LOGGER.error(
                                    "Failed to handle line ${outerLineCounter.get()}. Line content: ${tmpLine}.",
                                    innerEx
                                )
                                LOGGER.error("Please raise a GitHub issue and attach the log file where you think the exception you hit is important.")
                                LOGGER.error("Please remember to remove any sensitive message in the previous lines of log.")
                            }
                        }
                        LOGGER.info("Writing last piece of file.")
                        writeFragmentFileWithCatch(
                            htmlHeadTemplate.replace(MSG_CONTACT_PLACEHOLDER, msgContact),
                            globalStyleSheet,
                            styleClassNameMap,
                            dateForHtmlHead,
                            lineDeque,
                            msgContact
                        )
                    }
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


    private fun sanitizeFilename(filename: String): String {
        return StringEscapeUtils.unescapeHtml4(filename)
            .replace(BETTER_NOT_TO_HAVE_IN_FILENAME_REGEX, "_")
            .replace(ILLEGAL_CHAR_IN_FILENAME_REGEX, "_")
            .let {
                if (it.length > FILE_NAME_LENGTH_THRESHOLD) {
                    LOGGER.warn("Filename too long: $filename")
                    val remain = FILE_NAME_LENGTH_THRESHOLD - it.length
                    val res = it.substring(0, it.length.coerceAtMost(FILE_NAME_LENGTH_THRESHOLD)) + "_R${remain}"
                    LOGGER.warn("Stripped to $res")
                    res
                } else {
                    it
                }
            }
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

    private fun writeFragmentFileWithCatch(
        htmlHeadTemplate: String,
        globalStyleSheet: String,
        styleClassNameMap: ConcurrentHashMap<String, String>,
        dateForHtmlHead: Date,
        lineDeque: ConcurrentLinkedDeque<String>,
        msgContact: String
    ) = runCatching {
        writeFragmentFile(
            htmlHeadTemplate,
            globalStyleSheet,
            styleClassNameMap,
            dateForHtmlHead,
            lineDeque,
            msgContact
        )
    }.onFailure {
        LOGGER.error(
            "Exception when writing fragment file. msgContact=$msgContact. fileOutputPath=$fileOutputPath : ",
            it
        )
    }

    private fun writeFragmentFile(
        htmlHeadTemplate: String,
        globalStyleSheet: String,
        styleClassNameMap: ConcurrentHashMap<String, String>,
        dateForHtmlHead: Date,
        lineDeque: ConcurrentLinkedDeque<String>,
        msgContact: String
    ) {
        LOGGER.info("Going to write to $fileOutputPath")
        val sanitizedFilenamePrefix = sanitizeFilename(msgContact)
        if (msgContact != sanitizedFilenamePrefix) {
            LOGGER.warn("Msg contact $msgContact has been sanitized to: $sanitizedFilenamePrefix ")
        }
        filenameCounter.putIfAbsent(sanitizedFilenamePrefix, 0)
        filenameCounter[sanitizedFilenamePrefix] = filenameCounter[sanitizedFilenamePrefix]!! + 1
        val fileCount = filenameCounter[sanitizedFilenamePrefix]
        val fragmentFileName =
            sanitizedFilenamePrefix + "_${"%03d".format(filenameCounter[sanitizedFilenamePrefix]!!)}.html"
        val fragmentFile = File(fileOutputPath).resolve(fragmentFileName)
        if (fragmentFile.exists()) {
            LOGGER.warn("$fragmentFileName exists! Overwriting")
        }
        fragmentFile.createNewFile()
        LOGGER.info("New file ${fragmentFile.absolutePath} created. Writing...")
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
        LOGGER.info("Successfully wrote to ${fragmentFile.absolutePath} .")
    }

    private fun getImgFileNameExtensionMap(): Map<String, String> {
        val m = imgOutputFolder.listFiles()?.groupBy({ it.nameWithoutExtension }, { it.extension }) ?: emptyMap()
        val result = HashMap<String, String>()
        for (e in m.entries) {
            result[e.key] = e.value[0]
        }
        return result
    }

    data class ExtractFirstLineResult(
        val remainOfFirstLine: String,
        val htmlHeadTemplate: String,
        val globalStyleSheet: String,
        val date: Date,
        val dateLine: String
    )

    private fun handleFirstLine(
        rawFirstLine: String,
        styleClassNameMap: MutableMap<String, String>,
        imgFileNameExtensionMap: Map<String, String>,
    ): ExtractFirstLineResult {
        val (firstLine, _) = extractLineAndReplaceStyle(
            rawFirstLine,
            styleClassNameMap,
            Date(),
            imgFileNameExtensionMap,
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
        val msgObj = MSG_CONTACT_REGEX.find(htmlHeadTemplate)!!.groupValues[1]
        htmlHeadTemplate = htmlHeadTemplate.replace(msgObj, MSG_CONTACT_PLACEHOLDER)

        return ExtractFirstLineResult(
            firstLine.substring(endOfDateCellPlusOne),
            htmlHeadTemplate,
            globalStyleSheet,
            startDate,
            dateLineWithPlaceHolder
        )
    }

    private fun extractDateFromLine(firstLine: String): Pair<String, Date> {
        val yyyyMmDd = DATE_REGEX.find(firstLine)!!.groupValues[1]
        return Pair(yyyyMmDd, YYYY_MM_DD_DATE_FORMATTER_UTC.parse(yyyyMmDd))
    }

    private fun extractLineAndReplaceStyle(
        line: String,
        styleClassNameMap: MutableMap<String, String>,
        date: Date,
        imgFileNameExtensionMap: Map<String, String>,
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

    private val writtenFilenameSet = ConcurrentHashMap.newKeySet<String>()
    private fun CoroutineScope.launchConsumer(
        producerRank: Int,
        consumerRank: Int,
        latch: CountDownLatch,
        channel: ReceiveChannel<Triple<Long, Long, String>>
    ) = launch {
        val lRaf = RandomAccessFile(fileLocation, "r")
        LOGGER.info("[P${producerRank + 1}:C${consumerRank + 1}] Consumer launched.")
        for (msg in channel) {
            val beginOffsetOfB64 = msg.first
            val endOffsetOfB64 = msg.second
            val uuid = msg.third
            if (writtenFilenameSet.contains(uuid)) {
                LOGGER.error("$uuid already written!")
            }
            val b64Len = endOffsetOfB64 - beginOffsetOfB64
            val ba = ByteArray(b64Len.toInt())
            lRaf.seek(beginOffsetOfB64)
            lRaf.read(ba)
            val decode = Base64.decodeBase64(ba)
            val fileExt = kotlin.runCatching { Imaging.guessFormat(decode).defaultExtension }
                .onFailure {
                    LOGGER.error(
                        "Exception occurs when guessing image format: uuid=$uuid," +
                                " beginOffset=$beginOffsetOfB64, endOffset=$endOffsetOfB64"
                    )
                    LOGGER.error("Ex: ", it)
                }.getOrDefault("DAT")

            FileOutputStream(imgOutputFolder.resolve("$uuid.$fileExt")).use { fos ->
                BufferedOutputStream(fos).use { bfos ->
                    bfos.write(decode)
                    bfos.flush()
                }
            }
            writtenFilenameSet.add(uuid)
        }
        LOGGER.info("[P${producerRank + 1}:C${consumerRank + 1}] Consumer shutting down...")
        latch.countDown()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun CoroutineScope.produceOffSet(
        rank: Int,
        totalProducerCount: Int,
        initOffset: Long,
        thresholdOffset: Long
    ) =
        produce {
            val raf = RandomAccessFile(fileLocation, "r")
            var nextOffset: Long
            val sunday = Sunday(raf, initOffset, boundaryText.toByteArray())
            var previousOffset = -1L
            var shouldBreak = false
            var counter = 0
            LOGGER.info("[P${rank + 1}/$totalProducerCount] Running Sunday algorithm to locate boundary")
            while (sunday.getNextOffSet().also { nextOffset = it } > 0L) {
                if (shouldBreak) break
                if (counter == 0 && nextOffset >= thresholdOffset) {
                    break
                }
                shouldBreak = nextOffset >= thresholdOffset
                counter++
                updateProgress(nextOffset - initOffset, raf.length() / totalProducerCount)
                if ((rank == 0 && counter < 3) || counter < 2) {
                    leadingOffsetSet.add(nextOffset)
                    previousOffset = nextOffset
                    continue
                }

                var line: String
                var uuid = "INVALID"
                raf.seek(previousOffset)
                while (raf.readLineInUtf8().also { line = it } != null) {
                    if (line.contains("Content-Location")) {
                        uuid = line.substring("Content-Location:{".length, line.indexOf("}.dat"))
                        LOGGER.debug(uuid)
                        raf.readLine()
                        break
                    }
                }
                val beginOffsetOfB64 = raf.filePointer
                val endOffsetOfB64 = nextOffset
                previousOffset = nextOffset
                if (noImage) break
                send(Triple(beginOffsetOfB64, endOffsetOfB64, uuid))
                if (leadingOffsetSet.contains(nextOffset)) {
                    break
                }
            }
            LOGGER.info("[P${rank+1}/$totalProducerCount] Sunday algorithm ended.")
            this.channel.close()
        }

    private fun updateProgress(relativeOffset: Long, chunkSize: Long) =
        CoroutineScope(MainUIDispatcher).launch {
            if (progress == null) return@launch
            progress.value = max(progress.value, relativeOffset.toFloat() / chunkSize.toFloat()).coerceAtMost(1F)
        }
}
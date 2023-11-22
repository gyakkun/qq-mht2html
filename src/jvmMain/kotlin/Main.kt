import LoggingHelper.LOGGER
import Mht2Html.Constants.DefaultConfig.DEFAULT_LINE_LIMIT
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skiko.MainUIDispatcher
import org.slf4j.LoggerFactory
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

object LoggingHelper {
    val LOGGER = LoggerFactory.getLogger(LoggingHelper.javaClass)
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "QQ MHT2HTML",
        icon = painterResource("/drawables/qq-mht2html.png"),
    ) {
        val windowScope = this
        // App()
        MaterialTheme {
            App(windowScope)
        }
    }
}


@Composable
private fun App(windowScope: FrameWindowScope) {
    var threadCountStr by remember { mutableStateOf(Runtime.getRuntime().availableProcessors().toString()) }
    var lineLimitStr by remember { mutableStateOf("$DEFAULT_LINE_LIMIT") }
    var mhtFileLocation by remember { mutableStateOf("C:\\Windows\\notepad.exe") }
    var fileOutputLocation by remember { mutableStateOf("C:\\") }
    var imgFileOutputFolder by remember { mutableStateOf("img") }
    val errMsg = remember { mutableStateOf("Error message.") }
    val showAlert = remember { mutableStateOf(false) }
    val progress = remember { mutableStateOf(0.0F) }
    val noImage = remember { mutableStateOf(false) }
    val noHtml = remember { mutableStateOf(false) }

    Surface {
        if (showAlert.value) {
            TopAppBar(backgroundColor = Color.Red, modifier = Modifier.height(40.dp)) {
                Text(errMsg.value, color = Color.White)
            }
        }
        Surface(modifier = Modifier.padding(50.dp)) {

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                Row {
                    TextField(
                        value = mhtFileLocation,
                        onValueChange = { mhtFileLocation = it },
                        label = { Text("MHT File Location") },
                        modifier = Modifier.height(60.dp)
                            .weight(0.5F, false)
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Button(onClick = {
                        val fileChooser = JFileChooser()
                        fileChooser.isMultiSelectionEnabled = false
                        fileChooser.fileSelectionMode = JFileChooser.FILES_ONLY
                        fileChooser.fileFilter = FileNameExtensionFilter("MHT/MHTML File", "mht", "mhtm", "mhtml")
                        val option: Int = fileChooser.showOpenDialog(windowScope.window)
                        if (option == JFileChooser.APPROVE_OPTION) {
                            val file = fileChooser.selectedFile
                            mhtFileLocation = file.absolutePath
                        }
                    }, content = { Text("Choose File") })
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row {
                    TextField(
                        value = fileOutputLocation,
                        onValueChange = { fileOutputLocation = it },
                        label = { Text("HTML File Output Location") },
                        modifier = Modifier.height(60.dp)
                            .weight(0.5F, false)
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Button(onClick = {
                        val fileChooser = JFileChooser()
                        fileChooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        val option: Int = fileChooser.showOpenDialog(windowScope.window)
                        if (option == JFileChooser.APPROVE_OPTION) {
                            val file = fileChooser.selectedFile
                            fileOutputLocation = file.absolutePath
                        }
                    }, content = { Text("Choose Folder") })
                }
                Spacer(modifier = Modifier.size(10.dp))
                Row(modifier = Modifier.align(Alignment.Start)) {
                    TextField(
                        value = imgFileOutputFolder,
                        onValueChange = { imgFileOutputFolder = it },
                        label = { Text("Image File Output Folder") },
                        modifier = Modifier.height(60.dp)
                            .weight(0.5F, false)
                    )
                }
                Spacer(modifier = Modifier.size(10.dp))
                Row(modifier = Modifier.align(Alignment.Start)) {
                    TextField(
                        value = threadCountStr,
                        onValueChange = {
                            threadCountStr = it
                        },
                        label = { Text("Thread Count") },
                        modifier = Modifier.height(60.dp)
                            .weight(0.5F, false),
                        placeholder = {
                            Text(Runtime.getRuntime().availableProcessors().toString())
                        }
                    )
                }
                Spacer(modifier = Modifier.size(10.dp))
                Row(modifier = Modifier.align(Alignment.Start)) {
                    TextField(
                        value = lineLimitStr,
                        onValueChange = {
                            lineLimitStr = it
                        },
                        label = { Text("Paging Line Limit") },
                        modifier = Modifier.height(60.dp)
                            .weight(0.5F, false),
                        placeholder = {
                            Text("${DEFAULT_LINE_LIMIT}")
                        }
                    )
                }
                Spacer(modifier = Modifier.size(10.dp))
                Row(modifier = Modifier.align(Alignment.Start)) {
                    Text("No Image")
                    Checkbox(
                        checked = noImage.value,
                        onCheckedChange = {
                            noImage.value = it
                        },
                        modifier = Modifier.height(10.dp)
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text("No Html")
                    Checkbox(
                        checked = noHtml.value,
                        onCheckedChange = {
                            noHtml.value = it
                        },
                        modifier = Modifier.height(10.dp)
                    )
                }
                Spacer(modifier = Modifier.size(10.dp))
                Row(modifier = Modifier.align(Alignment.Start)) {
                    Button(
                        enabled = progress.value < 0.0001F || progress.value > 0.9999F,
                        onClick = {
                            CoroutineScope(MainUIDispatcher).launch {

                                val mhtFile = File(mhtFileLocation)
                                if (!mhtFile.exists() || mhtFile.isDirectory) {
                                    val tmpErrMsg = "Not a valid mht file location!"
                                    showInfoBar(showAlert, errMsg, tmpErrMsg, -1L)
                                    return@launch
                                }

                                val fileOutputDirFile = File(fileOutputLocation)

                                if (fileOutputDirFile.exists() && !fileOutputDirFile.isDirectory) {
                                    val tmpErrMsg = "Output dir exists and is not a folder!"
                                    showInfoBar(showAlert, errMsg, tmpErrMsg, -1L)
                                    return@launch
                                }

                                val imgOutputFolder = fileOutputDirFile.resolve(imgFileOutputFolder)
                                if (imgOutputFolder.exists() && !imgOutputFolder.isDirectory) {
                                    val tmpErrMsg = "Img output dir exists and is not a folder!"
                                    showInfoBar(showAlert, errMsg, tmpErrMsg, -1L)
                                    return@launch
                                }

                                var threadCount = Runtime.getRuntime().availableProcessors()
                                runCatching {
                                    threadCount = Integer.parseInt(threadCountStr)
                                    if (threadCount <= 0) threadCount = 1
                                }.onFailure {
                                    LOGGER.info("Thread count invalid. Using core number.")
                                }

                                var lineLimit = DEFAULT_LINE_LIMIT
                                kotlin.runCatching {
                                    lineLimit = Integer.parseInt(lineLimitStr)
                                    if (lineLimit <= 500) lineLimit = 500
                                }.onFailure {
                                    LOGGER.info("Thread count invalid. Using default 7500.")
                                }

                                Mht2Html(
                                    showAlert, errMsg, progress,
                                    mhtFileLocation,
                                    fileOutputLocation,
                                    imgOutputFolder.absolutePath,
                                    threadCount,
                                    lineLimit,
                                    noHtml.value,
                                    noImage.value
                                ).doJob()
                            }
                        }) {
                        Text("START")
                    }
                }
                Spacer(modifier = Modifier.size(10.dp))
                Row {
                    LinearProgressIndicator(
                        progress = progress.value,
                        modifier = Modifier
                            .weight(0.1F)
                            .height(15.dp),
                        backgroundColor = Color.LightGray,
                        color = Color.Red //progress color
                    )
                    Text(if (progress.value > 0.001F) "%.2f".format(progress.value * 100F) + "%" else "     ")
                }
            }
        }
    }

}

suspend fun showInfoBar(
    showAlert: MutableState<Boolean>?,
    errMsg: MutableState<String>?,
    msg: String,
    delayMs: Long = 1_000L
) = CoroutineScope(MainUIDispatcher).launch {
    showAlert?.value = true
    errMsg?.value = msg
    LOGGER.info(msg)
    if (delayMs < 0) return@launch
    delay(delayMs)
    showAlert?.value = false
    errMsg?.value = ""
}


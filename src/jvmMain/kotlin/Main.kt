// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
@file:OptIn(ExperimentalMaterialApi::class)

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "QQ MHT2HTML") {
        val windowScope = this
        // App()
        MaterialTheme {
            App(windowScope)
        }
    }
}


@OptIn(ExperimentalMaterialApi::class, ExperimentalCoroutinesApi::class)
@Composable
private fun App(windowScope: FrameWindowScope) {
    var showDialog by remember { mutableStateOf(false) }
    var threadCountStr by remember { mutableStateOf(Runtime.getRuntime().availableProcessors().toString()) }
    var mhtFileLocation by remember { mutableStateOf("E:\\[ToBak]\\Desktop_Win10\\咕咕瓜的避难窝.mht") }
    var fileOutputLocation by remember { mutableStateOf("U:\\") }
    var imgFileOutputFolder by remember { mutableStateOf("img") }
    var errMsg = remember { mutableStateOf("Error message.") }
    var showAlert = remember { mutableStateOf(false) }
    var progress = remember { mutableStateOf(0.0F) }


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
                Spacer(modifier = Modifier.height(30.dp))
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
                Button(
                    modifier = Modifier.padding(vertical = 24.dp),
                    enabled = progress.value < 0.001F || progress.value > 0.99F,
                    onClick = {
                        GlobalScope.launch {
                            var tc = Runtime.getRuntime().availableProcessors()
                            runCatching { Integer.parseInt(threadCountStr) }.onFailure {
                                System.err.println("Thread count invalid. Using core number.")
                            }
                            Mht2Html.doJob(
                                mhtFileLocation,
                                fileOutputLocation,
                                File(fileOutputLocation).resolve(imgFileOutputFolder).absolutePath,
                                tc,
                                showAlert,
                                errMsg,
                                progress
                            )
                        }
                    }) {
                    Text("START")
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

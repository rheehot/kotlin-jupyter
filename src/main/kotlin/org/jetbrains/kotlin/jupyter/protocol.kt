package org.jetbrains.kotlin.jupyter

import com.beust.klaxon.JsonObject
import jupyter.kotlin.KotlinKernelVersion.Companion.toMaybeUnspecifiedString
import jupyter.kotlin.MimeTypedResult
import jupyter.kotlin.textResult
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.timer
import kotlin.system.exitProcess

enum class ResponseState {
    Ok, Error, Abort
}

enum class JupyterOutType {
    STDOUT, STDERR;
    fun optionName() = name.toLowerCase()
}

abstract class Response(
    private val stdOut: String?,
    private val stdErr: String?
) {
    abstract val state: ResponseState

    fun send(socket: JupyterConnection.Socket, requestCount: Long, requestMsg: Message, startedTime: String) {
        if (stdOut != null && stdOut.isNotEmpty()) {
            socket.connection.iopub.sendOut(requestMsg, JupyterOutType.STDOUT, stdOut)
        }
        if (stdErr != null && stdErr.isNotEmpty()) {
            socket.connection.iopub.sendOut(requestMsg, JupyterOutType.STDERR, stdErr)
        }
        sendBody(socket, requestCount, requestMsg, startedTime)
    }

    protected abstract fun sendBody(socket: JupyterConnection.Socket, requestCount: Long, requestMsg: Message, startedTime: String)
}

class OkResponseWithMessage(
        private val result: MimeTypedResult?,
        private val newClasspath: Classpath = emptyList(),
        stdOut: String? = null,
        stdErr: String? = null,
): Response(stdOut, stdErr){
    override val state: ResponseState = ResponseState.Ok

    override fun sendBody(socket: JupyterConnection.Socket, requestCount: Long, requestMsg: Message, startedTime: String) {
        if (result != null) {
            val metadata = jsonObject()
            if (result.isolatedHtml) metadata["text/html"] = jsonObject("isolated" to true)
            metadata["new_classpath"] = newClasspath

            socket.connection.iopub.send(makeReplyMessage(requestMsg,
                "execute_result",
                content = jsonObject(
                    "execution_count" to requestCount,
                    "data" to result,
                    "metadata" to metadata
                )))
        }

        socket.send(makeReplyMessage(requestMsg, "execute_reply",
            metadata = jsonObject(
                "dependencies_met" to true,
                "engine" to requestMsg.header["session"],
                "status" to "ok",
                "started" to startedTime),
            content = jsonObject(
                "status" to "ok",
                "execution_count" to requestCount,
                "user_variables" to JsonObject(),
                "payload" to listOf<String>(),
                "user_expressions" to JsonObject())))
    }
}

class AbortResponseWithMessage(
        val result: MimeTypedResult?,
        stdErr: String? = null,
): Response(null, stdErr){
    override val state: ResponseState = ResponseState.Abort

    override fun sendBody(socket: JupyterConnection.Socket, requestCount: Long, requestMsg: Message, startedTime: String) {
        val errorReply = makeReplyMessage(requestMsg, "execute_reply",
            content = jsonObject(
                "status" to "abort",
                "execution_count" to requestCount))
        System.err.println("Sending abort: $errorReply")
        socket.send(errorReply)
    }
}

class ErrorResponseWithMessage(
        val result: MimeTypedResult?,
        stdErr: String? = null,
        private val errorName: String = "Unknown error",
        private var errorValue: String = "",
        private val traceback: List<String> = emptyList(),
        private val additionalInfo: JsonObject = jsonObject(),
): Response(null, stdErr){
    override val state: ResponseState = ResponseState.Error

    override fun sendBody(socket: JupyterConnection.Socket, requestCount: Long, requestMsg: Message, startedTime: String) {
        val errorReply = makeReplyMessage(requestMsg, "execute_reply",
            content = jsonObject(
                "status" to "error",
                "execution_count" to requestCount,
                "ename" to errorName,
                "evalue" to errorValue,
                "traceback" to traceback,
                "additionalInfo" to additionalInfo))
        System.err.println("Sending error: $errorReply")
        socket.send(errorReply)
    }
}

fun JupyterConnection.Socket.controlMessagesHandler(msg: Message, repl: ReplForJupyter?) {
    when(msg.header!!["msg_type"]) {
        "interrupt_request" -> {
            log.warn("Interruption is not yet supported!")
            send(makeReplyMessage(msg, "interrupt_reply", content = msg.content))
        }
        "shutdown_request" -> {
            repl?.evalOnShutdown()
            send(makeReplyMessage(msg, "shutdown_reply", content = msg.content))
            exitProcess(0)
        }
    }
}

fun JupyterConnection.Socket.shellMessagesHandler(msg: Message, repl: ReplForJupyter, executionCount: AtomicLong) {
    when (msg.header!!["msg_type"]) {
        "kernel_info_request" ->
            sendWrapped(msg, makeReplyMessage(msg, "kernel_info_reply",
                    content = jsonObject(
                            "protocol_version" to protocolVersion,
                            "language" to "Kotlin",
                            "language_version" to KotlinCompilerVersion.VERSION,
                            "language_info" to jsonObject(
                                    "name" to "kotlin",
                                    "codemirror_mode" to "text/x-kotlin",
                                    "file_extension" to ".kt",
                                    "mimetype" to "text/x-kotlin",
                                    "pygments_lexer" to "kotlin",
                                    "version" to KotlinCompilerVersion.VERSION
                            ),

                            // Jupyter lab Console support
                            "banner" to "Kotlin kernel v. ${repl.runtimeProperties.version.toMaybeUnspecifiedString()}, Kotlin v. ${KotlinCompilerVersion.VERSION}",
                            "implementation" to "Kotlin",
                            "implementation_version" to repl.runtimeProperties.version.toMaybeUnspecifiedString(),
                            "status" to "ok"
                    )))
        "history_request" ->
            sendWrapped(msg, makeReplyMessage(msg, "history_reply",
                    content = jsonObject(
                            "history" to listOf<String>() // not implemented
                    )))

        // TODO: This request is deprecated since messaging protocol v.5.1,
        // remove it in future versions of kernel
        "connect_request" ->
            sendWrapped(msg, makeReplyMessage(msg, "connect_reply",
                    content = jsonObject(JupyterSockets.values()
                            .map { Pair("${it.name}_port", connection.config.ports[it.ordinal]) })))
        "execute_request" -> {
            connection.contextMessage = msg
            val count = executionCount.getAndIncrement()
            val startedTime = ISO8601DateNow

            fun displayHandler(value: Any) {
                val res = value.toMimeTypedResult()
                connection.iopub.send(makeReplyMessage(msg,
                        "display_data",
                        content = jsonObject(
                                "data" to res,
                                "metadata" to jsonObject()
                        )))
            }

            connection.iopub.sendStatus("busy", msg)
            val code = msg.content["code"]
            connection.iopub.send(makeReplyMessage(msg, "execute_input", content = jsonObject(
                    "execution_count" to count,
                    "code" to code)))
            val res: Response = if (isCommand(code.toString())) {
                runCommand(code.toString(), repl)
            } else {
                connection.evalWithIO(repl.outputConfig, msg) {
                    repl.eval(code.toString(), ::displayHandler, count.toInt())
                }
            }

            res.send(this, count, msg, startedTime)

            connection.iopub.sendStatus("idle", msg)
            connection.contextMessage = null
        }
        "comm_info_request" -> {
            sendWrapped(msg, makeReplyMessage(msg, "comm_info_reply",  content = jsonObject("comms" to jsonObject())))
        }
        "complete_request" -> {
            val code = msg.content["code"].toString()
            val cursor = msg.content["cursor_pos"] as Int
            GlobalScope.launch {
                repl.complete(code, cursor) { result ->
                    sendWrapped(msg, makeReplyMessage(msg, "complete_reply", content = result.toJson()))
                }
            }
        }
        "list_errors_request" -> {
            val code = msg.content["code"].toString()
            GlobalScope.launch {
                repl.listErrors(code) { result ->
                    sendWrapped(msg, makeReplyMessage(msg, "list_errors_reply", content = result.toJson()))
                }
            }
        }
        "is_complete_request" -> {
            val code = msg.content["code"].toString()
            val resStr = if (isCommand(code)) "complete" else {
                val result = try {
                    val check = repl.checkComplete(code)
                    when {
                        check.isComplete -> "complete"
                        else -> "incomplete"
                    }
                } catch (ex: ReplCompilerException) {
                    "invalid"
                }
                result
            }
            sendWrapped(msg, makeReplyMessage(msg, "is_complete_reply", content = jsonObject("status" to resStr)))
        }
        else -> send(makeReplyMessage(msg, "unsupported_message_reply"))
    }
}

class CapturingOutputStream(
        private val stdout: PrintStream,
        private val conf: OutputConfig,
        private val captureOutput: Boolean,
        val onCaptured: (String) -> Unit,
) : OutputStream() {
    private val capturedLines = ByteArrayOutputStream()
    private val capturedNewLine = ByteArrayOutputStream()
    private var overallOutputSize = 0
    private var newlineFound = false

    private val timer = timer(
            initialDelay = conf.captureBufferTimeLimitMs,
            period = conf.captureBufferTimeLimitMs,
            action = {
                flush()
            })

    val contents: ByteArray
        @TestOnly
        get() = capturedLines.toByteArray() + capturedNewLine.toByteArray()

    private fun flushIfNeeded(b: Int) {
        val c = b.toChar()
        if (c == '\n') {
            newlineFound = true
            capturedNewLine.writeTo(capturedLines)
            capturedNewLine.reset()
        }

        val size = capturedLines.size() + capturedNewLine.size()

        if (newlineFound && size >= conf.captureNewlineBufferSize)
            return flushBuffers(capturedLines)
        if (size >= conf.captureBufferMaxSize)
            return flush()
    }

    @Synchronized
    override fun write(b: Int) {
        ++overallOutputSize
        stdout.write(b)

        if (captureOutput && overallOutputSize <= conf.cellOutputMaxSize) {
            capturedNewLine.write(b)
            flushIfNeeded(b)
        }
    }

    @Synchronized
    private fun flushBuffers(vararg buffers: ByteArrayOutputStream) {
        newlineFound = false
        val str = buffers.map { stream ->
            val str = stream.toString("UTF-8")
            stream.reset()
            str
        }.reduce { acc, s -> acc + s }
        if (str.isNotEmpty()) {
            onCaptured(str)
        }
    }

    override fun flush() {
        flushBuffers(capturedLines, capturedNewLine)
    }

    override fun close() {
        super.close()
        timer.cancel()
    }
}

fun Any.toMimeTypedResult(): MimeTypedResult? = when (this) {
    is MimeTypedResult -> this
    is Unit -> null
    else -> textResult(this.toString())
}

fun JupyterConnection.evalWithIO(config: OutputConfig, srcMessage: Message, body: () -> EvalResult?): Response {
    val out = System.out
    val err = System.err

    fun getCapturingStream(stream: PrintStream, outType: JupyterOutType, captureOutput: Boolean): CapturingOutputStream {
        return CapturingOutputStream(
                stream,
                config,
                captureOutput) { text ->
            this.iopub.sendOut(srcMessage, outType, text)
        }
    }

    val forkedOut = getCapturingStream(out, JupyterOutType.STDOUT, config.captureOutput)
    val forkedError = getCapturingStream(err, JupyterOutType.STDERR, false)

    System.setOut(PrintStream(forkedOut, false, "UTF-8"))
    System.setErr(PrintStream(forkedError, false, "UTF-8"))

    val `in` = System.`in`
    System.setIn(stdinIn)
    try {
        return try {
            val exec = body()
            if (exec == null) {
                AbortResponseWithMessage(textResult("Error!"), "NO REPL!")
            } else {
                forkedOut.flush()
                forkedError.flush()

                try {
                    val result = exec.resultValue?.toMimeTypedResult()
                    OkResponseWithMessage(result, exec.newClasspath)
                } catch (e: Exception) {
                    AbortResponseWithMessage(textResult("Error!"), "error:  Unable to convert result to a string: $e")
                }
            }
        } catch (ex: ReplCompilerException) {
            forkedOut.flush()
            forkedError.flush()

            val firstDiagnostic = ex.firstDiagnostics
            val additionalInfo = firstDiagnostic?.location?.let {
                val errorMessage = firstDiagnostic.message
                jsonObject("lineStart" to it.start.line, "colStart" to it.start.col,
                        "lineEnd" to (it.end?.line ?: -1), "colEnd" to (it.end?.col ?: -1),
                        "message" to errorMessage,
                        "path" to firstDiagnostic.sourcePath.orEmpty())
            } ?: jsonObject()

            ErrorResponseWithMessage(
                    textResult("Error!"),
                    ex.message,
                    ex.javaClass.canonicalName,
                    ex.message ?: "",
                    ex.stackTrace.map { it.toString() },
                    additionalInfo)
        } catch (ex: ReplEvalRuntimeException) {
            forkedOut.flush()

            val stdErr = StringBuilder()
            with(stdErr) {
                val cause = ex.cause
                if (cause == null) appendLine(ex.message)
                else {
                    when (cause) {
                        is InvocationTargetException -> appendLine(cause.targetException.toString())
                        else -> appendLine(cause.toString())
                    }
                    cause.stackTrace?.also {
                        for (s in it)
                            appendLine(s)
                    }
                }
            }
            ErrorResponseWithMessage(
                    textResult("Error!"),
                    stdErr.toString(),
                    ex.javaClass.canonicalName,
                    ex.message ?: "",
                    ex.stackTrace.map { it.toString() })
        }
    } finally {
        forkedOut.close()
        forkedError.close()
        System.setIn(`in`)
        System.setErr(err)
        System.setOut(out)
    }
}

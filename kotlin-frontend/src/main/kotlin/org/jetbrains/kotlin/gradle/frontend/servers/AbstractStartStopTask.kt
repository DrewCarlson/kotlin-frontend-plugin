package org.jetbrains.kotlin.gradle.frontend.servers

import net.rubygrapefruit.platform.*
import org.gradle.api.*
import org.gradle.api.tasks.*
import java.io.*
import java.util.concurrent.*

/**
 * @author Sergey Mashkov
 */
abstract class AbstractStartStopTask<S : Any> : DefaultTask() {
    @get:Internal
    protected abstract val identifier: String
    protected abstract fun checkIsRunning(stopInfo: S?): Boolean

    protected abstract fun builder(): ProcessBuilder
    protected abstract fun beforeStart(): S?
    protected abstract fun stop(state: S?)

    protected open fun needRestart(oldState: S?, newState: S?) = oldState != newState

    protected abstract fun readState(file: File): S
    protected abstract fun writeState(file: File, state: S)

    protected open fun notRunningThenKilledMessage(): Unit = logger.error("$identifier: not actually running so has been killed")
    protected open fun notRunningExitCodeMessage(exitCode: Int): Unit = logger.error("$identifier: exited with exit code $exitCode")

    protected open fun alreadyRunningMessage(): Unit = logger.warn("$identifier is already running")
    protected open fun needRestartMessage(): Unit = logger.warn("$identifier needs restart")
    protected open fun startedMessage(): Unit = logger.warn("$identifier started")
    protected open fun stoppedMessage(): Unit = logger.warn("$identifier stopped")

    protected open fun serverLog(): File = project.buildDir.resolve("$identifier.log")

    protected open val stateFile: File
        get() = project.buildDir.resolve(".run-$identifier.txt")

    protected fun doStart() {
        val oldStopInfo = tryReadState(stateFile)
        val newState = beforeStart()

        if (checkIsRunning(oldStopInfo)) {
            if (needRestart(oldStopInfo, newState)) {
                needRestartMessage()
                doStop()
            } else {
                alreadyRunningMessage()
                return
            }
        }

        stateFile.delete()
        serverLog().delete()

        val launcher = Native.get(ProcessLauncher::class.java)!!
        val builder = builder()
                .redirectErrorStream(true)
                .redirectOutput(serverLog())

        if (logger.isDebugEnabled) {
            logger.debug("Running ${builder.command().joinToString(" ")}")
        }

        val process = launcher.start(builder)

        for (i in 1..10) {
            if (checkIsRunning(newState)) {
                break
            }
            if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                break
            }
        }

        if (!checkIsRunning(newState)) {
            if (process.isAlive) {
                process.destroyForcibly()
                notRunningThenKilledMessage()
            } else {
                notRunningExitCodeMessage(process.exitValue())
            }

            throw GradleException("$identifier startup failed")
        }

        if (newState != null) {
            writeState(stateFile, newState)
        }

        startedMessage()
    }

    protected fun doStop() {
        val state = tryReadState(stateFile)

        if (!checkIsRunning(state)) {
            logger.info("$identifier: Not running")
            return
        }

        for (i in 1..10) {
            stop(state)
            if (!checkIsRunning(state)) {
                break
            }
            Thread.sleep(500)
        }

        if (checkIsRunning(state)) {
            logger.error("Failed to stop $identifier: still running")
        } else {
            stoppedMessage()
            stateFile.delete()
        }
    }

    private fun tryReadState(stateFile: File): S? = try {
        if (stateFile.canRead()) readState(stateFile) else null
    } catch (e: IOException) {
        null
    }
}

package ru.e327.dusterscanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class Elm327(private val adapter: BluetoothAdapter) {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** true, если автомобиль работает по CAN (ISO 15765) — влияет на разбор Mode 03 */
    var isCan: Boolean = false
        private set

    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!adapter.isEnabled) error("Bluetooth выключен")
        close()
        try { adapter.cancelDiscovery() } catch (_: Exception) {}

        var s: BluetoothSocket? = null
        try {
            s = device.createRfcommSocketToServiceRecord(uuid)
            s.connect()
        } catch (first: Exception) {
            // Многие дешёвые клоны ELM327 не отвечают на стандартный UUID — пробуем RFCOMM-канал 1 напрямую
            try { s?.close() } catch (_: Exception) {}
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                s = m.invoke(device, 1) as BluetoothSocket
                s.connect()
            } catch (second: Exception) {
                try { s?.close() } catch (_: Exception) {}
                throw first
            }
        }
        socket = s
        input = s!!.inputStream
        output = s.outputStream
        drainInput()

        command("ATZ", 2500)
        command("ATE0", 800)
        command("ATL0", 800)
        command("ATS0", 800)
        command("ATH0", 800)
        command("ATSP0", 1000)
        // Первый запрос запускает автоопределение протокола — это может занять несколько секунд
        command("0100", 10000)
        var p = command("ATDPN", 1000).replace(">", "").trim().uppercase()
        if (p.length == 2 && p[0] == 'A') p = p.substring(1)
        isCan = p.isNotEmpty() && p[0] in "6789"
    }

    fun command(cmd: String, waitMs: Long = 1500): String {
        val out = output ?: error("ELM327 не подключён")
        val inp = input ?: error("ELM327 не подключён")

        drainInput()
        out.write((cmd.trim() + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()

        val deadline = System.currentTimeMillis() + maxOf(waitMs, 500L)
        val data = StringBuilder()
        val buf = ByteArray(256)

        while (System.currentTimeMillis() < deadline) {
            if (inp.available() > 0) {
                val n = inp.read(buf)
                if (n > 0) {
                    data.append(String(buf, 0, n, Charsets.US_ASCII))
                    if (data.contains(">")) break
                }
            } else Thread.sleep(30)
        }
        return data.toString().replace("\r", "\n").replace("\u0000", "").replace(">", "").trim()
    }

    fun readStoredCodes(): String = command("03", 6000)
    fun clearCodes(): String = command("04", 4000)
    fun ecuInfo(): String = command("0100", 8000)
    fun readPid(pid: String): String = command("01$pid", 3000)

    private fun drainInput() {
        val inp = input ?: return
        try {
            val buf = ByteArray(256)
            while (inp.available() > 0) inp.read(buf)
        } catch (_: Exception) {}
    }

    fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null; output = null; socket = null
    }
}

package ru.e327.dusterscanner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var read: Button
    private lateinit var clear: Button
    private lateinit var params: Button
    private lateinit var ecuInfo: Button
    private var elm: Elm327? = null
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        result = findViewById(R.id.result)
        val connect: Button = findViewById(R.id.connect)
        read = findViewById(R.id.readCodes)
        clear = findViewById(R.id.clearCodes)
        params = findViewById(R.id.readParams)
        ecuInfo = findViewById(R.id.ecuInfo)

        requestBtPermissionIfNeeded()

        connect.setOnClickListener { chooseAndConnect() }
        read.setOnClickListener { readCodes() }
        clear.setOnClickListener { confirmClear() }
        params.setOnClickListener { readParameters() }
        ecuInfo.setOnClickListener { checkEcu() }
    }

    private fun hasBtPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestBtPermissionIfNeeded() {
        if (!hasBtPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 10)
        }
    }

    private fun chooseAndConnect() {
        if (!hasBtPermission()) {
            status.text = "Разреши доступ к Bluetooth («Устройства поблизости») и нажми ещё раз"
            requestBtPermissionIfNeeded()
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { status.text = "Bluetooth не поддерживается"; return }
        if (!adapter.isEnabled) { status.text = "Включи Bluetooth и повтори подключение"; return }
        val paired = try { adapter.bondedDevices.toList() } catch (e: SecurityException) {
            status.text = "Нет разрешения Bluetooth"; return
        }
        if (paired.isEmpty()) {
            status.text = "Нет сопряжённых устройств. Сначала сопряги ELM327 в настройках Bluetooth телефона (PIN обычно 1234 или 0000)."
            return
        }
        val names = paired.map { "${it.name ?: "Без имени"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Выбери ELM327").setItems(names) { _, which ->
            connectDevice(adapter, paired[which])
        }.show()
    }

    private fun connectDevice(adapter: BluetoothAdapter, device: BluetoothDevice) {
        status.text = "Подключение (до 20 с)..."
        executor.execute {
            try {
                elm?.close()
                val e = Elm327(adapter)
                e.connect(device)
                elm = e
                runOnUiThread {
                    status.text = "ELM327 подключён" + if (e.isCan) " · CAN" else ""
                    read.isEnabled = true; clear.isEnabled = true
                    params.isEnabled = true; ecuInfo.isEnabled = true
                    result.text = "Связь с адаптером установлена. Для ответа от авто включи зажигание."
                }
            } catch (ex: Exception) {
                runOnUiThread { status.text = "Ошибка подключения: ${ex.message ?: "неизвестная ошибка"}" }
            }
        }
    }

    private fun readCodes() {
        result.text = "Считываю ошибки..."
        executor.execute {
            try {
                val e = elm ?: error("ELM327 не подключён")
                val raw = e.readStoredCodes()
                val codes = parseCodes(raw, e.isCan)
                val text = when {
                    codes.isNotEmpty() -> codes.joinToString("\n\n") { "$it: ${DtcDecoder.description(it)}" }
                    bytesOf(raw).isNotEmpty() || raw.contains("NO DATA", true) -> "Ошибок не найдено."
                    else -> "Блок не ответил. Проверь зажигание.\n\nОтвет ELM327:\n$raw"
                }
                runOnUiThread { result.text = text }
            } catch (ex: Exception) { runOnUiThread { result.text = "Ошибка чтения: ${ex.message}" } }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Очистить ошибки?")
            .setMessage("Коды неисправностей будут стёрты, мониторы готовности сброшены.")
            .setPositiveButton("Очистить") { _, _ -> clearCodes() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun clearCodes() {
        result.text = "Отправляю команду очистки..."
        executor.execute {
            try {
                val raw = elm?.clearCodes() ?: error("ELM327 не подключён")
                runOnUiThread { result.text = "Команда очистки отправлена. Ответ:\n$raw" }
            } catch (ex: Exception) { runOnUiThread { result.text = "Ошибка: ${ex.message}" } }
        }
    }

    private fun checkEcu() {
        result.text = "Проверяю связь с ЭБУ..."
        executor.execute {
            try {
                val raw = elm?.ecuInfo() ?: error("ELM327 не подключён")
                runOnUiThread { result.text = "Ответ ЭБУ (PID 0100):\n$raw" }
            } catch (ex: Exception) { runOnUiThread { result.text = "Ошибка связи с ЭБУ: ${ex.message}" } }
        }
    }

    private fun readParameters() {
        result.text = "Считываю параметры двигателя..."
        executor.execute {
            try {
                val e = elm ?: error("ELM327 не подключён")
                val rpm = pidBytes(e.readPid("0C"), 0x0C)
                val coolant = pidBytes(e.readPid("05"), 0x05)
                val map = pidBytes(e.readPid("0B"), 0x0B)
                val speed = pidBytes(e.readPid("0D"), 0x0D)
                val volt = pidBytes(e.readPid("42"), 0x42)
                val none = "нет ответа"
                val text = "ПАРАМЕТРЫ ДВИГАТЕЛЯ\n\n" +
                    "Обороты: " + (if (rpm.size >= 2) "${(rpm[0] * 256 + rpm[1]) / 4} об/мин" else none) + "\n" +
                    "Температура ОЖ: " + (if (coolant.isNotEmpty()) "${coolant[0] - 40} °C" else none) + "\n" +
                    "MAP: " + (if (map.isNotEmpty()) "${map[0]} кПа" else none) + "\n" +
                    "Скорость: " + (if (speed.isNotEmpty()) "${speed[0]} км/ч" else none) + "\n" +
                    "Напряжение ЭБУ: " + (if (volt.size >= 2) String.format("%.2f В", (volt[0] * 256 + volt[1]) / 1000.0) else none)
                runOnUiThread { result.text = text }
            } catch (ex: Exception) { runOnUiThread { result.text = "Ошибка чтения параметров: ${ex.message}" } }
        }
    }

    /** Байты ответа: берём только токены ровно из двух hex-символов */
    private fun bytesOf(raw: String): List<Int> =
        raw.uppercase().split(Regex("\\s+")).filter { it.matches(Regex("[0-9A-F]{2}")) }.map { it.toInt(16) }

    /** Данные после «41 <pid>» */
    private fun pidBytes(raw: String, pid: Int): List<Int> {
        val b = bytesOf(raw)
        for (i in 0 until b.size - 1) {
            if (b[i] == 0x41 && b[i + 1] == pid) return b.drop(i + 2).take(4)
        }
        return emptyList()
    }

    private fun parseCodes(raw: String, can: Boolean): List<String> {
        // Склеиваем мультифреймовые ответы CAN («0: ...», «1: ...»)
        val msgs = mutableListOf<String>()
        for (line in raw.uppercase().split("\n").map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = Regex("^([0-9A-F]):\\s*(.*)$").find(line)
            if (m != null) {
                if (m.groupValues[1] == "0") msgs += m.groupValues[2]
                else if (msgs.isNotEmpty()) msgs[msgs.lastIndex] = msgs.last() + " " + m.groupValues[2]
            } else if (!Regex("^[0-9A-F]{3}$").matches(line)) msgs += line
        }
        val out = linkedSetOf<String>()
        for (msg in msgs) {
            val b = bytesOf(msg)
            val i = b.indexOf(0x43)
            if (i < 0) continue
            var d = b.drop(i + 1)
            if (can) d = d.drop(1) // в CAN после 43 идёт байт «количество кодов»
            var k = 0
            while (k + 1 < d.size) {
                val a = d[k]; val c = d[k + 1]; k += 2
                if (a == 0 && c == 0) continue
                val type = when ((a shr 6) and 3) { 0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U" }
                out += "$type${(a shr 4) and 3}${(a and 15).toString(16)}${((c shr 4) and 15).toString(16)}${(c and 15).toString(16)}".uppercase()
            }
        }
        return out.toList()
    }

    override fun onDestroy() {
        elm?.close(); executor.shutdownNow(); super.onDestroy()
    }
}

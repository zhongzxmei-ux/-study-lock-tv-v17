package com.studylock.tv.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LanUploadServer(
    private val repo: QuestionRepository,
    private val onUploaded: (Int) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false

    fun start(port: Int = 8899): String {
        if (running) return "http://${getLocalIp()}:$port"
        running = true
        thread(isDaemon = true) {
            try {
                serverSocket = ServerSocket(port)
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    thread { handleClient(client) }
                }
            } catch (_: Exception) {
            }
        }
        return "http://${getLocalIp()}:$port"
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            if (requestLine.startsWith("POST /upload")) {
                val buf = CharArray(contentLength.coerceAtMost(8 * 1024 * 1024))
                var read = 0
                while (read < buf.size) {
                    val r = reader.read(buf, read, buf.size - read)
                    if (r <= 0) break
                    read += r
                }
                val body = String(buf, 0, read).trim()
                val res = repo.saveCustomJson(body)
                val msg = res.fold(
                    onSuccess = { cnt ->
                        onUploaded(cnt)
                        "OK: 导入成功，共 $cnt 道题！"
                    },
                    onFailure = { err -> "ERROR: 导入失败 - ${err.message}" }
                )
                sendResponse(socket, "text/plain; charset=utf-8", msg)
            } else {
                val html = "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>学伴时光 · 局域网题库上传</title></head><body style='font-family:sans-serif;max-width:560px;margin:24px auto;padding:16px;background:#1e1112;color:#fff;'><h2>学伴时光 · 局域网题库上传</h2><p>选择由 convert_pep_questions.py 生成的 JSON 文件，或直接粘贴 JSON 数组：</p><input type='file' id='f' accept='.json' /><br/><textarea id='t' rows='8' style='width:100%;padding:8px;border-radius:8px;'></textarea><br/><button onclick='up()' style='margin-top:10px;padding:10px 20px;font-size:16px;border-radius:8px;'>上传到小米电视</button><p id='s' style='color:#ffd166;'></p><script>document.getElementById('f').onchange=e=>{const r=new FileReader();r.onload=()=>document.getElementById('t').value=r.result;r.readAsText(e.target.files[0]);};async function up(){const r=await fetch('/upload',{method:'POST',body:document.getElementById('t').value});document.getElementById('s').innerText=await r.text();}</script></body></html>"
                sendResponse(socket, "text/html; charset=utf-8", html)
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendResponse(socket: Socket, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
    }

    private fun getLocalIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}

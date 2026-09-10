package com.dingaimin.dedaocompanion

import android.content.Context
import android.content.SharedPreferences
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.LinkedHashMap
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-shot authenticated-session broker.
 *
 * The server is deliberately reachable only over Android's loopback interface. 得到 loads the tiny
 * page, its native bridge makes the signed request using its own session, and the page posts only
 * allow-listed playback metadata back here. Credentials and response bodies never leave 得到.
 */
class DedaoBridgeServer private constructor(private val context: Context) {
    private val nonce: String
    private var serverSocket: ServerSocket? = null
    @Volatile private var closed: Boolean = false

    init {
        val random: ByteArray = ByteArray(24)
        SecureRandom().nextBytes(random)
        val value: StringBuilder = StringBuilder(random.size * 2)
        for (item: Byte in random) value.append(
            String.format(Locale.US, "%02x", item.toInt() and 0xff)
        )
        nonce = value.toString()
    }

    @Throws(IOException::class)
    private fun open() {
        serverSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        serverSocket!!.setSoTimeout(TIMEOUT_MS)
        DiagnosticReport.mark(context, "server_listening", "port=" + serverSocket!!.getLocalPort())
        val worker: Thread = Thread(Runnable({ this.serve() }), "dedao-session-bridge")
        worker.setDaemon(true)
        worker.start()
    }

    private fun bridgeUrl(): String {
        return "http://127.0.0.1:" + serverSocket!!.getLocalPort() + "/bridge/" + nonce
    }

    private fun serve() {
        var completed: Boolean = false
        try {
            while (!closed && !completed) {
                serverSocket!!
                    .accept()
                    .use({ socket ->
                        socket.setSoTimeout(5000)
                        completed = handle(socket)
                    })
            }
        } catch (error: Exception) {
            if (!closed) {
                DiagnosticReport.mark(context, "server_timeout", error.javaClass.getSimpleName())
                finishFailure("登录态确认超时，可重试；仍失败再使用兼容模式")
            }
        } finally {
            close()
            synchronized(LOCK) { if (active == this) active = null }
        }
    }

    @Throws(IOException::class)
    private fun handle(socket: Socket): Boolean {
        val input: BufferedInputStream = BufferedInputStream(socket.getInputStream())
        val requestLine: String? = readLine(input)
        if (requestLine == null || requestLine!!.isBlank()) return false
        val parts: Array<String> =
            requestLine!!.split((" ").toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
        if (parts.size < 2) {
            respond(socket, 400, "text/plain; charset=utf-8", "bad request")
            return false
        }
        val headers: MutableMap<String, String> = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val split: Int = line!!.indexOf(':')
            if (split > 0)
                headers.put(
                    line!!.substring(0, split).trim({ it <= ' ' }).lowercase(Locale.US),
                    line!!.substring(split + 1).trim({ it <= ' ' }),
                )
        }
        val method: String = parts[0]
        val path: String = parts[1]
        if ("GET" == method && path == "/bridge/" + nonce) {
            DiagnosticReport.mark(context, "page_loaded", "得到已读取本机确认页")
            respond(socket, 200, "text/html; charset=utf-8", page())
            return false
        }
        val eventPrefix: String = "/event/" + nonce + "?stage="
        if ("GET" == method && path.startsWith(eventPrefix)) {
            val stage: String =
                path.substring(eventPrefix.length).replace(("[^a-zA-Z0-9_-]").toRegex(), "")
            DiagnosticReport.mark(context, if (stage.isEmpty()) "page_event" else stage, "来自得到确认页")
            respond(socket, 200, "application/json", "{\"ok\":true}")
            return false
        }
        if ("POST" != method || path != "/result/" + nonce) {
            respond(socket, 404, "text/plain; charset=utf-8", "not found")
            return false
        }
        val length: Int = headers["content-length"]?.toIntOrNull() ?: -1

        if (length < 0 || length > MAX_BODY_BYTES) {
            respond(socket, 413, "application/json", "{\"ok\":false}")
            return false
        }
        val body: ByteArray = ByteArray(length)
        var offset: Int = 0
        while (offset < length) {
            val read: Int = input.read(body, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        if (offset != length) {
            respond(socket, 400, "application/json", "{\"ok\":false}")
            return false
        }
        DiagnosticReport.mark(context, "result_received", "bytes=" + length)
        val saved: Int = storeSanitized(String(body, StandardCharsets.UTF_8))
        if (saved <= 0) {
            finishFailure("没有查询到当前可播放的知识红包")
            respond(socket, 422, "application/json", "{\"ok\":false}")
        } else {
            respond(socket, 200, "application/json", "{\"ok\":true}")
            DiagnosticReport.mark(context, "result_saved", "count=" + saved)
            returnToCompanion(true)
        }
        return true
    }

    private fun storeSanitized(body: String): Int {
        try {
            val root: JSONObject = JSONObject(body)
            val submitted: JSONArray? = root.optJSONArray("items")
            if (submitted == null) return 0
            val stored: JSONArray = JSONArray()
            val seen: LinkedHashMap<String, Boolean> = LinkedHashMap<String, Boolean>()
            var i: Int = 0
            while (i < submitted!!.length() && stored.length() < 200) {
                val raw: JSONObject? = submitted!!.optJSONObject(i)
                if (raw == null || !raw!!.optBoolean("hasRights", false)) {
                    i++
                    continue
                }
                val id: String = clean(raw!!.optString("id"), 160)
                val title: String = clean(raw!!.optString("title"), 300)
                val audioId: String = clean(raw!!.optString("audioId"), 300)
                if (id.isEmpty() || title.isEmpty() || audioId.isEmpty()) {
                    i++
                    continue
                }
                val key: String = "${raw!!.optInt("type")}:$id"
                if (seen.put(key, java.lang.Boolean.TRUE) != null) {
                    i++
                    continue
                }
                val item: RedPacketItem =
                    RedPacketItem(
                        id,
                        raw!!.optInt("type"),
                        clean(raw!!.optString("course"), 300),
                        title,
                        clean(raw!!.optString("deepLink"), 1000),
                        audioId,
                        Math.max(0L, raw!!.optLong("claimedAt")),
                        raw!!.optBoolean("completed", false),
                    )
                stored.put(item.toJson())
                i++
            }
            if (stored.length() == 0) return 0
            context
                .getSharedPreferences("page_probe", Context.MODE_PRIVATE)
                .edit()
                .putString("scan_items", stored.toString())
                .putInt("scan_count", stored.length())
                .putInt(
                    "scan_expired_skipped",
                    Math.max(0, root.optInt("examined", stored.length()) - stored.length()),
                )
                .putBoolean("scan_requested", false)
                .remove("scan_return_to_app")
                .putString("scan_source", "authenticated_bridge")
                .putString("scan_status", "已由得到确认 " + stored.length() + " 条有效红包")
                .putLong("scan_finished_at", System.currentTimeMillis())
                .commit()
            return stored.length()
        } catch (error: Exception) {
            DiagnosticReport.mark(context, "result_parse_failed", error.javaClass.getSimpleName())
            return 0
        }
    }

    private fun clean(value: String?, maxLength: Int): String {
        if (value == null) return ""
        val result: String = value!!.replace('\u0000', ' ').trim({ it <= ' ' })
        return if (result.length <= maxLength) result else result.substring(0, maxLength)
    }

    private fun finishFailure(status: String) {
        val preferences: SharedPreferences =
            context.getSharedPreferences("page_probe", Context.MODE_PRIVATE)
        preferences
            .edit()
            .putBoolean("scan_requested", false)
            .remove("scan_return_to_app")
            .putString("scan_source", "bridge_failed")
            .putString("scan_status", status)
            .apply()
        DiagnosticReport.mark(context, "bridge_failed", status)
        returnToCompanion(false)
    }

    private fun returnToCompanion(success: Boolean) {
        BridgeReturn.request(context, success)
    }

    @Throws(IOException::class)
    private fun respond(socket: Socket, status: Int, type: String, content: String) {
        val body: ByteArray = content.toByteArray(StandardCharsets.UTF_8)
        val reason: String = if (status == 200) "OK" else "Error"
        val header: String =
            ("HTTP/1.1 " +
                status +
                " " +
                reason +
                "\r\n" +
                "Content-Type: " +
                type +
                "\r\n" +
                "Content-Length: " +
                body.size +
                "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; " +
                "connect-src 'self'; style-src 'unsafe-inline'\r\n" +
                "Connection: close\r\n\r\n")
        val output: BufferedOutputStream = BufferedOutputStream(socket.getOutputStream())
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    @Throws(IOException::class)
    private fun readLine(input: BufferedInputStream): String? {
        val output: ByteArrayOutputStream = ByteArrayOutputStream()
        var previous: Int = -1
        while (output.size() < 8192) {
            val current = input.read()
            if (current < 0) break
            if (previous == '\r'.toInt() && current == '\n'.toInt()) {
                val bytes: ByteArray = output.toByteArray()
                return String(bytes, 0, Math.max(0, bytes.size - 1), StandardCharsets.US_ASCII)
            }
            output.write(current)
            previous = current
        }
        return if (output.size() == 0) null
        else String(output.toByteArray(), StandardCharsets.US_ASCII)
    }

    private fun page(): String {
        return """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>正在确认知识红包</title><style>
                body{font-family:sans-serif;background:#fafafa;color:#1d1f24;display:flex;min-height:90vh;align-items:center;justify-content:center;margin:0}
                main{text-align:center;padding:28px}.dot{color:#ff6a2a;font-size:34px}p{color:#70747e;font-size:14px}a{display:none;margin:28px auto 0;padding:14px 26px;border-radius:10px;background:#ff6a2a;color:white;text-decoration:none;font-weight:700}
                </style></head><body><main><div class="dot">●</div><h3>正在确认有效红包</h3><p id="s">正在连接得到…</p><a id="r" href="dedaocompanion://bridge/complete">返回知识红包伴侣</a></main><script>
                (()=>{const out={items:[],examined:0},seen=new Set();let nativeBridge=null;
                const event=stage=>fetch('/event/%NONCE%?stage='+stage,{cache:'no-store'}).catch(()=>{});
                const parse=v=>{if(typeof v==='string'){try{return JSON.parse(v)}catch(e){return v}}return v};
                const walk=(v,fn,depth=0)=>{v=parse(v);if(!v||depth>12)return;if(Array.isArray(v)){v.forEach(x=>walk(x,fn,depth+1));return}if(typeof v==='object'){fn(v);Object.keys(v).forEach(k=>walk(v[k],fn,depth+1))}};
                const collect=response=>{walk(response,o=>{if(!o.authority_intro||typeof o.authority_intro.red_packet_rights!=='boolean')return;out.examined++;
                  if(o.authority_intro.red_packet_rights!==true)return;const a=o.is_article_collection&&o.article_item?o.article_item:o;
                  const resource=a.resource||{},audio=String(resource.audio_id||'').trim(),id=String(a.id||a.product_id||'').trim(),title=String(a.product_title||a.title||'').trim();
                  if(!audio||!id||!title)return;const key=String(a.product_type||o.product_type||0)+':'+id;if(seen.has(key))return;seen.add(key);
                  const progress=(a.progress_intro||o.progress_intro||{}).progress;
                  out.items.push({hasRights:true,id,type:Number(a.product_type||o.product_type||0),course:String(o.product_title||o.title||''),title,
                    deepLink:String(a.dd_url||a.ddurl||o.dd_url||o.ddurl||''),audioId:audio,claimedAt:Number(o.collection_timestamp||0),
                    completed:a.is_finished===1||o.is_finished===1||Number(progress)>=100});});};
                const more=response=>{let yes=false;walk(response,o=>{if(o.is_more===true||o.is_more===1)yes=true});return yes};
                const cursor=response=>{let value=0;walk(response,o=>{const n=Number(o.collection_timestamp||0);if(n>0&&(value===0||n<value))value=n});return value};
                const bridge=()=>new Promise((resolve,reject)=>{let tries=0;const ready=()=>{if(window.WebViewJavascriptBridge)return resolve(window.WebViewJavascriptBridge);if(++tries>80)return reject(Error('bridge unavailable'));setTimeout(ready,100)};ready()});
                const load=(b,params)=>new Promise((resolve,reject)=>{let done=false;const timer=setTimeout(()=>{if(!done)reject(Error('request timeout'))},10000);
                  const message={sdkType:'network.load',seqid:'redpacket_'+Date.now()+'_'+Math.random(),data:{url:'${'$'}_ENTREE_DOMAIN_$/mustard-view/v1/red_packet/category/list',method:'POST',proxyType:'gateway/entree',contentType:'application/json',params}};
                  try{b.send(message,r=>{done=true;clearTimeout(timer);resolve(parse(r))})}catch(e){clearTimeout(timer);reject(e)}});
                const showReturn=success=>{const href='dedaocompanion://bridge/'+(success?'complete':'failed'),a=document.getElementById('r');a.href=href;a.style.display='block';
                  const navigate=()=>{try{if(nativeBridge){nativeBridge.send({sdkType:'jump.universal',seqid:'redpacket_return_'+Date.now(),data:{type:'scheme',route:href}},()=>{});return}}catch(e){}try{location.href=href}catch(e){}};
                  a.onclick=e=>{e.preventDefault();navigate()};setTimeout(navigate,250)};
                const run=async()=>{try{await event('page_script_started');const b=await bridge();nativeBridge=b;await event('js_bridge_ready');document.getElementById('s').textContent='正在读取红包列表…';let max=0;for(let page=0;page<10;page++){const params={count:20,type:0};if(max>0)params.max_timestamp=max;
                    const response=await load(b,params);collect(response);const next=cursor(response);if(!more(response)||!next||next===max)break;max=next;}
                  await event('api_query_complete');document.getElementById('s').textContent='正在保存确认结果…';
                  const result=await fetch('/result/%NONCE%',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(out)});
                  if(!result.ok)throw Error('no playable items');document.getElementById('s').textContent='确认完成，正在返回伴侣。若未自动返回，请点击通知栏中的“红包已刷新”，或手动打开伴侣；结果已保存。';showReturn(true);
                }catch(e){document.getElementById('s').textContent='确认失败，请返回伴侣应用重试';
                  try{await fetch('/result/%NONCE%',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"items":[],"examined":0}'})}catch(x){}
                  showReturn(false);}};run();})();
                </script></body></html>
                """
            .replace("%NONCE%", nonce)
    }

    private fun close() {
        closed = true
        BridgeKeepAliveService.stop(context)
        if (serverSocket != null) {
            try {
                serverSocket!!.close()
            } catch (ignored: IOException) {}
        }
    }

    companion object {
        private val MAX_BODY_BYTES: Int = 512 * 1024
        private val TIMEOUT_MS: Int = 30000
        private val LOCK: Any = Any()
        private var active: DedaoBridgeServer? = null

        @Throws(IOException::class)
        fun start(context: Context): String {
            synchronized(LOCK) {
                if (active != null) active!!.close()
                val server: DedaoBridgeServer = DedaoBridgeServer(context.getApplicationContext())
                BridgeKeepAliveService.start(context)
                try {
                    server.open()
                } catch (error: IOException) {
                    BridgeKeepAliveService.stop(context)
                    DiagnosticReport.mark(
                        context,
                        "server_start_failed",
                        error.javaClass.getSimpleName(),
                    )
                    throw error
                }

                active = server
                return server.bridgeUrl()
            }
        }
    }
}

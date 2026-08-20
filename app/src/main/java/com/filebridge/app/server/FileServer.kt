package com.filebridge.app.server

import android.net.Uri
import com.filebridge.app.crypto.TlsUtil
import com.filebridge.app.data.DocStore
import com.filebridge.app.data.SecurityManager
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.request.Method
import org.nanohttpd.protocols.http.response.Response
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class SharedRoot(val label: String, val treeUri: String)

/**
 * Embedded HTTP/HTTPS file server. Everything is password-gated via the
 * [SessionStore]; protected endpoints refuse to touch the SAF trees outside
 * the granted root, so a web client can never escape the shared folders.
 */
class FileServer(
    private val port: Int,
    private val tls: Boolean,
    private val sharedRoots: List<SharedRoot>,
    private val vaultEnabled: Boolean,
    private val security: SecurityManager,
    private val docStore: DocStore,
    private val sessionStore: SessionStore,
) : NanoHTTPD(port) {

    private val loginGuard = LoginGuard()

    init {
        if (tls) {
            makeSecure(TlsUtil.createServerSocketFactory(), arrayOf("TLSv1.2", "TLSv1.3"))
        }
    }

    // ------------------------------------------------------------------ routes

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "internal error")
        }
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri

        if (uri == "/login") {
            if (session.method == Method.POST) return handleLogin(session)
            if (session.method == Method.GET) {
                val err = session.parms?.get("error") == "1"
                return renderLogin(err)
            }
            return notFound()
        }
        if (uri == "/logout") {
            sessionStore.revoke(cookieToken(session))
            return redirect("/login")
        }

        val token = cookieToken(session)
        if (token == null || !sessionStore.isValid(token)) {
            return if (session.method == Method.PUT) {
                text(Response.Status.FORBIDDEN, "forbidden: not signed in")
            } else {
                redirect("/login")
            }
        }

        return when {
            uri == "/" -> renderHome()
            uri == "/list" -> handleList(session)
            uri == "/dl" -> handleDownload(session)
            uri == "/up" && session.method == Method.PUT -> handleUpload(session)
            uri == "/vault" && vaultEnabled -> handleVaultList()
            uri == "/vault/dl" && vaultEnabled -> handleVaultDownload(session)
            uri == "/vault/up" && session.method == Method.PUT && vaultEnabled -> handleVaultUpload(session)
            uri == "/vault/del" && vaultEnabled -> handleVaultDelete(session)
            else -> notFound()
        }
    }

    // ------------------------------------------------------------------ auth

    private fun handleLogin(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val password = (session.parms ?: emptyMap())["password"]?.toCharArray() ?: charArrayOf()
        val remoteIp = session.remoteIp ?: "unknown"

        if (!loginGuard.allowed(remoteIp)) {
            return renderLogin(true, "尝试次数过多，请稍后再试")
        }
        if (security.verifyPassword(password)) {
            loginGuard.success(remoteIp)
            val token = sessionStore.newToken()
            return redirect("/").also {
                it.addHeader(SET_COOKIE, "fb_session=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=${24 * 3600}")
            }
        }
        loginGuard.fail(remoteIp)
        return renderLogin(true, "密码错误")
    }

    private fun cookieToken(session: IHTTPSession): String? {
        val cookies = session.headers?.get("cookie") ?: return null
        return cookies.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("fb_session=") }
            ?.substringAfter("fb_session=")
    }

    // ------------------------------------------------------------------ browser UI

    private fun handleList(session: IHTTPSession): Response {
        val tree = decode(session.parms?.get("tree")) ?: return notFound()
        if (sharedRoots.none { it.treeUri == tree }) return forbidden()
        val root = sharedRoots.first { it.treeUri == tree }
        val docId = decode(session.parms?.get("doc")) ?: rootDocId(tree)
        val entries = docStore.listChildren(Uri.parse(tree), docId)
        return renderList(root, docId, entries)
    }

    private fun handleDownload(session: IHTTPSession): Response {
        val tree = decode(session.parms?.get("tree")) ?: return notFound()
        if (sharedRoots.none { it.treeUri == tree }) return forbidden()
        val docId = decode(session.parms?.get("doc")) ?: return notFound()
        val opened = docStore.openDocument(Uri.parse(tree), docId) ?: return notFound()
        return streamResponse(opened)
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val tree = decode(session.parms?.get("tree")) ?: return notFound()
        if (sharedRoots.none { it.treeUri == tree }) return forbidden()
        val docId = decode(session.parms?.get("doc")) ?: return notFound()
        val rawName = session.headers?.get("x-file-name") ?: return text(Response.Status.BAD_REQUEST, "missing file name")
        val name = String(Base64.getUrlDecoder().decode(rawName), Charsets.UTF_8)
        val target = docStore.openWrite(Uri.parse(tree), docId, name) ?: return text(Response.Status.BAD_REQUEST, "cannot write")

        target.output.use { out ->
            session.inputStream.use { it.copyTo(out, 256 * 1024) }
        }
        return text(Response.Status.OK, "ok")
    }

    private fun handleVaultList(): Response = renderVault()

    private fun handleVaultDownload(session: IHTTPSession): Response {
        val name = String(Base64.getUrlDecoder().decode(session.parms?.get("name") ?: ""), Charsets.UTF_8)
        val stream = security.open(name) ?: return notFound()
        return newChunkedResponse(Response.Status.OK, mime(name), stream).also {
            it.addHeader("Content-Disposition", "attachment; filename*=UTF-8''${percentEncode(name)}")
        }
    }

    private fun handleVaultUpload(session: IHTTPSession): Response {
        if (!security.vaultUnlocked) return text(Response.Status.FORBIDDEN, "vault locked on phone")
        val rawName = session.headers?.get("x-file-name") ?: return text(Response.Status.BAD_REQUEST, "missing file name")
        val name = String(Base64.getUrlDecoder().decode(rawName), Charsets.UTF_8)
        val ok = runCatching {
            security.encrypt(name, session.inputStream)
        }.getOrDefault(false)
        return if (ok) text(Response.Status.OK, "ok") else text(Response.Status.INTERNAL_ERROR, "encrypt failed")
    }

    private fun handleVaultDelete(session: IHTTPSession): Response {
        val name = String(Base64.getUrlDecoder().decode(session.parms?.get("name") ?: ""), Charsets.UTF_8)
        security.delete(name)
        return redirect("/vault")
    }

    // ------------------------------------------------------------------ render

    private fun renderLogin(error: Boolean, message: String = "请输入访问密码"): Response =
        newFixedLengthResponse(Response.Status.OK, MIME_HTML, PAGE_LOGIN(error, message))

    private fun renderHome(): Response {
        return page("FileBridge", "", """
            <div class="panel">
              <h2>共享的文件夹</h2>
              <ul class="roots">
                ${sharedRoots.joinToString("") { "<li><a href='/list?tree=${enc(it.treeUri)}'>📁 ${esc(it.label)}</a></li>" }}
              </ul>
              ${if (vaultEnabled) "<p class='muted'>同时提供 <a href='/vault'>🔒 保险箱</a>（加密文件）</p>" else ""}
            </div>
        """.trimIndent())

    private fun renderList(root: SharedRoot, docId: String, entries: List<DocStore.DocEntry>): Response {
        val treeParam = enc(root.treeUri)
        val crumbs = breadcrumbs(root, docId)
        val rows = StringBuilder()
        entries.forEach { e ->
            if (e.isDirectory) {
                rows.append("<li class='dir'><a href='/list?tree=$treeParam&doc=${enc(e.documentId)}'>📁 ${esc(e.name)}</a></li>")
            } else {
                rows.append(
                    "<li class='file'>" +
                        "<a class='name' href='/dl?tree=$treeParam&doc=${enc(e.documentId)}' download>📄 ${esc(e.name)}</a>" +
                        "<span class='size'>${fmtSize(e.size)}</span></li>"
                )
            }
        }
        val upload = uploader(treeParam, docId)
        val body = """
            <div class="bar">
              <span class="crumb">$crumbs</span>
              <span class="util"><a href="/logout">退出</a></span>
            </div>
            $upload
            <ul class="items">
              ${if (docId != rootDocId(root.treeUri)) "<li class='dir'><a href='/list?tree=$treeParam&doc=${enc(parentOf(root, docId))}'>⬆ 返回上级</a></li>" else ""}
              $rows
            </ul>
        """.trimIndent()
        return page(root.label, treeParam, body)
    }

    private fun renderVault(): Response {
        val entries = security.list()
        val rows = entries.joinToString("") {
            "<li class='file'><a class='name' href='/vault/dl?name=${enc(it.name)}' download>🔒 ${esc(it.name)}</a>" +
                "<span class='size'>${fmtSize(it.cipherSize)}</span>" +
                "<a class='del' href='/vault/del?name=${enc(it.name)}'>删除</a></li>"
        }
        val body = """
            <div class="bar">
              <span class="crumb">保险箱</span>
              <span class="util"><a href="/logout">退出</a></span>
            </div>
            <div class="panel">
              <p class="muted">这些文件在手机本地使用 AES-256-GCM 加密保存，查看时实时解密。</p>
            </div>
            $uploaderVault
            <ul class="items">$rows</ul>
            ${if (entries.isEmpty()) "<p class='muted'>保险箱为空，可在上方上传文件（会自动加密存储）。</p>" else ""}
        """.trimIndent()
        return page("保险箱", "vault", body)
    }

    private fun uploader(treeParam: String, docId: String): String = """
        <div class="uploader">
          <label>上传文件到当前目录 <input type="file" id="fl" multiple></label>
          <button onclick="fbUpload('$treeParam', '${enc(docId)}')" type="button">上传</button>
          <span id="fbs" class="muted"></span>
        </div>
    """.trimIndent()

    private val uploaderVault: String = """
        <div class="uploader">
          <label>上传到保险箱（加密后保存） <input type="file" id="flv" multiple></label>
          <button onclick="fbVaultUpload()" type="button">加密上传</button>
          <span id="fbvs" class="muted"></span>
        </div>
    """.trimIndent()

    private fun tabs(active: String): String {
        val sb = StringBuilder("<div class='tabs'>")
        sharedRoots.forEach { r ->
            sb.append("<a href='/list?tree=${enc(r.treeUri)}' class='${if (active == r.treeUri) "on" else ""}'>${esc(r.label)}</a>")
        }
        if (vaultEnabled) sb.append("<a href='/vault' class='${if (active == "vault") "on" else ""}'>🔒 保险箱</a>")
        sb.append("<a href='/' class='${if (active == "") "on" else ""}'>首页</a>")
        sb.append("</div>")
        return sb.toString()
    }

    private fun breadcrumbs(root: SharedRoot, docId: String): String {
        val rootId = rootDocId(root.treeUri)
        val rel = docId.removePrefix(rootId).trim('/')
        val sb = StringBuilder("<a href='/list?tree=${enc(root.treeUri)}'>${esc(root.label)}</a>")
        if (rel.isNotEmpty()) {
            val parts = rel.split('/')
            var cursor = rootId
            for (i in parts.indices) {
                cursor += "/" + parts[i]
                if (i == parts.lastIndex) {
                    sb.append(" <span class='sep'>/</span> <b>${esc(parts[i])}</b>")
                } else {
                    sb.append(" <span class='sep'>/</span> <a href='/list?tree=${enc(root.treeUri)}&doc=${enc(cursor)}'>${esc(parts[i])}</a>")
                }
            }
        }
        return sb.toString()
    }

    private fun parentOf(root: SharedRoot, docId: String): String {
        val rootId = rootDocId(root.treeUri)
        if (docId == rootId) return rootId
        return docId.substringBeforeLast('/').takeIf { it.isNotEmpty() } ?: rootId
    }

    private fun page(title: String, active: String, body: String): Response {
        val html = PAGE_SHELL(title) { tabs(active) + body }
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
    }

    // ------------------------------------------------------------------ helpers

    private fun streamResponse(opened: DocStore.OpenedDoc): Response {
        val resp = if (opened.size >= 0) {
            newFixedLengthResponse(Response.Status.OK, mime(opened.name), opened.stream, opened.size)
        } else {
            newChunkedResponse(Response.Status.OK, mime(opened.name), opened.stream)
        }
        resp.addHeader("Content-Disposition", "attachment; filename*=UTF-8''${percentEncode(opened.name)}")
        return resp
    }

    private fun rootDocId(treeUri: String): String =
        android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))

    private fun redirect(location: String): Response =
        newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").also {
            it.addHeader("Location", location)
        }

    private fun text(status: Response.Status, s: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, s)

    private fun notFound(): Response = text(Response.Status.NOT_FOUND, "not found")
    private fun forbidden(): Response = text(Response.Status.FORBIDDEN, "forbidden")

    private fun enc(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun decode(s: String?): String? =
        s?.takeIf { it.isNotBlank() }?.let {
            runCatching { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
        }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun percentEncode(s: String): String {
        val sb = StringBuilder()
        s.toByteArray(Charsets.UTF_8).forEach { b ->
            val c = b.toInt() and 0xff
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) || c == '-' || c == '_' || c == '.') {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(lowerHex(c))
            }
        }
        return sb.toString()
    }

    private fun lowerHex(b: Int): String {
        val s = Integer.toHexString(b)
        return if (s.length < 2) "0$s" else s
    }

    private fun fmtSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    private fun mime(name: String): String = when {
        name.endsWith(".html") || name.endsWith(".htm") -> "text/html"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".js") -> "application/javascript"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".gif") -> "image/gif"
        name.endsWith(".webp") -> "image/webp"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".mp4") || name.endsWith(".m4v") -> "video/mp4"
        name.endsWith(".mkv") -> "video/x-matroska"
        name.endsWith(".mp3") -> "audio/mpeg"
        name.endsWith(".wav") -> "audio/wav"
        name.endsWith(".pdf") -> "application/pdf"
        name.endsWith(".zip") -> "application/zip"
        name.endsWith(".txt") || name.endsWith(".md") -> "text/plain"
        else -> "application/octet-stream"
    }

    // ------------------------------------------------------------------ pages

    companion object {
        const val SET_COOKIE = "Set-Cookie"
        const val MIME_HTML = "text/html"
        const val MIME_PLAINTEXT = "text/plain"
    }
}

private fun PAGE_LOGIN(error: Boolean, message: String): String {
    val css = PAGE_CSS
    return """
    <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>FileBridge · 访问需授权</title><style>$css</style></head><body>
    <div class="shell">
      <div class="tabs"><a class="on">FileBridge 文件桥</a></div>
      <div class="login">
        <h2>请输入访问密码</h2>
        ${if (error) "<p class='err'>$message</p>" else ""}
        <form method="post" action="/login" autocomplete="off">
          <input type="password" name="password" placeholder="访问密码" autofocus required>
          <button>进入</button>
        </form>
        <p class="muted">密码在使用文件桥的手机端设置</p>
      </div>
    </div></body></html>
    """.trimIndent()
}

private fun PAGE_SHELL(title: String, body: () -> String): String {
    val css = PAGE_CSS
    val script = """
      <script>
      function encodeB64(str){ var b=[]; for(var i=0;i<str.length;i++){b.push(str.charCodeAt(i));} var bytes=new Uint8Array(b); var bin=''; for(var j=0;j<bytes.length;j++){bin+=String.fromCharCode(bytes[j]);} return btoa(bin).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,''); }
      function fbUpload(tree,docCtx){ var inp=document.getElementById('fl'); if(!inp.files.length){document.getElementById('fbs').textContent='请先选择文件';return;} var done=0, n=inp.files.length;
        Array.from(inp.files).forEach(function(f){ var fd=new FormData(); var xhr=new XMLHttpRequest(); xhr.open('PUT','/up?tree='+tree+'&doc='+docCtx); xhr.setRequestHeader('X-File-Name', encodeB64(f.name));
          xhr.onload=function(){ done++; document.getElementById('fbs').textContent='已上传 '+done+'/'+n; if(done===n){setTimeout(function(){location.reload();},600);} };
          xhr.send(f); });
      }
      function fbVaultUpload(){ var inp=document.getElementById('flv'); if(!inp.files.length){document.getElementById('fbvs').textContent='请先选择文件';return;} var done=0,n=inp.files.length;
        Array.from(inp.files).forEach(function(f){ var xhr=new XMLHttpRequest(); xhr.open('PUT','/vault/up'); xhr.setRequestHeader('X-File-Name', encodeB64(f.name));
          xhr.onload=function(){ done++; document.getElementById('fbvs').textContent='已加密上传 '+done+'/'+n; if(done===n){setTimeout(function(){location.reload();},600);} };
          xhr.send(f); });
      }
      </script>
    """.trimIndent()
    return """
    <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>${title} · FileBridge</title><style>$css</style></head><body>
    <div class="shell">${body()}</div>
    $script
    </body></html>
    """.trimIndent()
}

private val PAGE_CSS = """
    *{box-sizing:border-box}
    body{margin:0;font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;background:#f4f7fb;color:#16233f}
    .shell{max-width:860px;margin:0 auto;padding:16px}
    .tabs{display:flex;gap:6px;flex-wrap:wrap;background:#fff;border:1px solid #e3e9f2;border-radius:12px;padding:10px;margin-bottom:14px}
    .tabs a{text-decoration:none;color:#54709b;padding:6px 12px;border-radius:8px;font-size:14px;font-weight:600}
    .tabs a.on{background:#2563eb;color:#fff}
    .bar{display:flex;justify-content:space-between;align-items:center;background:#fff;border:1px solid #e3e9f2;border-radius:12px;padding:10px 14px;margin-bottom:12px}
    .crumb a{color:#2563eb;text-decoration:none} .crumb .sep{color:#b8c4d6} .crumb b{color:#16233f}
    .util a{color:#54709b;text-decoration:none;font-size:13px}
    .uploader{display:flex;gap:10px;align-items:center;padding:12px 14px;background:#eef4ff;border:1px dashed #b6cdfb;border-radius:12px;margin-bottom:12px;font-size:14px}
    .uploader button{background:#2563eb;color:#fff;border:0;border-radius:8px;padding:7px 16px;cursor:pointer}
    .items{list-style:none;padding:0;margin:0;background:#fff;border:1px solid #e3e9f2;border-radius:12px;overflow:hidden}
    .items li{display:flex;align-items:center;padding:11px 14px;border-bottom:1px solid #f0f3f8}
    .items li:last-child{border-bottom:0}
    .items .name{color:#1c2d4f;text-decoration:none;flex:1}
    .items .dir a{color:#2563eb;text-decoration:none;font-weight:600}
    .items .size{color:#8ea0bd;font-size:12px;margin-right:10px}
    .items .del{color:#dc2626;text-decoration:none;font-size:12px}
    .panel{background:#fff;border:1px solid #e3e9f2;border-radius:12px;padding:16px}
    .panel h2{margin:0 0 8px;font-size:16px}
    .roots{list-style:none;padding:0} .roots a{color:#2563eb;text-decoration:none}
    .muted{color:#8ea0bd;font-size:13px}
    .login{max-width:380px;margin:60px auto;background:#fff;border:1px solid #e3e9f2;border-radius:14px;padding:26px}
    .login h2{margin:0 0 14px;font-size:18px}
    .login form{display:flex;flex-direction:column;gap:10px}
    .login input{padding:11px 12px;border:1px solid #cdd7e6;border-radius:9px;font-size:15px}
    .login button{padding:11px;border:0;border-radius:9px;background:#2563eb;color:#fff;font-size:15px;cursor:pointer}
    .err{color:#dc2626;font-size:13px;margin:0 0 10px}
""".trimIndent()

private class LoginGuard {
    private data class State(var fails: Int, var windowStart: Long)
    private val map = ConcurrentHashMap<String, State>()
    private val WINDOW = 5 * 60_000L
    private val LIMIT = 5

    fun allowed(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val s = map[ip] ?: return true
        if (now - s.windowStart > WINDOW) { map.remove(ip); return true }
        return s.fails < LIMIT
    }

    fun fail(ip: String) {
        val now = System.currentTimeMillis()
        val s = map.computeIfAbsent(ip) { State(0, now) }
        if (now - s.windowStart > WINDOW) { s.windowStart = now; s.fails = 1 } else s.fails++
    }

    fun success(ip: String) { map.remove(ip) }
}
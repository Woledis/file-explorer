//! FileBridge Rust core.
//!
//! Milestone 2.1: a real HTTP/1.1 native transfer engine. It owns the network
//! hot path (multi-connection, HEAD/GET, single-range resumes, streaming file
//! read -> socket write with a 64KB buffer) and is gated by a bearer token.
//! The Kotlin layer starts it via JNI; routes that need SAF stay in Kotlin.

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::io::{Read, Seek, SeekFrom, Write};
use std::net::TcpListener;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

const VERSION: &str = "filebridge-rs 0.2.0";
const IO_BUF: usize = 64 * 1024;

static STOP: AtomicBool = AtomicBool::new(false);

#[no_mangle]
pub extern "system" fn Java_com_filebridge_app_native_FbCore_ping<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let s = env.new_string(VERSION).expect("new_string");
    s.into_raw()
}

/// Starts the native transfer engine and blocks until [stopHttp] is called or
/// the listener closes. Returns 1 on clean listener bind+run, 0 otherwise.
#[no_mangle]
pub extern "system" fn Java_com_filebridge_app_native_FbCore_serveHttp<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    port: jint,
    root: JString<'local>,
    token: JString<'local>,
) -> jint {
    let root_owned = match env.get_string(&root) {
        Ok(r) => r.to_str().map(str::to_owned).unwrap_or_default(),
        Err(_) => String::new(),
    };
    let token_owned = match env.get_string(&token) {
        Ok(t) => t.to_str().map(str::to_owned).unwrap_or_default(),
        Err(_) => String::new(),
    };
    match serve(port as u16, &root_owned, &token_owned) {
        Ok(()) => 1,
        Err(_) => 0,
    }
}

/// Asks a running native engine to stop serving.
#[no_mangle]
pub extern "system" fn Java_com_filebridge_app_native_FbCore_stopHttp<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    STOP.store(true, Ordering::SeqCst);
}

fn serve(port: u16, root: &str, token: &str) -> std::io::Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", port))?;
    let root = Arc::new(root.to_owned());
    let token = Arc::new(token.to_owned());
    for conn in listener.incoming() {
        if STOP.swap(false, Ordering::SeqCst) {
            break;
        }
        match conn {
            Ok(stream) => {
                let root = Arc::clone(&root);
                let token = Arc::clone(&token);
                std::thread::spawn(move || {
                    let _ = handle_conn(stream, &root, &token);
                });
            }
            Err(_) => continue,
        }
    }
    Ok(())
}

fn handle_conn(mut stream: std::net::TcpStream, root: &str, token: &str) -> std::io::Result<()> {
    let mut buf = Vec::with_capacity(8192);
    let mut tmp = [0u8; 2048];
    loop {
        let n = stream.read(&mut tmp)?;
        if n == 0 {
            break;
        }
        buf.extend_from_slice(&tmp[..n]);
        if let Some(hd_end) = find_headers_end(&buf) {
            let head = String::from_utf8_lossy(&buf[..hd_end]).into_owned();
            let body_pref = &buf[hd_end + 4..];
            match dispatch(&head, body_pref, &mut stream, root, token) {
                Ok(keep) if keep => {
                    buf.drain(..hd_end + 4);
                    buf.clear(); // 头部之后预留的 body 已被 PUT 消费,清空等待下一请求
                    if buf.is_empty() {
                        continue;
                    }
                }
                _ => break,
            }
        }
    }
    Ok(())
}

/// Returns Ok(true) when `Connection: keep-alive` was honoured (more requests
/// may follow on the same socket). `body_pref` carries bytes already buffered
/// past the header terminator (needed for PUT).
fn dispatch(
    head: &str,
    body_pref: &[u8],
    stream: &mut std::net::TcpStream,
    root: &str,
    token: &str,
) -> std::io::Result<bool> {
    let mut lines = head.lines();
    let request_line = match lines.next() {
        Some(l) => l,
        None => return write_bad(stream),
    };
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("");
    let target = parts.next().unwrap_or("");
    let _version = parts.next().unwrap_or("");

    let mut headers: Vec<(String, String)> = Vec::new();
    for l in lines {
        let mut it = l.splitn(2, ':');
        if let (Some(k), Some(v)) = (it.next(), it.next()) {
            headers.push((k.trim().to_ascii_lowercase(), v.trim().to_owned()));
        }
    }

    let keep_alive = header(&headers, "connection")
        .map(|v| v.eq_ignore_ascii_case("keep-alive"))
        .unwrap_or(false);

    if method != "GET" && method != "HEAD" && method != "PUT" {
        let _ = write_simple(stream, "405", "Method Not Allowed", None);
        return Ok(false);
    }

    // 鉴权: 空 token=开放(本地降级用); 否则需 header Bearer 或 query ?t= 之一匹配。
    let query_tok = query_token(target);
    let auth_hdr = header(&headers, "authorization").cloned().unwrap_or_default();
    let authorized = token.is_empty()
        || auth_hdr == format!("Bearer {}", token)
        || (!query_tok.is_empty() && query_tok == token);
    if !authorized {
        let _ = write_simple(stream, "401", "Unauthorized", None);
        return Ok(false);
    }

    let rel = decode_path(target);
    let path = match safe_path(root, &rel) {
        Some(p) => p,
        None => {
            let _ = write_simple(stream, "404", "Not Found", None);
            return Ok(false);
        }
    };

    if method == "PUT" {
        return handle_put(stream, &headers, &path, body_pref, keep_alive);
    }

    // 目录 → 渲染原生列表; 文件 → 流式下载(GET/HEAD + Range)
    if let Ok(md) = std::fs::metadata(&path) {
        if md.is_dir() {
            return send_listing(stream, &path, &rel, token, keep_alive);
        }
    }

    let range = parse_range(header(&headers, "range"));
    send_file(stream, &path, method == "HEAD", range, keep_alive)
}

const MAX_BODY: u64 = 20 * 1024 * 1024 * 1024; // 20 GiB 上传上限

/// Upload: writes `Content-Length` body to [path] via a temp file + atomic
/// rename. Parent dir must already exist. Connection always closes afterwards.
fn handle_put(
    stream: &mut std::net::TcpStream,
    headers: &[(String, String)],
    path: &PathBuf,
    body_pref: &[u8],
    _keep_alive: bool,
) -> std::io::Result<bool> {
    let len: u64 = match header(headers, "content-length").and_then(|s| s.trim().parse().ok()) {
        Some(l) => l,
        None => {
            let _ = write_simple(stream, "400", "Bad Request", None);
            return Ok(false);
        }
    };
    if len > MAX_BODY {
        let _ = write_simple(stream, "413", "Payload Too Large", None);
        return Ok(false);
    }
    let parent_ok = path.parent().map(|p| p.is_dir()).unwrap_or(false);
    if !parent_ok {
        let _ = write_simple(stream, "409", "Conflict", None);
        return Ok(false);
    }

    let tmp = path.with_extension("part");
    let mut f = match std::fs::File::create(&tmp) {
        Ok(f) => f,
        Err(_) => {
            let _ = write_simple(stream, "403", "Forbidden", None);
            return Ok(false);
        }
    };

    let mut left = len;
    let pref_n = std::cmp::min(left as usize, body_pref.len());
    if pref_n > 0 {
        f.write_all(&body_pref[..pref_n])?;
        left -= pref_n as u64;
    }
    let mut b = vec![0u8; IO_BUF];
    while left > 0 {
        let want = std::cmp::min(IO_BUF, left as usize);
        let n = stream.read(&mut b[..want])?;
        if n == 0 {
            drop(f);
            let _ = std::fs::remove_file(&tmp);
            return Ok(false);
        }
        f.write_all(&b[..n])?;
        left -= n as u64;
    }
    f.sync_all()?;
    drop(f);

    if std::fs::rename(&tmp, path).is_err() {
        let _ = std::fs::remove_file(&tmp);
        let _ = write_simple(stream, "500", "Internal Server Error", None);
        return Ok(false);
    }
    write_bytes(
        stream,
        "HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
    )?;
    Ok(false)
}

fn query_token(target: &str) -> String {
    target
        .split('?')
        .nth(1)
        .unwrap_or("")
        .split('&')
        .find_map(|kv| {
            let mut s = kv.splitn(2, '=');
            if s.next() == Some("t") {
                s.next().map(String::from)
            } else {
                None
            }
        })
        .unwrap_or_default()
}

fn send_listing(
    stream: &mut std::net::TcpStream,
    dir: &PathBuf,
    rel: &str,
    token: &str,
    keep_alive: bool,
) -> std::io::Result<bool> {
    let mut items: Vec<(bool, String)> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(dir) {
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().into_owned();
            if n.starts_with('.') {
                continue;
            }
            let is_dir = e.metadata().map(|m| m.is_dir()).unwrap_or(false);
            items.push((is_dir, n));
        }
    }
    items.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));

    let mut html = String::from(
        "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">\
         <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\
         <title>文件桥 · 原生服务</title>\
         <style>body{font-family:system-ui,sans-serif;margin:24px;color:#222}\
         ul{list-style:none;padding:0}li{padding:6px 4px;border-bottom:1px solid #eee}\
         a{text-decoration:none;color:#0b57d0;font-size:15px}.up{color:#888}</style>\
         </head><body><h3>文件桥 · Rust 原生服务（高速）</h3><ul>",
    );

    if !rel.is_empty() {
        let mut comps: Vec<&str> = rel.split('/').collect();
        comps.pop();
        let up = comps.join("/");
        html.push_str(&format!(
            "<li>← <a class=\"up\" href=\"/{up}?t={tk}\">上级目录</a></li>",
            up = enc(&up),
            tk = token
        ));
    }

    for (is_dir, name) in &items {
        let child = if rel.is_empty() {
            name.clone()
        } else {
            format!("{rel}/{name}")
        };
        let sym = if *is_dir { "📁" } else { "📄" };
        html.push_str(&format!(
            "<li><a href=\"/{c}?t={tk}\">{sym} {n}</a></li>",
            c = enc(&child),
            tk = token,
            n = esc(name)
        ));
    }
    html.push_str("</ul></body></html>");

    let conn = if keep_alive { "keep-alive" } else { "close" };
    write_bytes(
        stream,
        &format!(
            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: {}\r\n\r\n{}",
            html.len(),
            conn,
            html
        ),
    )?;
    Ok(keep_alive)
}

/// Percent-encode a URL path segment, keeping '/' separators intact.
fn enc(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = String::with_capacity(bytes.len());
    for &b in bytes {
        if b == b'/' {
            out.push('/');
        } else if b.is_ascii_alphanumeric() || b == b'-' || b == b'_' || b == b'.' {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{:02X}", b));
        }
    }
    out
}

fn esc(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

fn send_file(
    stream: &mut std::net::TcpStream,
    path: &PathBuf,
    head_only: bool,
    range: Option<(u64, u64)>,
    keep_alive: bool,
) -> std::io::Result<bool> {
    let mut file = match std::fs::File::open(path) {
        Ok(f) => f,
        Err(_) => {
            let _ = write_simple(stream, "404", "Not Found", None);
            return Ok(false);
        }
    };
    let size = file.metadata().map(|m| m.len()).unwrap_or(0);
    let (start, end, status, reason) = single_range(size, range);

    if range.is_some() && status == 416 {
        let _ = write_bytes(
            stream,
            &format!(
                "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{}\r\nContent-Length: 0\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n",
                size
            ),
        );
        return Ok(false);
    }
    if start >= size && size == 0 {
        let _ = write_simple(stream, "404", "Not Found", None);
        return Ok(false);
    }

    let len = if start <= end && start < size { end - start + 1 } else { 0 };
    let ct = mime_for(path);
    let mut head = format!("HTTP/1.1 {} {}\r\n", status, reason);
    head.push_str(&format!("Content-Type: {}\r\n", ct));
    head.push_str(&format!("Content-Length: {}\r\n", len));
    head.push_str("Accept-Ranges: bytes\r\n");
    if !range.is_none() && status == 206 {
        head.push_str(&format!("Content-Range: bytes {}-{}/{}\r\n", start, end, size));
    }
    head.push_str(if keep_alive { "Connection: keep-alive\r\n" } else { "Connection: close\r\n" });
    head.push_str("\r\n");

    let mut head_bytes = head.into_bytes();
    // prune dropped Content-Range for plain 200
    if range.is_none() || status == 200 {
        let cr = b"\r\nContent-Range:";
        if let Some(i) = find_sub(&head_bytes, cr) {
            if let Some(ei) = find_sub(&head_bytes[i..], b"\r\n") {
                head_bytes.drain(i..i + ei);
            }
        }
    }
    stream.write_all(&head_bytes)?;

    if head_only {
        stream.flush()?;
        return Ok(keep_alive);
    }
    if len > 0 {
        file.seek(SeekFrom::Start(start))?;
        let mut left = len;
        let mut buf = vec![0u8; IO_BUF];
        while left > 0 {
            let want = std::cmp::min(left as usize, IO_BUF);
            let n = file.read(&mut buf[..want])?;
            if n == 0 {
                break;
            }
            stream.write_all(&buf[..n])?;
            left -= n as u64;
        }
    }
    stream.flush()?;
    Ok(keep_alive)
}

/// total file size + optional request range -> (start, end, status, reason)
fn single_range(size: u64, range: Option<(u64, u64)>) -> (u64, u64, u16, &'static str) {
    match range {
        None => (0, size.saturating_sub(1), 200, "OK"),
        Some((s, e)) => {
            if s >= size || (e != u64::MAX && s > e) {
                return (0, 0, 416, "Range Not Satisfiable");
            }
            let end = if e == u64::MAX { size - 1 } else { e.min(size - 1) };
            (s, end, 206, "Partial Content")
        }
    }
}

fn parse_range(h: Option<&String>) -> Option<(u64, u64)> {
    let v = h?;
    let v = v.trim_start();
    let suffix = v.strip_prefix("bytes=")?;
    // suffix form bytes=-N is not handled: bail out -> server replies full 200
    if suffix.starts_with('-') && !suffix.starts_with("-0") {
        return None;
    }
    let mut it = suffix.splitn(2, '-');
    let s: u64 = it.next()?.trim().parse().ok()?;
    let e = match it.next() {
        Some(x) if x.trim().is_empty() => u64::MAX,
        Some(x) => x.trim().parse().ok()?,
        None => u64::MAX,
    };
    Some((s, e))
}

fn safe_path(root: &str, rel: &str) -> Option<PathBuf> {
    if rel.is_empty() {
        return Some(PathBuf::from(root));
    }
    if rel.starts_with('/') {
        return None;
    }
    let mut p = PathBuf::from(root);
    for comp in rel.split('/') {
        if comp == ".." || comp.is_empty() || comp.contains('\\') {
            return None;
        }
        p.push(comp);
    }
    if !p.starts_with(root) {
        return None;
    }
    Some(p)
}

fn decode_path(target: &str) -> String {
    let raw = target.split('?').next().unwrap_or(target).trim_start_matches('/');
    percent_decode(raw)
}

fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let (Some(h), Some(l)) = (hex(bytes[i + 1]), hex(bytes[i + 2])) {
                out.push(h << 4 | l);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

fn header<'a>(h: &'a [(String, String)], name: &str) -> Option<&'a String> {
    h.iter().find(|(k, _)| k == name).map(|(_, v)| v)
}

fn find_headers_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n")
}

fn find_sub(hay: &[u8], needle: &[u8]) -> Option<usize> {
    hay.windows(needle.len()).position(|w| w == needle)
}

fn write_simple(stream: &mut std::net::TcpStream, status: &str, reason: &str, body: Option<&str>) -> std::io::Result<()> {
    let b = body.unwrap_or(reason);
    write_bytes(
        stream,
        &format!(
            "HTTP/1.1 {} {}\r\nContent-Type: text/plain\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
            status,
            reason,
            b.len(),
            b
        ),
    )
}

fn write_bad(stream: &mut std::net::TcpStream) -> std::io::Result<bool> {
    let _ = write_simple(stream, "400", "Bad Request", None);
    Ok(false)
}

fn write_bytes(stream: &mut std::net::TcpStream, s: &str) -> std::io::Result<()> {
    stream.write_all(s.as_bytes())?;
    stream.flush()
}

fn mime_for(p: &PathBuf) -> &'static str {
    match p.extension().and_then(|e| e.to_str()).map(|e| e.to_ascii_lowercase()) {
        Some(ref e) => match e.as_str() {
            "html" | "htm" => "text/html",
            "txt" | "log" | "md" | "csv" => "text/plain",
            "json" | "xml" => "application/json",
            "png" => "image/png",
            "jpg" | "jpeg" => "image/jpeg",
            "gif" => "image/gif",
            "webp" => "image/webp",
            "svg" => "image/svg+xml",
            "mp4" => "video/mp4",
            "mkv" => "video/x-matroska",
            "mp3" => "audio/mpeg",
            "wav" => "audio/x-wav",
            "pdf" => "application/pdf",
            "zip" => "application/zip",
            "gz" => "application/gzip",
            "apk" => "application/vnd.android.package-archive",
            _ => "application/octet-stream",
        },
        None => "application/octet-stream",
    }
}
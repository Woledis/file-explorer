//! FileBridge Flutter Rust core —— C ABI export for dart:ffi.
//!
//! M2: 在 M1 的字符串 demo 基础上，把一个真实 HTTP 文件服务引擎经 C ABI
//! 暴露给 Flutter：fb_engine_start / fb_engine_stop / fb_engine_is_running。
//! 引擎在后台线程运行，具备目录列表 + 流式下载(MIME/进度) 能力。

use std::ffi::{CStr, CString};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::os::raw::{c_char, c_int};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

const VERSION: &str = "filebridge-core 0.2.0";
const IO_BUF: usize = 64 * 1024;

static RUNNING: AtomicBool = AtomicBool::new(false);
static STOP: AtomicBool = AtomicBool::new(false);

// ------------------------------------------------------------------ strings

#[no_mangle]
pub extern "C" fn fb_version_string() -> *mut c_char {
    cstr(VERSION)
}

#[no_mangle]
pub extern "C" fn fb_greet(name: *const c_char) -> *mut c_char {
    if name.is_null() {
        return cstr("hello");
    }
    let n = unsafe { CStr::from_ptr(name) }.to_string_lossy().into_owned();
    cstr(&format!("hello, {n}"))
}

#[no_mangle]
pub extern "C" fn fb_free_string(p: *mut c_char) {
    if !p.is_null() {
        unsafe { drop(CString::from_raw(p)); }
    }
}

fn cstr(s: &str) -> *mut c_char {
    CString::new(s).unwrap_or_else(|_| CString::new("").unwrap()).into_raw()
}

// ------------------------------------------------------------------ engine

fn os_to_int(b: bool) -> c_int {
    if b { 1 } else { 0 }
}

/// root: 允许访问的物理根目录(UTF-8), 如 /storage/emulated/0
/// port: 监听端口(0=自动选)
/// On success stores listener port into *out_port(非空时)。
/// Returns 1 on success, 0 on failure。
#[no_mangle]
pub extern "C" fn fb_engine_start(
    root: *const c_char,
    port: c_int,
    out_port: *mut c_int,
) -> c_int {
    if RUNNING.load(Ordering::SeqCst) {
        return 0;
    }
    let root = if root.is_null() {
        String::from("/storage/emulated/0")
    } else {
        unsafe { CStr::from_ptr(root) }.to_string_lossy().into_owned()
    };
    let listener = match TcpListener::bind(("0.0.0.0", port.max(0) as u16)) {
        Ok(l) => l,
        Err(_) => return 0,
    };
    let actual = match listener.local_addr() {
        Ok(a) => a.port(),
        Err(_) => return 0,
    };
    if !out_port.is_null() {
        unsafe { *out_port = actual as c_int; }
    }
    STOP.store(false, Ordering::SeqCst);
    RUNNING.store(true, Ordering::SeqCst);

    let root = Arc::new(root);
    std::thread::spawn(move || {
        let _ = run_loop(listener, root);
        RUNNING.store(false, Ordering::SeqCst);
    });
    1
}

#[no_mangle]
pub extern "C" fn fb_engine_stop() -> c_int {
    STOP.store(true, Ordering::SeqCst);
    1
}

#[no_mangle]
pub extern "C" fn fb_engine_is_running() -> c_int {
    os_to_int(RUNNING.load(Ordering::SeqCst))
}

fn run_loop(listener: TcpListener, root: Arc<String>) -> std::io::Result<()> {
    for conn in listener.incoming() {
        if STOP.load(Ordering::SeqCst) {
            break;
        }
        if let Ok(stream) = conn {
            let root = Arc::clone(&root);
            std::thread::spawn(move || {
                let _ = handle_conn(stream, &root);
            });
        }
    }
    Ok(())
}

fn handle_conn(mut stream: TcpStream, root: &str) -> std::io::Result<()> {
    let mut buf = Vec::with_capacity(8192);
    let mut tmp = [0u8; 2048];
    loop {
        let n = stream.read(&mut tmp)?;
        if n == 0 {
            break;
        }
        buf.extend_from_slice(&tmp[..n]);
        if let Some(hd_end) = find_sub(&buf, b"\r\n\r\n") {
            let head = String::from_utf8_lossy(&buf[..hd_end]).into_owned();
            let keep = dispatch(&head, &mut stream, root)?;
            buf.drain(..hd_end + 4);
            buf.clear();
            if !keep {
                break;
            }
        }
    }
    Ok(())
}

fn dispatch(head: &str, stream: &mut TcpStream, root: &str) -> std::io::Result<bool> {
    let mut lines = head.lines();
    let request_line = match lines.next() {
        Some(l) => l,
        None => return write_bad(stream),
    };
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("");
    let target = parts.next().unwrap_or("");
    let keep = parts.any(|v| v.contains("1.1"));

    let path_str = decode_path(target);
    if path_str.contains("..") {
        return write_status(stream, 404, "Not Found", keep);
    }

    if method == "GET" || method == "HEAD" {
        let path = make_path(root, &path_str);
        if let Ok(md) = std::fs::metadata(&path) {
            if md.is_dir() {
                return write_listing(stream, &path, &path_str, keep);
            } else {
                return stream_file(stream, &path, &md, method == "HEAD", keep);
            }
        }
        return write_status(stream, 404, "Not Found", keep);
    }
    write_status(stream, 405, "Method Not Allowed", keep)
}

fn make_path(root: &str, rel: &str) -> PathBuf {
    PathBuf::from(root).join(Path::new(rel.trim_start_matches('/')))
}

fn write_listing(stream: &mut TcpStream, dir: &Path, rel: &str, keep: bool) -> std::io::Result<bool> {
    let mut items: Vec<(bool, String)> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(dir) {
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().into_owned();
            if n.starts_with('.') { continue; }
            let is_dir = e.metadata().map(|m| m.is_dir()).unwrap_or(false);
            items.push((is_dir, n));
        }
    }
    items.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));

    let mut html = String::from(
        "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">\
         <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\
         <title>FileBridge</title>\
         <style>body{font-family:system-ui,sans-serif;margin:24px;color:#222}\
         ul{list-style:none;padding:0}li{padding:6px 4px;border-bottom:1px solid #eee}\
         a{text-decoration:none;color:#0b57d0;font-size:15px}</style></head><body>\
         <h3>FileBridge · Rust</h3><ul>",
    );
    if !rel.is_empty() {
        let mut comps: Vec<&str> = rel.trim_end_matches('/').split('/').collect();
        comps.pop();
        html.push_str(&format!("<li>…<a href=\"/{}\">上级</a></li>", comps.join("/")));
    }
    for (is_dir, name) in &items {
        let child = if rel.is_empty() { name.clone() } else { format!("{}/{}", rel.trim_matches('/'), name) };
        let sym = if *is_dir { "📁" } else { "📄" };
        html.push_str(&format!("<li><a href=\"/{}\">{sym} {}</a></li>", enc(&child), esc(name)));
    }
    html.push_str("</ul></body></html>");
    let body = html;
    let status = if keep { "HTTP/1.1 200 OK\r\nConnection: keep-alive\r\n" } else { "HTTP/1.1 200 OK\r\nConnection: close\r\n" };
    write_bytes(stream, &format!("{}Content-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\n\r\n{}", status, body.len(), body))
}

fn stream_file(stream: &mut TcpStream, path: &Path, md: &std::fs::Metadata, head: bool, keep: bool) -> std::io::Result<bool> {
    let len = md.len();
    let ct = mime(path);
    let conn = if keep { "keep-alive" } else { "close" };
    write_bytes(stream, &format!("HTTP/1.1 200 OK\r\nContent-Type: {}\r\nContent-Length: {}\r\nAccept-Ranges: bytes\r\nConnection: {}\r\n\r\n", ct, len, conn))?;
    if head {
        stream.flush()?;
        return Ok(keep);
    }
    let mut f = std::fs::File::open(path)?;
    let mut buf = vec![0u8; IO_BUF];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 { break; }
        stream.write_all(&buf[..n])?;
    }
    stream.flush()?;
    Ok(keep)
}

fn write_status(stream: &mut TcpStream, code: u16, reason: &str, keep: bool) -> std::io::Result<bool> {
    let conn = if keep { "keep-alive" } else { "close" };
    write_bytes(stream, &format!("HTTP/1.1 {} {}\r\nContent-Length: 0\r\nConnection: {}\r\n\r\n", code, reason, conn))
}

fn write_bad(stream: &mut TcpStream) -> std::io::Result<bool> {
    write_status(stream, 400, "Bad Request", false)
}

fn write_bytes(stream: &mut TcpStream, s: &str) -> std::io::Result<()> {
    stream.write_all(s.as_bytes())?;
    stream.flush()
}

fn enc(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = String::new();
    for &b in bytes {
        if b == b'/' { out.push('/'); }
        else if b.is_ascii_alphanumeric() || b == b'-' || b == b'_' || b == b'.' { out.push(b as char); }
        else { out.push_str(&format!("%{:02X}", b)); }
    }
    out
}

fn esc(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

fn decode_path(target: &str) -> String {
    let raw = target.split('?').next().unwrap_or(target);
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

fn find_sub(hay: &[u8], needle: &[u8]) -> Option<usize> {
    hay.windows(needle.len()).position(|w| w == needle)
}

fn mime(p: &Path) -> &'static str {
    match p.extension().and_then(|e| e.to_str()).map(|e| e.to_ascii_lowercase()) {
        Some(ref e) => match e.as_str() {
            "html" | "htm" => "text/html",
            "txt" | "log" | "md" | "csv" => "text/plain",
            "png" | "jpg" | "jpeg" | "gif" | "webp" | "svg" => "image/octet",
            "mp4" | "mkv" | "mp3" | "wav" => "media/octet",
            "pdf" => "application/pdf",
            "zip" | "gz" | "apk" => "application/octet-stream",
            _ => "application/octet-stream",
        },
        None => "application/octet-stream",
    }
}

#[no_mangle]
pub extern "C" fn fb_ping() -> u32 { 0xFB02 }
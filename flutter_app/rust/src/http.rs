//! 纯 std HTTP 文件引擎。
//!
//! 支持: GET/HEAD 下载(Range 断点续传)、目录 HTML/JSON 列表、登录表单 + cookie
//! 会话。PUT 上传由本模块提供主体流式写入(read 侧在连接层)。所有文件访问以
//! root 为锚, 路径防穿越。

use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::net::TcpStream;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use crate::auth;
use crate::settings;

pub const IO_BUF: usize = 64 * 1024;

pub struct Request {
    pub method: String,
    pub target: String,
    pub version: String,
    pub range: Option<(u64, u64)>,
    cookies: Option<String>,
    pub content_length: usize,
}

pub fn handle_conn(mut stream: TcpStream, root: Arc<String>) {
    let read_stream = match stream.try_clone() {
        Ok(s) => s,
        Err(_) => return,
    };
    let mut reader = BufReader::new(read_stream);
    'outer: loop {
        let mut head_lines: Vec<String> = Vec::new();
        loop {
            let mut line = String::new();
            if reader.read_line(&mut line).unwrap_or(0) == 0 {
                break 'outer;
            }
            let line = line.trim_end().to_string();
            if line.is_empty() {
                break;
            }
            head_lines.push(line);
        }
        if head_lines.is_empty() {
            break;
        }
        let req = parse_head(&head_lines);
        let keep = req.version.contains("1.1");

        if req.method == "POST" {
            let cl = req.content_length;
            let mut body = vec![0u8; cl];
            let mut read = 0;
            while read < body.len() {
                let n = reader.read(&mut body[read..]).unwrap_or(0);
                if n == 0 {
                    break;
                }
                read += n;
            }
            serve_login(&mut stream, &body, keep);
            if !keep {
                break;
            }
            continue 'outer;
        }

        let authed = settings::get_password().is_empty() || session_ok(&req);
        if !authed {
            skip_body(&mut reader, req.content_length);
            if req.method == "GET" && target_is_login(&req.target) {
                serve_login_form(&mut stream, keep);
            } else {
                serve_auth_required(&mut stream, keep);
            }
            if !keep {
                break;
            }
            continue;
        }

        let keep_next = serve(&mut stream, Some(&mut reader), &req, &root, keep);
        if !keep_next {
            break;
        }
    }
}

fn parse_head(lines: &[String]) -> Request {
    let mut req = Request {
        method: String::new(),
        target: String::new(),
        version: String::new(),
        range: None,
        cookies: None,
        content_length: 0,
    };
    if let Some(first) = lines.first() {
        let mut it = first.split_whitespace();
        if let Some(m) = it.next() {
            req.method = m.to_string();
        }
        if let Some(t) = it.next() {
            req.target = t.to_string();
        }
        if let Some(v) = it.next() {
            req.version = v.to_string();
        }
    }
    for l in lines.iter().skip(1) {
        if let Some(sep) = l.find(':') {
            let k = &l[..sep];
            let v = &l[sep + 1..];
            let k = k.trim().to_ascii_lowercase();
            match k.as_str() {
                "cookie" => req.cookies = Some(v.trim().to_string()),
                "content-length" => req.content_length = v.trim().parse().unwrap_or(0),
                "range" => req.range = parse_range(Some(v.trim())),
                _ => {}
            }
        }
    }
    req
}

fn session_ok(req: &Request) -> bool {
    auth::session_from_cookie(req.cookies.as_deref()).is_some()
}

fn serve_login(w: &mut impl Write, body: &[u8], _keep: bool) {
    let body = String::from_utf8_lossy(body);
    let pw = match field(&body, "pw") {
        Some(p) => p,
        None => {
            redirect(w, "/login?e=1");
            return;
        }
    };
    match auth::try_login(&pw) {
        Some(tok) => {
            let hdr = format!("{}={}\r\n", auth::session_cookie_name(), tok);
            let _ = write!(
                w,
                "HTTP/1.1 303 See Other\r\nSet-Cookie: {}\r\nLocation: /\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                hdr.trim_end()
            );
        }
        None => redirect(w, "/login?e=1"),
    }
}

fn field(body: &str, name: &str) -> Option<String> {
    for kv in body.split('&') {
        if let Some(eq) = kv.find('=') {
            if kv[..eq] == *name {
                return Some(percent_decode(kv[eq + 1..].as_bytes()));
            }
        }
    }
    None
}

fn redirect(w: &mut impl Write, loc: &str) {
    let _ = write!(
        w,
        "HTTP/1.1 302 Found\r\nLocation: {}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        loc
    );
}

fn serve_auth_required(w: &mut impl Write, _keep: bool) {
    let _ = write_bytes(
        w,
        "HTTP/1.1 302 Found\r\nLocation: /login?e=1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
    );
}

fn target_is_login(target: &str) -> bool {
    let path = target.split('?').next().unwrap_or(target);
    path == "/login" || path == "/login/"
}

fn serve_login_form(w: &mut impl Write, keep: bool) -> bool {
    let body = auth::render_login();
    write_bytes_keep(
        w,
        &format!(
            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: {}\r\n\r\n{}",
            body.len(),
            conn(keep),
            body
        ),
        keep,
    )
}

fn skip_body(reader: &mut impl Read, n: usize) {
    let mut tmp = vec![0u8; 4096];
    let mut left = n;
    while left > 0 {
        match reader.read(&mut tmp[..left.min(4096)]) {
            Ok(0) | Err(_) => break,
            Ok(r) => left -= r,
        }
    }
}

/// Returns true if the connection may continue (keep-alive).
/// `reader` is passed for PUT body streaming, unused by GET/HEAD.
fn serve(
    wout: &mut impl Write,
    reader: Option<&mut BufReader<TcpStream>>,
    req: &Request,
    root: &str,
    keep: bool,
) -> bool {
    let path = match safe_path(root, &req.target) {
        Some(p) => p,
        None => {
            let _ = write_simple(wout, 404, "Not Found", None, keep);
            return true;
        }
    };

    match req.method.as_str() {
        "GET" | "HEAD" => serve_get(wout, req, &path, keep),
        "PUT" => match reader {
            Some(r) => serve_put(wout, r, req, &path, keep),
            None => {
                let _ = write_simple(wout, 500, "Internal Server Error", None, keep);
                true
            }
        },
        _ => {
            let _ = write_simple(wout, 405, "Method Not Allowed", None, keep);
            true
        }
    }
}

/// Streams the request body into `path` in IO_BUF chunks. Return value follows
/// serve()'s contract: true keeps the connection alive.
fn serve_put(
    w: &mut impl Write,
    reader: &mut BufReader<TcpStream>,
    req: &Request,
    path: &Path,
    keep: bool,
) -> bool {
    if let Some(parent) = path.parent() {
        if !parent.is_dir() && std::fs::create_dir_all(parent).is_err() {
            let _ = write_simple(w, 500, "Cannot create directory", None, keep);
            return keep;
        }
    }
    let mut file = match std::fs::File::create(path) {
        Ok(f) => f,
        Err(_) => {
            let _ = write_simple(w, 500, "Cannot write file", None, keep);
            return keep;
        }
    };
    let mut left = req.content_length;
    let mut buf = vec![0u8; IO_BUF];
    let mut written: u64 = 0;
    let mut ok = true;
    while left > 0 {
        let want = left.min(IO_BUF);
        match reader.read(&mut buf[..want]) {
            Ok(0) | Err(_) => {
                ok = false;
                break;
            }
            Ok(n) => {
                if file.write_all(&buf[..n]).is_err() {
                    ok = false;
                    break;
                }
                written += n as u64;
                left -= n;
            }
        }
    }
    let _ = file.flush();
    if ok {
        let header = format!(
            "HTTP/1.1 201 Created\r\nContent-Type: text/plain\r\nContent-Length: {}\r\nConnection: {}\r\n\r\nwrote {} bytes",
            written.to_string().len(),
            conn(keep),
            written
        );
        let _ = w.write_all(header.as_bytes());
        let _ = w.flush();
    } else {
        let _ = write_simple(w, 400, "Bad Request", None, keep);
    }
    keep
}

fn serve_get(w: &mut impl Write, req: &Request, path: &Path, keep: bool) -> bool {
    if let Ok(md) = std::fs::metadata(path) {
        if md.is_dir() {
            let json = req.target.split('?').nth(1).map_or(false, |q| q.split('&').any(|p| p == "format=json"));
            return serve_dir(w, path, req, json, keep);
        }
        return serve_file(w, path, &md, req, keep);
    }
    let _ = write_simple(w, 404, "Not Found", None, keep);
    true
}

fn serve_dir(w: &mut impl Write, path: &Path, req: &Request, json: bool, keep: bool) -> bool {
    let rel = decode_target(&req.target);
    let mut items: Vec<(bool, String)> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(path) {
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

    if json {
        let mut body = String::from("[");
        for (i, (is_dir, n)) in items.iter().enumerate() {
            if i > 0 {
                body.push(',');
            }
            let t = if *is_dir { "d" } else { "f" };
            body.push_str(&format!("[\"{}\", \"{}\"]", t, esc(n)));
        }
        body.push(']');
        return write_bytes_keep(
            w,
            &format!("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: {}\r\n\r\n{}", body.len(), conn(keep), body),
            keep,
        );
    }

    let mut html = format!(
        "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">\
         <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\
         <title>FileBridge</title>\
         <style>body{font-family:system-ui,sans-serif;margin:20px;color:#222}\
         .up{margin:10px 0}.up input{max-width:60%}.up button{padding:6px 14px;margin-left:8px;border:0;border-radius:6px;background:#3949ab;color:#fff;cursor:pointer}\
         ul{list-style:none;padding:0}li{padding:6px 2px;border-bottom:1px solid #eee}\
         a{text-decoration:none;color:#0b57d0;font-size:15px}</style></head><body><h3>📁 FileBridge /{}</h3>\
         <div class=\"up\"><input type=\"file\" id=\"f\"><button onclick=\"up()\">上传到此目录</button></div>\
         <script>async function up(){const f=document.getElementById('f').files[0];if(!f)return;const r=await fetch(location.pathname+encodeURIComponent(f.name),{method:'PUT',body:f});if(r.ok)location.reload();else alert('上传失败 '+(r.status||'网络错误'));}</script>\
         <ul>",
        esc(&rel),
    );
    let up = parent_path(&rel);
    if !up.is_empty() {
        html.push_str(&format!("<li><a href=\"/{}\">…上级目录</a></li>", enc_path(&up)));
    }
    for (is_dir, n) in &items {
        let child = if rel.is_empty() { n.clone() } else { format!("{}/{}", rel.trim_matches('/'), n) };
        let sym = if *is_dir { "📁" } else { "📄" };
        html.push_str(&format!("<li><a href=\"/{}\">{sym} {}</a></li>", enc_path(&child), esc(n)));
    }
    html.push_str("</ul></body></html>");
    let body = html;
    write_bytes_keep(
        w,
        &format!("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: {}\r\n\r\n{}", body.len(), conn(keep), body),
        keep,
    )
}

fn serve_file(w: &mut impl Write, path: &Path, md: &std::fs::Metadata, req: &Request, keep: bool) -> bool {
    let size = md.len();
    let head_only = req.method == "HEAD";
    let (start, end, code) = single_range(size, req.range);
    if code == 416 {
        let _ = write_bytes(
            w,
            &format!("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{}\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n", size),
        );
        return true;
    }
    let len = if start <= end && start < size { end - start + 1 } else { 0 };
    let ct = mime(path);
    let mut head = format!(
        "HTTP/1.1 {} {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nAccept-Ranges: bytes\r\n",
        code,
        if code == 206 { "Partial Content" } else { "OK" },
        ct,
        len
    );
    if code == 206 {
        head.push_str(&format!("Content-Range: bytes {}-{}/{}\r\n", start, end, size));
    }
    head.push_str(&format!("Connection: {}\r\n\r\n", conn(keep)));
    let _ = w.write_all(head.as_bytes());
    let _ = w.flush();
    if head_only {
        return keep;
    }
    if len > 0 {
        if let Ok(mut f) = std::fs::File::open(path) {
            let _ = f.seek(SeekFrom::Start(start));
            let mut buf = vec![0u8; IO_BUF];
            let mut left = len;
            while left > 0 {
                let want = left.min(IO_BUF as u64) as usize;
                match f.read(&mut buf[..want]) {
                    Ok(0) => break,
                    Ok(n) => {
                        if w.write_all(&buf[..n]).is_err() {
                            break;
                        }
                        left -= n as u64;
                        let _ = w.flush();
                    }
                    Err(_) => break,
                }
            }
        }
    }
    let _ = w.flush();
    keep
}

fn conn(keep: bool) -> &'static str {
    if keep { "keep-alive" } else { "close" }
}

fn write_simple(w: &mut impl Write, code: u16, reason: &str, body: Option<&str>, keep: bool) -> std::io::Result<()> {
    let b = body.unwrap_or(reason);
    let msg = format!(
        "HTTP/1.1 {} {}\r\nContent-Type: text/plain\r\nContent-Length: {}\r\nConnection: {}\r\n\r\n{}",
        code, reason, b.len(), conn(keep), b
    );
    w.write_all(msg.as_bytes())?;
    w.flush()
}

fn write_bytes(w: &mut impl Write, s: &str) -> std::io::Result<()> {
    w.write_all(s.as_bytes())?;
    w.flush()
}

fn write_bytes_keep(w: &mut impl Write, s: &str, keep: bool) -> bool {
    let _ = write_bytes(w, s);
    keep
}

fn safe_path(root: &str, target: &str) -> Option<PathBuf> {
    let raw = target.split('?').next().unwrap_or(target);
    if raw == "/" {
        return Some(PathBuf::from(root));
    }
    let rel = raw.trim_start_matches('/');
    let decoded = percent_decode(rel.as_bytes());
    if decoded.starts_with('/') || decoded.contains("..") || decoded.contains('\\') {
        return None;
    }
    let mut p = PathBuf::from(root);
    for comp in decoded.split('/') {
        if comp == ".." || comp.is_empty() {
            return None;
        }
        p.push(comp);
    }
    if !p.starts_with(root) {
        return None;
    }
    Some(p)
}

fn decode_target(target: &str) -> String {
    let raw = target.split('?').next().unwrap_or(target);
    percent_decode(raw.as_bytes())
}

fn parent_path(rel: &str) -> String {
    let r = rel.trim_matches('/');
    if r.is_empty() {
        return String::new();
    }
    let mut comps: Vec<&str> = r.split('/').collect();
    comps.pop();
    comps.join("/")
}

fn enc_path(s: &str) -> String {
    let mut out = String::new();
    for b in s.as_bytes() {
        match b {
            b'/' => out.push('/'),
            b if b.is_ascii_alphanumeric() || *b == b'-' || *b == b'_' || *b == b'.' => out.push(*b as char),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

fn esc(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

fn single_range(size: u64, range: Option<(u64, u64)>) -> (u64, u64, u16) {
    match range {
        None => (0, size.saturating_sub(1), 200),
        Some((s, e)) => {
            if s >= size || (e != u64::MAX && s > e) {
                return (0, 0, 416);
            }
            let end = if e == u64::MAX { size - 1 } else { e.min(size - 1) };
            (s, end, 206)
        }
    }
}

fn parse_range(h: Option<&str>) -> Option<(u64, u64)> {
    let v = h?;
    let suffix = v.trim().strip_prefix("bytes=")?;
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

fn percent_decode(bytes: &[u8]) -> String {
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

fn mime(p: &Path) -> &'static str {
    match p.extension().and_then(|e| e.to_str()).map(|e| e.to_ascii_lowercase()) {
        Some(ref e) => match e.as_str() {
            "html" | "htm" => "text/html",
            "txt" | "log" | "md" | "csv" => "text/plain",
            "json" | "xml" => "application/json",
            "png" | "jpg" | "jpeg" | "gif" | "webp" | "svg" => "application/octet-stream",
            "mp4" | "mkv" | "mp3" | "wav" => "application/octet-stream",
            "pdf" => "application/pdf",
            "apk" => "application/vnd.android.package-archive",
            _ => "application/octet-stream",
        },
        None => "application/octet-stream",
    }
}
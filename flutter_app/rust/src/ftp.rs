//! 极简 FTP server(RFC 959 子集), 纯 std 实现。
//!
//! 局域网文件传输的第二条通道: 控制连接固定监听, 数据连接用 PASV/EPSV 被动模式。
//! 支持登录(复用访问口令)、PWD/CWD/CDUP、LIST/NLST、RETR(下载/REST 断点)、
//! STOR(上传/REST)、SIZE/MDTM、DELE/MKD/RMD/RNFR+RNTO、TYPE、特征协商。
//! 所有文件操作限制在 root 内, 路径做规范化防止目录穿越。

use std::io::{BufRead, BufReader, BufWriter, Seek, SeekFrom, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

use crate::settings;

use std::io;

pub const DEFAULT_PORT: u16 = 2121;
const MAX_CONN: usize = 8;

static STOP: AtomicBool = AtomicBool::new(false);

pub fn request_stop() {
    STOP.store(true, Ordering::SeqCst);
}

/// 阻塞运行 FTP 服务, 直到 [request_stop] 被调用。返回 () 表示正常退出/绑定失败。
pub fn serve(root: &str, port: u16) -> io::Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", port))?;
    serve_on(listener, root)
}

/// 在已绑定的监听器上运行, 便于调用方读取实际端口。STOP 标志在进来时复位。
pub fn serve_on(listener: TcpListener, root: &str) -> io::Result<()> {
    STOP.store(false, SeqCst);
    let root = String::from(root.trim_end_matches('/'));
    let root = std::sync::Arc::new(root);
    let active = std::sync::Arc::new(AtomicUsize::new(0));
    // 非阻塞轮询, 让 request_stop() 在 100ms 内生效(见 lib.rs 同款注释)
    listener.set_nonblocking(true).ok();
    loop {
        if STOP.load(Ordering::SeqCst) {
            break;
        }
        match listener.accept() {
            Ok((stream, _)) => {
                stream.set_nonblocking(false).ok();
                if active.load(Ordering::Relaxed) >= MAX_CONN {
                    // 并发连接超限: 快速拒绝再进入的会话, 防线程失控堆积
                    let _ = write!(&mut &stream, "421 Too many connections, try later\r\n");
                    continue;
                }
                let root = std::sync::Arc::clone(&root);
                let active = std::sync::Arc::clone(&active);
                std::thread::spawn(move || {
                    active.fetch_add(1, Ordering::Relaxed);
                    let _ = handle(stream, &root);
                    active.fetch_sub(1, Ordering::Relaxed);
                });
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
            Err(_) => {
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
        }
    }
    Ok(())
}

// ---------------------------------------------------------------- connection

fn handle(stream: TcpStream, root: &str) -> io::Result<()> {
    let mut stream = stream;
    // 控制连接空闲超时: 5s 会把正常空闲的会话(FileZilla 保活/用户思考)误杀,
    // 提到 300s; 真正的死连接由 accept 侧与 TCP 自身回收。
    stream
        .set_read_timeout(Some(std::time::Duration::from_secs(300)))
        .ok();
    // PASV 应答里的 IP 必须是客户端实际连进来的网卡地址(通常是 Wi-Fi),
    // 否则双网络(流量+Wi-Fi)时会广播流量网卡 IP, 数据连接连不上。
    let ctrl_ip = stream
        .local_addr()
        .map(|a| a.ip().to_string())
        .unwrap_or_else(local_ip);
    let read_stream = match stream.try_clone() {
        Ok(s) => s,
        Err(_) => return Ok(()),
    };
    let mut reader = BufReader::new(read_stream);

    let mut cwd = String::from("/");
    let mut data: Option<TcpListener> = None;
    let mut rest: u64 = 0;
    let mut rnfr: Option<String> = None;

    reply(&mut stream, "220 FileBridge FTP ready")?;

    'outer: loop {
        let mut line = String::new();
        if reader.read_line(&mut line).unwrap_or(0) == 0 {
            break;
        }
        let line = line.trim_end_matches(['\r', '\n']).trim().to_string();
        if line.is_empty() {
            continue;
        }
        let (cmd, arg) = match line.find(' ') {
            Some(i) => (line[..i].to_uppercase(), line[i + 1..].trim().to_string()),
            None => (line.to_uppercase(), String::new()),
        };

        let err = match cmd.as_str() {
            "QUIT" => {
                reply(&mut stream, "221 Goodbye")?;
                break 'outer;
            }
            "USER" => {
                reply(&mut stream, "331 Password required")?;
                None
            }
            "PASS" => {
                // 已设口令时必须校验; 未设口令则放行。修复: 旧逻辑空 arg 直接 true,
                // 属于设了口令也能匿名登录的漏洞。
                let ok = settings::get_password().is_empty() || settings::verify(&arg);
                if ok {
                    reply(&mut stream, "230 Logged in")?;
                } else {
                    reply(&mut stream, "530 Login incorrect")?;
                }
                None
            }
            "SYST" => {
                reply(&mut stream, "215 UNIX Type: L8")?;
                None
            }
            "FEAT" => {
                send(&mut stream,
                    "211-Features:\r\nSIZE\r\nMDTM\r\nREST STREAM\r\nEPSV\r\nUTF8\r\nTVFS\r\n211 End",
                )?;
                None
            }
            "OPTS" => {
                reply(&mut stream, "200 OK")?;
                None
            }
            "NOOP" => {
                reply(&mut stream, "200 OK")?;
                None
            }
            "TYPE" => {
                reply(&mut stream, "200 Type set")?;
                None
            }
            "PWD" | "XPWD" => {
                reply(&mut stream, &format!("257 \"{cwd}\""))?;
                None
            }
            "CWD" => {
                let new = join_arg(&cwd, &arg);
                if let Some(abs) = to_abs(root, &new) {
                    if Path::new(&abs).is_dir() {
                        cwd = new;
                        reply(&mut stream, "250 OK")?;
                    } else {
                        reply(&mut stream, "550 No such directory")?;
                    }
                } else {
                    reply(&mut stream, "550 Invalid path")?;
                }
                None
            }
            "CDUP" => {
                let new = norm_path(&format!("{}/..", cwd));
                if let Some(abs) = to_abs(root, &new) {
                    if Path::new(&abs).is_dir() {
                        cwd = norm_path(&new);
                        reply(&mut stream, "250 OK")?;
                    } else {
                        reply(&mut stream, "550 No such directory")?;
                    }
                } else {
                    reply(&mut stream, "550 Invalid path")?;
                }
                None
            }
            "PASV" => {
                if let Some(l) = data.take() {
                    let _ = l;
                }
                match TcpListener::bind(("0.0.0.0", 0)) {
                    Ok(l) => {
                        let port = l.local_addr().map(|a| a.port()).unwrap_or(0);
                        let ip = ctrl_ip.clone();
                        let nums: Vec<&str> = ip.split('.').collect();
                        if nums.len() != 4 {
                            reply(&mut stream, "425 Cannot open data connection")?;
                        } else {
                            let p1 = port / 256;
                            let p2 = port % 256;
                            reply(
                                &mut stream,
                                &format!(
                                    "227 Entering Passive Mode ({},{},{},{},{},{})",
                                    nums[0], nums[1], nums[2], nums[3], p1, p2
                                ),
                            )?;
                            data = Some(l);
                        }
                    }
                    Err(_) => reply(&mut stream, "425 Cannot open data connection")?,
                }
                None
            }
            "EPSV" => {
                if let Some(l) = data.take() {
                    let _ = l;
                }
                match TcpListener::bind(("0.0.0.0", 0)) {
                    Ok(l) => {
                        let port = l.local_addr().map(|a| a.port()).unwrap_or(0);
                        reply(
                            &mut stream,
                            &format!("229 Entering Extended Passive Mode (|||{port}|)"),
                        )?;
                        data = Some(l);
                    }
                    Err(_) => reply(&mut stream, "425 Cannot open data connection")?,
                }
                None
            }
            "LIST" | "NLST" => {
                if data.is_none() {
                    reply(&mut stream, "425 Use PASV/EPSV first")?;
                    None
                } else {
                    list_dir(&mut stream, data.take(), root, &cwd, &arg, cmd == "NLST")
                }
            }
            "RETR" => {
                if data.is_none() {
                    reply(&mut stream, "425 Use PASV/EPSV first")?;
                    None
                } else {
                    retrieve(&mut stream, data.take(), root, &cwd, &arg, rest)
                }
            }
            "STOR" => {
                if data.is_none() {
                    reply(&mut stream, "425 Use PASV/EPSV first")?;
                    None
                } else {
                    store(&mut stream, data.take(), root, &cwd, &arg, rest)
                }
            }
            "REST" => {
                rest = arg.parse::<u64>().unwrap_or(0);
                reply(&mut stream, "350 Restart position accepted")?;
                None
            }
            "SIZE" => {
                size_of(&mut stream, root, &cwd, &arg)
            }
            "MDTM" => {
                mdtm_of(&mut stream, root, &cwd, &arg)
            }
            "DELE" => {
                del(&mut stream, root, &cwd, &arg)
            }
            "MKD" | "XMKD" => {
                mkdir(&mut stream, root, &cwd, &arg)
            }
            "RMD" | "XRMD" => {
                rmdir(&mut stream, root, &cwd, &arg)
            }
            "RNFR" => {
                match to_abs(root, &join_arg(&cwd, &arg)) {
                    Some(abs) if Path::new(&abs).exists() => {
                        rnfr = Some(abs);
                        reply(&mut stream, "350 Ready for RNTO")?;
                    }
                    _ => reply(&mut stream, "550 No such file")?,
                }
                None
            }
            "RNTO" => {
                match rnfr.take() {
                    Some(from) => match to_abs(root, &join_arg(&cwd, &arg)) {
                        Some(to) => {
                            if std::fs::rename(&from, &to).is_ok() {
                                reply(&mut stream, "250 Rename successful")?;
                            } else {
                                reply(&mut stream, "550 Rename failed")?;
                            }
                        }
                        None => reply(&mut stream, "550 Invalid path")?,
                    },
                    None => reply(&mut stream, "503 Bad sequence, RNFR first")?,
                }
                None
            }
            _ => {
                reply(&mut stream, "502 Command not implemented")?;
                None
            }
        };

        if let Some(e) = err {
            let _ = e;
        }
    }
    Ok(())
}

// ---------------------------------------------------------------- commands

fn list_dir(
    stream: &mut TcpStream,
    data: Option<TcpListener>,
    root: &str,
    cwd: &str,
    arg: &str,
    nlst: bool,
) -> Option<()> {
    let d = data?;
    let dir_path = match to_abs(root, &if arg.is_empty() { cwd.to_string() } else { join_arg(cwd, arg) }) {
        Some(p) if Path::new(&p).is_dir() => p,
        _ => {
            reply(stream, "550 No such directory").ok()?;
            return None;
        }
    };
    let read = std::fs::read_dir(&dir_path).ok()?;
    reply(stream, "150 Opening data connection").ok()?;
    let mut ds = open_data(Some(d), stream)?;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);

    let mut entries: Vec<(std::fs::DirEntry, std::fs::Metadata)> = Vec::new();
    for e in read.flatten() {
        if let Ok(m) = e.metadata() {
            entries.push((e, m));
        }
    }
    entries.sort_by(|a, b| {
        let ad = a.1.is_dir();
        let bd = b.1.is_dir();
        bd.cmp(&ad).then(a.0.file_name().cmp(&b.0.file_name()))
    });

    for (e, m) in entries {
        let name = e.file_name().to_string_lossy().into_owned();
        if nlst {
            write_line(&mut ds, &name).ok();
        } else {
            let line = ls_line(&name, &m, now);
            write_line(&mut ds, &line).ok();
        }
    }
    drop(ds);
    reply(stream, "226 Transfer complete").ok()
}

fn retrieve(
    stream: &mut TcpStream,
    data: Option<TcpListener>,
    root: &str,
    cwd: &str,
    arg: &str,
    rest: u64,
) -> Option<()> {
    if arg.is_empty() {
        reply(stream, "501 Syntax error").ok()?;
        return None;
    }
    let abs = rest_of(root, cwd, arg)?;
    let mut f = BufReader::new(std::fs::File::open(&abs).ok()?);
    f.seek(SeekFrom::Start(rest)).ok()?;
    reply(stream, "150 Opening data connection").ok()?;
    let mut ds = open_data(data, stream)?;
    std::io::copy(&mut f, &mut ds).ok()?;
    drop(ds);
    reply(stream, "226 Transfer complete").ok()
}

fn store(
    stream: &mut TcpStream,
    data: Option<TcpListener>,
    root: &str,
    cwd: &str,
    arg: &str,
    rest: u64,
) -> Option<()> {
    if arg.is_empty() {
        reply(stream, "501 Syntax error").ok()?;
        return None;
    }
    let abs = rest_of(root, cwd, arg)?;
    // create=true, 不 truncate: 普通上传覆盖时写满后 set_len 截断; REST 续传从 rest 续写。
    let file = {
        use std::fs::OpenOptions;
        OpenOptions::new().create(true).write(true).open(&abs).ok()?
    };
    let mut file = BufWriter::new(file);
    file.seek(SeekFrom::Start(rest)).ok()?;
    reply(stream, "150 Opening data connection").ok()?;
    let mut ds = open_data(data, stream)?;
    let n = std::io::copy(&mut ds, &mut file).ok()?;
    file.flush().ok()?;
    file.get_mut().set_len(rest + n).ok()?;
    drop(ds);
    reply(stream, "226 Transfer complete").ok()
}

fn size_of(stream: &mut TcpStream, root: &str, cwd: &str, arg: &str) -> Option<()> {
    let abs = to_abs(root, &join_arg(cwd, arg))?;
    match std::fs::metadata(&abs) {
        Ok(m) if m.is_file() => reply(stream, &format!("213 {}", m.len())).ok(),
        _ => reply(stream, "550 No such file").ok(),
    }
}

fn mdtm_of(stream: &mut TcpStream, root: &str, cwd: &str, arg: &str) -> Option<()> {
    let abs = to_abs(root, &join_arg(cwd, arg))?;
    match std::fs::metadata(&abs).and_then(|m| m.modified()) {
        Ok(t) => reply(stream, &format!("213 {}", ftp_time(t))).ok(),
        _ => reply(stream, "550 No such file").ok(),
    }
}

fn del(stream: &mut TcpStream, root: &str, cwd: &str, arg: &str) -> Option<()> {
    let abs = to_abs(root, &join_arg(cwd, arg))?;
    if std::fs::remove_file(&abs).is_ok() {
        reply(stream, "250 Deleted").ok()
    } else {
        reply(stream, "550 Cannot delete").ok()
    }
}

fn mkdir(stream: &mut TcpStream, root: &str, cwd: &str, arg: &str) -> Option<()> {
    let abs = to_abs(root, &join_arg(cwd, arg))?;
    match std::fs::create_dir(&abs) {
        Ok(()) => reply(stream, "257 Created").ok(),
        Err(_) => reply(stream, "550 Cannot create").ok(),
    }
}

fn rmdir(stream: &mut TcpStream, root: &str, cwd: &str, arg: &str) -> Option<()> {
    let abs = to_abs(root, &join_arg(cwd, arg))?;
    if std::fs::remove_dir(&abs).is_ok() {
        reply(stream, "250 Removed").ok()
    } else {
        reply(stream, "550 Cannot remove").ok()
    }
}

// ---------------------------------------------------------------- helpers

fn reply(stream: &mut TcpStream, line: &str) -> io::Result<()> {
    write!(&mut *stream, "{line}\r\n")
}

fn send(stream: &mut TcpStream, multi: &str) -> io::Result<()> {
    stream.write_all(multi.as_bytes())?;
    stream.write_all(b"\r\n")
}

fn write_line(w: &mut impl Write, line: &str) -> io::Result<()> {
    w.write_all(line.as_bytes())?;
    w.write_all(b"\r\n")
}

/// 规范化路径(词法): 消去 `.`/`..`, 去除重复斜杠。
fn norm_path(p: &str) -> String {
    let mut out: Vec<&str> = Vec::new();
    let trailing_slash = p.ends_with('/') || p.ends_with("/.");
    for comp in Path::new(p).components() {
        match comp {
            Component::CurDir => {}
            Component::ParentDir => {
                out.pop();
            }
            Component::Normal(c) => out.push(c.to_str().unwrap_or("")),
            _ => {}
        }
    }
    let mut s = format!("/{}", out.join("/"));
    if trailing_slash && !s.ends_with('/') {
        s.push('/');
    }
    if s.is_empty() {
        s.push('/');
    }
    s
}

fn to_abs(root: &str, rel_abs: &str) -> Option<String> {
    let p = norm_path(rel_abs);
    let joined = PathBuf::from(root).join(p.trim_start_matches('/'));
    if joined.starts_with(root) {
        Some(joined.to_string_lossy().into_owned())
    } else {
        None
    }
}

/// 拼接 cwd 与 arg; arg 以 / 开头时视为绝对路径(部分客户端会发绝对路径,
/// 旧实现一律拼在 cwd 下导致 550)。
fn join_arg(cwd: &str, arg: &str) -> String {
    if arg.starts_with('/') {
        norm_path(arg)
    } else {
        norm_path(&format!("{}/{}", cwd, arg))
    }
}

fn local_ip() -> String {
    if let Ok(sock) = std::net::UdpSocket::bind("0.0.0.0:0") {
        if sock.connect("8.8.8.8:80").is_ok() {
            if let Ok(a) = sock.local_addr() {
                return a.ip().to_string();
            }
        }
    }
    "127.0.0.1".to_string()
}

const MONTHS: [&str; 12] = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

/// Unix 秒 → (年,月,日,时,分)。Hinnant 算法, 稳定无 panic。
fn to_ymd(secs: i64) -> (i32, u32, u32, u32, u32) {
    let t = secs.rem_euclid(86400);
    let days = secs.div_euclid(86400);
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = ((if mp < 10 { mp + 3 } else { mp - 9 }) as i32) as u32;
    let y = if m <= 2 { y + 1 } else { y };
    (y as i32, m, d, (t / 3600) as u32, ((t % 3600) / 60) as u32)
}

fn ftp_time(t: std::time::SystemTime) -> String {
    let secs = t.duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
    let (y, mo, d, h, mi) = to_ymd(secs);
    format!("{:04}{:02}{:02}{:02}{:02}{:02}", y, mo, d, h, mi, secs.rem_euclid(60))
}

/// ls 风格单行: `-rw-r--r-- 1 uid gid size Mon DD HH:MM name`。
fn ls_line(name: &str, m: &std::fs::Metadata, now: i64) -> String {
    let secs = m.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let (y, mo, d, h, mi) = to_ymd(secs);
    let (ny, nmono, _nd, _, _) = to_ymd(now);
    let date = if y == ny && (nmono as i32 - mo as i32) < 6 {
        format!("{} {:2} {:02}:{:02}", MONTHS[(mo - 1) as usize], d, h, mi)
    } else {
        format!("{} {:2}  {:04}", MONTHS[(mo - 1) as usize], d, y)
    };
    let mode = if m.is_dir() {
        "drwxr-xr-x"
    } else if m.is_symlink() {
        "lrwxrwxrwx"
    } else {
        "-rw-r--r--"
    };
    format!("{mode} 1 0 0 {:>12} {date} {name}", m.len())
}

// ---------------------------------------------------------------- generic data transfer

/// 等待客户端连入数据端口, 最多 30s。
/// 旧实现无限阻塞: 客户端中途放弃会永久挂死线程, 8 个连接额度被耗光后 FTP 瘫痪。
fn open_data(data: Option<TcpListener>, _stream: &mut TcpStream) -> Option<TcpStream> {
    let l = data?;
    l.set_nonblocking(true).ok()?;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(30);
    loop {
        match l.accept() {
            Ok((s, _)) => {
                // 监听器非阻塞时, 部分平台 accept 出的 socket 继承该标志, 显式恢复
                s.set_nonblocking(false).ok();
                s.set_read_timeout(Some(std::time::Duration::from_secs(300))).ok();
                s.set_write_timeout(Some(std::time::Duration::from_secs(300))).ok();
                return Some(s);
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                if std::time::Instant::now() >= deadline {
                    return None;
                }
                std::thread::sleep(std::time::Duration::from_millis(20));
            }
            Err(_) => return None,
        }
    }
}

fn rest_of(root: &str, cwd: &str, arg: &str) -> Option<String> {
    to_abs(root, &join_arg(cwd, arg))
}
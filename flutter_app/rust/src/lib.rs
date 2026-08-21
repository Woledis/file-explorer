//! FileBridge Flutter Rust core —— 用 Rust 完整重写。
//!
//! 纯 std HTTP 文件服务 + 口令鉴权 + 设置持久化, 供 Flutter 经 dart:ffi 调用。
//! 支持目录 HTML/JSON 列表、GET/HEAD 下载 + Range 断点、口令登录(cookie 会话)。

mod auth;
mod ftp;
mod http;
mod settings;
mod vault;

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use http::handle_conn;

const VERSION: &str = "filebridge-core 0.3.0";

static RUNNING: AtomicBool = AtomicBool::new(false);
static STOP: AtomicBool = AtomicBool::new(false);

// ----------------------------------------------------------------- std helper

fn cstr_new(s: &str) -> *mut c_char {
    CString::new(s)
        .unwrap_or_else(|_| CString::new("").unwrap())
        .into_raw()
}

fn to_int(b: bool) -> c_int {
    if b { 1 } else { 0 }
}

// ----------------------------------------------------------------- C ABI

#[no_mangle]
pub extern "C" fn fb_version_string() -> *mut c_char {
    cstr_new(VERSION)
}

/// 显式初始化设置文件(幂等). 供 Dart 在实际启停服务前读写口令/端口使用。
#[no_mangle]
pub extern "C" fn fb_settings_init(settings_file: *const c_char) -> c_int {
    let path = read_cstr(settings_file).unwrap_or_default();
    if !path.is_empty() {
        settings::init(&path);
    }
    1
}

#[no_mangle]
pub extern "C" fn fb_settings_set_http_port(p: c_int) -> c_int {
    settings::set_http_port(p.max(0) as u16);
    1
}

#[no_mangle]
pub extern "C" fn fb_settings_get_http_port() -> c_int {
    settings::get_http_port() as c_int
}

#[no_mangle]
pub extern "C" fn fb_settings_set_ftp_port(p: c_int) -> c_int {
    settings::set_ftp_port(p.max(0) as u16);
    1
}

#[no_mangle]
pub extern "C" fn fb_settings_get_ftp_port() -> c_int {
    settings::get_ftp_port() as c_int
}

/// Returns true if running.
#[no_mangle]
pub extern "C" fn fb_engine_is_running() -> c_int {
    to_int(RUNNING.load(Ordering::SeqCst))
}

/// Start the HTTP engine.
///   root: 共享根目录(UTF-8), 不可为空
///   settings_file: 设置文件路径(存放口令等)
///   port: 0=自动
///   out_port: 输出实际端口(非空时写入)
/// Returns 1 on success, 0 on failure.
#[no_mangle]
pub extern "C" fn fb_engine_start(
    root: *const c_char,
    settings_file: *const c_char,
    port: c_int,
    out_port: *mut c_int,
) -> c_int {
    if RUNNING.load(Ordering::SeqCst) {
        return 0;
    }
    let root = read_cstr(root).unwrap_or_else(|| String::from("/storage/emulated/0"));
    let settings_path = read_cstr(settings_file).unwrap_or_default();
    if !settings_path.is_empty() {
        settings::init(&settings_path);
    }

    // port==0 时读取已配置的 HTTP 端口; 未配置(0)则由系统自动分配。
    // 旧写法 `.max(1)` 会把未配置变成端口 1(系统保留端口) -> bind 必失败 -> 一直"启动失败"。
    let bind_port: u16 = if port == 0 {
        settings::get_http_port()
    } else {
        port.max(0) as u16
    };
    let listener = match std::net::TcpListener::bind(("0.0.0.0", bind_port)) {
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

    // 非阻塞轮询: STOP 只在有新连接时才被检查的旧写法, 会让"停止服务"在无人
    // 访问时永远不生效(端口持续占用, 重启失败)。轮询让停止在 100ms 内落地。
    listener.set_nonblocking(true).ok();
    std::thread::spawn(move || {
        loop {
            if STOP.load(Ordering::SeqCst) {
                break;
            }
            match listener.accept() {
                Ok((stream, _)) => {
                    stream.set_nonblocking(false).ok();
                    let root = Arc::clone(&root);
                    std::thread::spawn(move || handle_conn(stream, root));
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(std::time::Duration::from_millis(50));
                }
                Err(_) => {
                    std::thread::sleep(std::time::Duration::from_millis(50));
                }
            }
        }
        RUNNING.store(false, Ordering::SeqCst);
    });
    1
}

#[no_mangle]
pub extern "C" fn fb_engine_stop() -> c_int {
    STOP.store(true, Ordering::SeqCst);
    1
}

// ----------------------------------------------------------------- ftp

static FTP_RUNNING: AtomicBool = AtomicBool::new(false);

/// 启动 FTP 服务(端口 2121 或自定)。返回实际端口; 0 = 启动失败。
#[no_mangle]
pub extern "C" fn fb_ftp_start(
    root: *const c_char,
    port: c_int,
    out_port: *mut c_int,
) -> c_int {
    if FTP_RUNNING.load(Ordering::SeqCst) {
        return 0;
    }
    match std::net::TcpListener::bind(("0.0.0.0", port.max(0) as u16)) {
        Ok(listener) => {
            let actual = listener.local_addr().map(|a| a.port()).unwrap_or(0);
            if !out_port.is_null() {
                unsafe { *out_port = actual as c_int; }
            }
            FTP_RUNNING.store(true, Ordering::SeqCst);
            let root = read_cstr(root).unwrap_or_else(|| String::from("/storage/emulated/0"));
            let root = Arc::new(root);
            std::thread::spawn(move || {
                let _ = ftp::serve_on(listener, &root);
                FTP_RUNNING.store(false, Ordering::SeqCst);
            });
            1
        }
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "C" fn fb_ftp_stop() -> c_int {
    ftp::request_stop();
    1
}

#[no_mangle]
pub extern "C" fn fb_ftp_is_running() -> c_int {
    to_int(FTP_RUNNING.load(Ordering::SeqCst))
}

// ----------------------------------------------------------------- password

/// Set access password (empty = open). Persists to settings file.
#[no_mangle]
pub extern "C" fn fb_set_password(pw: *const c_char) -> c_int {
    let pw = read_cstr(pw).unwrap_or_default();
    settings::set_password(&pw);
    1
}

/// Current password (may be empty).
#[no_mangle]
pub extern "C" fn fb_get_password() -> *mut c_char {
    cstr_new(&settings::get_password())
}

// ----------------------------------------------------------------- vault

/// 加密 src(dst 为空/失败返回 0)。1 = 成功。
#[no_mangle]
pub extern "C" fn fb_vault_encrypt_file(
    src: *const c_char,
    dst: *const c_char,
    password: *const c_char,
) -> c_int {
    let src = read_cstr(src).unwrap_or_default();
    let dst = read_cstr(dst).unwrap_or_default();
    let pw = read_cstr(password).unwrap_or_default();
    to_int(vault::encrypt_file(&src, &dst, &pw))
}

/// 解密 src(口令错误/格式非法返回 0)。1 = 成功。
#[no_mangle]
pub extern "C" fn fb_vault_decrypt_file(
    src: *const c_char,
    dst: *const c_char,
    password: *const c_char,
) -> c_int {
    let src = read_cstr(src).unwrap_or_default();
    let dst = read_cstr(dst).unwrap_or_default();
    let pw = read_cstr(password).unwrap_or_default();
    to_int(vault::decrypt_file(&src, &dst, &pw))
}

// ----------------------------------------------------------------- strings

fn read_cstr(p: *const c_char) -> Option<String> {
    if p.is_null() {
        return None;
    }
    let s = unsafe { CStr::from_ptr(p) };
    Some(s.to_string_lossy().into_owned())
}

#[no_mangle]
pub extern "C" fn fb_free_string(p: *mut c_char) {
    if !p.is_null() {
        unsafe { drop(CString::from_raw(p)); }
    }
}

// keep a copy of the M1 demo helpers for parity/backward tests
#[no_mangle]
pub extern "C" fn fb_greet(name: *const c_char) -> *mut c_char {
    let n = read_cstr(name).unwrap_or_default();
    cstr_new(&format!("hello, {n}"))
}

#[no_mangle]
pub extern "C" fn fb_ping() -> u32 {
    0xFB03
}
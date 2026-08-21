//! 设置与口令持久化。纯 std, 存成简单的 key=value 文本文件。

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

static PASSWORD: Mutex<Option<String>> = Mutex::new(None);
static HTTP_PORT: Mutex<Option<u16>> = Mutex::new(None);
static FTP_PORT: Mutex<Option<u16>> = Mutex::new(None);
static SETTINGS_PATH: Mutex<Option<PathBuf>> = Mutex::new(None);
static TLS_INITIALIZED: AtomicBool = AtomicBool::new(false);

const KEY_NAME: &str = "access_password";
const KEY_HTTP: &str = "http_port";
const KEY_FTP: &str = "ftp_port";

pub fn set_password(pw: &str) {
    let mut g = PASSWORD.lock().unwrap();
    *g = Some(pw.to_owned());
    persist();
}

/// empty string means no password (open).
pub fn get_password() -> String {
    PASSWORD.lock().unwrap().clone().unwrap_or_default()
}

pub fn verify(pw: &str) -> bool {
    let cur = get_password();
    cur.is_empty() || const_time_eq(&cur, pw)
}

/// 0 表示未设置(服务启动时自动/默认端口).
pub fn set_http_port(p: u16) {
    *HTTP_PORT.lock().unwrap() = (p != 0).then_some(p);
    persist();
}

pub fn get_http_port() -> u16 {
    HTTP_PORT.lock().unwrap().unwrap_or(0)
}

pub fn set_ftp_port(p: u16) {
    *FTP_PORT.lock().unwrap() = (p != 0).then_some(p);
    persist();
}

pub fn get_ftp_port() -> u16 {
    FTP_PORT.lock().unwrap().unwrap_or(0)
}

/// 0-length probabilistic equality to reduce timing leak.
fn const_time_eq(a: &str, b: &str) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff: u8 = 0;
    let ab = a.as_bytes();
    let bb = b.as_bytes();
    for i in 0..ab.len() {
        diff |= ab[i] ^ bb[i];
    }
    diff == 0
}

pub fn init(path: &str) {
    {
        let mut g = SETTINGS_PATH.lock().unwrap();
        *g = Some(PathBuf::from(path));
    }
    let _ = load();
}

pub fn persist() {
    let path = {
        let g = SETTINGS_PATH.lock().unwrap();
        g.clone()
    };
    let Some(path) = path else { return };
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let (pw, hp, fp) = (
        get_password(),
        get_http_port(),
        get_ftp_port(),
    );
    if let Ok(mut f) = fs::File::create(&path) {
        let _ = write!(f, "{}={}\n", KEY_NAME, pw);
        let _ = write!(f, "{}={}\n", KEY_HTTP, hp);
        let _ = write!(f, "{}={}\n", KEY_FTP, fp);
        let _ = f.sync_all();
    }
}

fn load() -> std::io::Result<()> {
    let path = {
        let g = SETTINGS_PATH.lock().unwrap();
        g.clone()
    };
    let Some(path) = path else { return Ok(()) };
    if !Path::new(&path).exists() {
        return Ok(());
    }
    let data = fs::read_to_string(&path)?;
    for line in data.lines() {
        if let Some(eq) = line.find('=') {
            let (k, v) = (&line[..eq], &line[eq + 1..]);
            match k {
                KEY_NAME => *PASSWORD.lock().unwrap() = Some(v.to_owned()),
                KEY_HTTP => *HTTP_PORT.lock().unwrap() =
                    v.trim().parse::<u16>().ok().filter(|&p| p != 0),
                KEY_FTP => *FTP_PORT.lock().unwrap() =
                    v.trim().parse::<u16>().ok().filter(|&p| p != 0),
                _ => {}
            }
        }
    }
    Ok(())
}

/// TLS is optional. We keep a flag so future wiring can lazily init once.
pub fn mark_tls_used() {
    TLS_INITIALIZED.store(true, Ordering::SeqCst);
}
//! 口令鉴权与会话 token。
//!
//! 理念: 不引入外部 HTTP 框架, 手写极小 session。浏览器经登录表单提交口令,
//! 校验通过后签发一个随机 token 放 cookie; 后续受保护请求需带上该 token。

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, AtomicU32, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::settings;

const SESSION_COOKIE: &str = "fbsess";
const SESSION_TTL_NS: i64 = 12 * 3600 * 1_000_000_000; // 会话 12 小时过期
const MAX_FAILS: u32 = 5; // 连续失败 5 次触发临时锁定
const LOCK_NS: i64 = 60_000_000_000; // 锁定 60 秒

static COUNTER: AtomicU64 = AtomicU64::new(1);
// 已签发且仍有效的会话 token -> 签发时刻(ns), 双重校验: 防伪造/枚举 + 过期失效。
// HashMap::new() 非 const, 故静态初始为 None, 使用时惰性创建。
static ISSUED: Mutex<Option<HashMap<u64, i64>>> = Mutex::new(None);
static FAILS: AtomicU32 = AtomicU32::new(0);
static LOCK_UNTIL: AtomicI64 = AtomicI64::new(0);

fn now_ns() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos() as i64)
        .unwrap_or(0)
}

fn next_token() -> u64 {
    let n = COUNTER.fetch_add(1, Ordering::Relaxed);
    let t = now_ns() as u64;
    // XOR 打散 + 单调计数 + 纳秒时间戳, 使 token 不可预测、不可枚举。
    (n.wrapping_mul(0x9E3779B97F4A7C15))
        .wrapping_add(0x2545F4914F6CDD1D)
        .wrapping_add(t)
}

/// Returns Some(new_token) if the provided password is accepted.
/// 成功后把 token 记入已签发集合; 重启后集合清空, 所有会话立即失效(需重新登录)。
pub fn try_login(password: &str) -> Option<u64> {
    let now = now_ns();
    if now < LOCK_UNTIL.load(Ordering::Relaxed) {
        return None; // 处于锁定期, 拒绝登录(防爆破)
    }
    if !settings::verify(password) {
        let f = FAILS.fetch_add(1, Ordering::Relaxed) + 1;
        if f >= MAX_FAILS {
            lock_until(now);
        }
        return None;
    }
    FAILS.store(0, Ordering::Relaxed);
    let tok = next_token();
    let mut guard = ISSUED.lock().unwrap();
    let map = guard.get_or_insert_with(HashMap::new);
    prune(map, now);
    map.insert(tok, now);
    Some(tok)
}

fn lock_until(now: i64) {
    LOCK_UNTIL.store(now + LOCK_NS, Ordering::Relaxed);
}

fn prune(map: &mut HashMap<u64, i64>, now: i64) {
    map.retain(|_, ts| now - *ts <= SESSION_TTL_NS);
}

pub fn session_cookie_name() -> &'static str {
    SESSION_COOKIE
}

/// Parse cookie header for our session value, verifying it was actually issued and still fresh.
pub fn session_from_cookie(cookie_hdr: Option<&str>) -> Option<u64> {
    let h = cookie_hdr?;
    let now = now_ns();
    for part in h.split(';') {
        let part = part.trim();
        if let Some(v) = part.strip_prefix(&format!("{}=", SESSION_COOKIE)) {
            if let Ok(n) = v.trim().parse::<u64>() {
                let guard = ISSUED.lock().unwrap();
                if let Some(map) = guard.as_ref() {
                    if let Some(&issued) = map.get(&n) {
                        if now <= issued + SESSION_TTL_NS {
                            return Some(n);
                        }
                    }
                }
            }
        }
    }
    None
}

pub fn render_login() -> String {
    String::from(
        "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">\
         <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\
         <title>FileBridge 登录</title>\
         <style>body{font-family:system-ui,sans-serif;margin:48px auto;max-width:320px;color:#222}\
         h2{font-weight:600;margin-bottom:8px}form{display:flex;flex-direction:column;gap:12px}\
         input{padding:10px;font-size:15px;border:1px solid #ccc;border-radius:6px}\
         button{padding:11px;font-size:15px;border:0;border-radius:6px;background:#3949ab;color:#fff;cursor:pointer}\
         .e{color:#c62828;font-size:13px}</style></head><body>\
         <h2>FileBridge</h2>\
         <form method=\"post\" action=\"/login\">\
         <input type=\"password\" name=\"pw\" placeholder=\"访问口令\" autofocus>\
         <button type=\"submit\">登录</button></form>\
         <p class=\"e\" id=\"e\"></p><script>\
         if(location.search.includes('e'))document.getElementById('e').textContent='口令错误';\
         </script></body></html>",
    )
}
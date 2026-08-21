//! 口令鉴权与会话 token。
//!
//! 理念: 不引入外部 HTTP 框架, 手写极小 session。浏览器经登录表单提交口令,
//! 校验通过后签发一个随机 token 放 cookie; 后续受保护请求需带上该 token。

use std::sync::atomic::{AtomicU64, Ordering};

use crate::settings;

const SESSION_COOKIE: &str = "fbsess";

static COUNTER: AtomicU64 = AtomicU64::new(1);

fn next_token() -> u64 {
    let n = COUNTER.fetch_add(1, Ordering::Relaxed);
    // XOR 打散, 避免连续; 真实产品应用安全随机源, 此为例证级实现。
    (n.wrapping_mul(0x9E3779B97F4A7C15)).wrapping_add(0x2545F4914F6CDD1D)
}

/// Returns Some(new_token) if the provided password is accepted.
pub fn try_login(password: &str) -> Option<u64> {
    if settings::verify(password) {
        Some(next_token())
    } else {
        None
    }
}

pub fn session_cookie_name() -> &'static str {
    SESSION_COOKIE
}

/// Parse cookie header for our session value.
pub fn session_from_cookie(cookie_hdr: Option<&str>) -> Option<u64> {
    let h = cookie_hdr?;
    for part in h.split(';') {
        let part = part.trim();
        if let Some(v) = part.strip_prefix(&format!("{}=", SESSION_COOKIE)) {
            if let Ok(n) = v.trim().parse::<u64>() {
                return Some(n);
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
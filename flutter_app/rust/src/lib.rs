//! FileBridge Rust core —— C ABI 导出层，供 Flutter 经 dart:ffi 调用。
//!
//! M1 只暴露两个最小函数验证跨 FFI 链路：
//!   - fb_version_string : 返回版本号字符串(需调用方 fb_free_string 释放)
//!   - fb_greet          : 入参字符串 → 返回问候(同样需释放)
//!
//! 后续 M2/M3 会在此继续暴露 http 服务启动、FTP、加密等。

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_void};

const VERSION: &str = "filebridge-core 0.1.0";

/// Returns a newly allocated C-string that the caller MUST release via
/// [fb_free_string]. Never returns NULL.
#[no_mangle]
pub extern "C" fn fb_version_string() -> *mut c_char {
    match CString::new(VERSION) {
        Ok(c) => c.into_raw(),
        Err(_) => {
            // Never fails for our constant, keep a defensive empty string.
            CString::new("").unwrap().into_raw()
        }
    }
}

/// Returns a newly allocated greeting containing the (UTF-8) input name.
/// The caller MUST release the returned string via [fb_free_string].
#[no_mangle]
pub extern "C" fn fb_greet(name: *const c_char) -> *mut c_char {
    if name.is_null() {
        return CString::new("hello").unwrap().into_raw();
    }
    let name = unsafe { CStr::from_ptr(name) };
    let msg = match name.to_str() {
        Ok(s) => format!("hello, {s}"),
        Err(_) => "hello, ?".to_owned(),
    };
    match CString::new(msg) {
        Ok(c) => c.into_raw(),
        Err(_) => CString::new("hello").unwrap().into_raw(),
    }
}

/// Frees a string previously returned by [fb_version_string] / [fb_greet].
/// Safe to call with NULL.
#[no_mangle]
pub extern "C" fn fb_free_string(p: *mut c_char) {
    if !p.is_null() {
        unsafe {
            drop(CString::from_raw(p));
        }
    }
}

/// Placeholder of the transfer engine for later M2 wiring.
#[no_mangle]
pub extern "C" fn fb_ping() -> u32 {
    0xFB01
}

// Suppress unused warning for c_void (kept for future pointer-typed APIs).
#[allow(dead_code)]
fn _keep_signature() {
    let _: *const c_void = std::ptr::null();
}
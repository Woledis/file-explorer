//! FileBridge Rust core (milestone 1).
//!
//! Exposes JNI entry points to the Kotlin layer. This first milestone only proves
//! the NDK cross-compile + JNI bridge works on device (tiny payload, std-only HTTP
//! probe). The HTTP/FTP/crypto cores get migrated into this crate in later
//! milestones; until then Kotlin keeps its current servers.

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;

const VERSION: &str = "filebridge-rs 0.1.0";

#[no_mangle]
pub extern "system" fn Java_com_filebridge_app_native_FbCore_ping<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let s = env.new_string(VERSION).expect("new_string failed");
    s.into_raw()
}

/// Milestone-1 HTTP smoke test: binds `127.0.0.1:<port>`, answers every request
/// with a tiny 200 body, then shuts down. Verifies Android Rust networking works.
#[no_mangle]
pub extern "system" fn Java_com_filebridge_app_native_FbCore_smokeHttp<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    port: jint,
) -> jint {
    match serve_one(port) {
        Ok(()) => 1,
        Err(_) => 0,
    }
}

fn serve_one(port: jint) -> std::io::Result<()> {
    let listener = std::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, port as u16))?;
    let header = b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nOK\r\n";
    for stream in listener.incoming().take(200) {
        match stream {
            Ok(mut s) => {
                use std::io::Write;
                let _ = s.write_all(header);
            }
            Err(_) => break,
        }
    }
    Ok(())
}
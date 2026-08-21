//! AES-256-GCM 加密/保险箱模块。
//!
//! 不引入完整加密框架, 手写受控格式, 支持分块流式加密(内存占用 = 单块大小),
//! 便于加密/解密大文件而不一次性载入内存。密钥经 PBKDF2-HMAC-SHA256 从口令派生,
//! 每文件独立随机盐 + 每块随机 nonce, 保证同口令多文件/多块密文不重。

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::io::{Read, Write};

type HmacSha256 = Hmac<Sha256>;

const MAGIC: &[u8; 8] = b"FBE2\0\0\0\0";
const SALT_LEN: usize = 16;
const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;
const ITER: u32 = 100_000;
const KEY_LEN: usize = 32;
const HEADER_LEN: usize = 8 + SALT_LEN + 4;
const DEFAULT_CHUNK: usize = 256 * 1024;

/// PBKDF2-HMAC-SHA256 派生主密钥(pure std + hmac/sha2)。
fn pbkdf2_sha256(password: &[u8], salt: &[u8], iters: u32, dklen: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(dklen);
    let mut block: u32 = 1;
    while out.len() < dklen {
        let mut mac = <HmacSha256 as Mac>::new_from_slice(password).unwrap();
        mac.update(salt);
        mac.update(&block.to_be_bytes());
        let mut u: Vec<u8> = mac.finalize().into_bytes().to_vec();
        let mut t = u.clone();
        for _ in 1..iters {
            let mut m = <HmacSha256 as Mac>::new_from_slice(password).unwrap();
            m.update(&u);
            u = m.finalize().into_bytes().to_vec();
            for (ti, ui) in t.iter_mut().zip(u.iter()) {
                *ti ^= ui;
            }
        }
        out.extend_from_slice(&t);
        block += 1;
    }
    out.truncate(dklen);
    out
}

fn block_encrypt(key: &Key<Aes256Gcm>, nonce: &[u8], plain: &[u8]) -> Option<Vec<u8>> {
    let cipher = Aes256Gcm::new(key);
    cipher.encrypt(Nonce::from_slice(nonce), plain).ok()
}

fn block_decrypt(key: &Key<Aes256Gcm>, nonce: &[u8], ct: &[u8]) -> Option<Vec<u8>> {
    let cipher = Aes256Gcm::new(key);
    cipher.decrypt(Nonce::from_slice(nonce), ct).ok()
}

fn random(buf: &mut [u8]) -> bool {
    getrandom::getrandom(buf).is_ok()
}

fn new_key(password: &[u8], salt: &[u8]) -> Key<Aes256Gcm> {
    let kb = pbkdf2_sha256(password, salt, ITER, KEY_LEN);
    *Key::<Aes256Gcm>::from_slice(&kb)
}

fn write_header(w: &mut impl Write, salt: &[u8], chunk: usize) -> std::io::Result<()> {
    w.write_all(MAGIC)?;
    w.write_all(salt)?;
    w.write_all(&(chunk as u32).to_le_bytes())
}

fn read_header(r: &mut impl Read) -> std::io::Result<(Vec<u8>, usize)> {
    let mut magic = [0u8; 8];
    r.read_exact(&mut magic)?;
    if &magic != MAGIC {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "bad magic"));
    }
    let mut salt = vec![0u8; SALT_LEN];
    r.read_exact(&mut salt)?;
    let mut cb = [0u8; 4];
    r.read_exact(&mut cb)?;
    let chunk = u32::from_le_bytes(cb) as usize;
    Ok((salt, if chunk == 0 { DEFAULT_CHUNK } else { chunk }))
}

/// 加密 src 文件到 dst。每个块独立随机 nonce, 头部自描述。
pub fn encrypt_file(src: &str, dst: &str, password: &str) -> bool {
    if password.is_empty() {
        return false;
    }
    let mut salt = [0u8; SALT_LEN];
    if !random(&mut salt) {
        return false;
    }
    let key = new_key(password.as_bytes(), &salt);
    let mut in_file = match std::fs::File::open(src) {
        Ok(f) => f,
        Err(_) => return false,
    };
    let out_file = match std::fs::File::create(dst) {
        Ok(f) => f,
        Err(_) => return false,
    };
    let mut writer = std::io::BufWriter::new(out_file);
    if write_header(&mut writer, &salt, DEFAULT_CHUNK).is_err() {
        return false;
    }
    let mut buf = vec![0u8; DEFAULT_CHUNK];
    loop {
        let n = match in_file.read(&mut buf) {
            Ok(n) => n,
            Err(_) => return false,
        };
        if n == 0 {
            break;
        }
        let mut nonce = [0u8; NONCE_LEN];
        if !random(&mut nonce) {
            return false;
        }
        let ct = match block_encrypt(&key, &nonce, &buf[..n]) {
            Some(c) => c,
            None => return false,
        };
        if writer.write_all(&nonce).is_err() || writer.write_all(&ct).is_err() {
            return false;
        }
    }
    let _ = writer.flush();
    let _ = writer.get_ref().sync_all();
    true
}

/// 解密 src 到 dst。校验魔数与 GCM tag; 口令错误时解密失败并返回 false。
pub fn decrypt_file(src: &str, dst: &str, password: &str) -> bool {
    if password.is_empty() {
        return false;
    }
    let mut in_file = match std::fs::File::open(src) {
        Ok(f) => f,
        Err(_) => return false,
    };
    let (salt, chunk) = match read_header(&mut in_file) {
        Ok(v) => v,
        Err(_) => return false,
    };
    let key = new_key(password.as_bytes(), &salt);
    let out_file = match std::fs::File::create(dst) {
        Ok(f) => f,
        Err(_) => return false,
    };
    let mut writer = std::io::BufWriter::new(out_file);
    let mut nonce = [0u8; NONCE_LEN];
    loop {
        let mut filled = 0;
        while filled < NONCE_LEN {
            match in_file.read(&mut nonce[filled..]) {
                Ok(0) => break,
                Ok(n) => filled += n,
                Err(_) => return false,
            }
        }
        if filled < NONCE_LEN {
            break; // EOF
        }
        let mut ct = vec![0u8; chunk];
        let mut got = 0;
        loop {
            match in_file.read(&mut ct[got..]) {
                Ok(0) => break,
                Ok(m) => {
                    got += m;
                    if got == ct.len() {
                        break;
                    }
                }
                Err(_) => return false,
            }
        }
        if got < TAG_LEN {
            return false;
        }
        let plain = match block_decrypt(&key, &nonce, &ct[..got]) {
            Some(p) => p,
            None => return false,
        };
        if writer.write_all(&plain).is_err() {
            return false;
        }
    }
    let _ = writer.flush();
    let _ = writer.get_ref().sync_all();
    true
}
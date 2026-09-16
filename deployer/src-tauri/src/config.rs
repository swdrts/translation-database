use rand::RngCore;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WizardConfig {
    pub port: u16,
    pub admin_password: String,
    pub es_heap_gb: u32,
    pub backend_heap_gb: u32,
    pub app_version: String,
}

pub const DEFAULT_APP_VERSION: &str = "0.1.0";

pub fn default_config() -> WizardConfig {
    WizardConfig {
        port: 80,
        admin_password: String::new(),
        es_heap_gb: 2,
        backend_heap_gb: 1,
        app_version: DEFAULT_APP_VERSION.into(),
    }
}

pub fn validate(cfg: &WizardConfig) -> Result<(), Vec<String>> {
    let mut errs = Vec::new();
    if cfg.port == 0 { errs.push("网页端口必须在 1-65535 之间".into()); }
    if cfg.admin_password.chars().count() < 8 { errs.push("管理员密码至少 8 位".into()); }
    if !(1..=16).contains(&cfg.es_heap_gb) { errs.push("ES 堆内存必须在 1-16GB".into()); }
    if !(1..=16).contains(&cfg.backend_heap_gb) { errs.push("后端 JVM 内存必须在 1-16GB".into()); }
    let v = cfg.app_version.as_bytes();
    let charset_ok = !v.is_empty()
        && v.len() <= 64
        && v[0].is_ascii_alphanumeric()
        && v.iter().all(|c| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'_' | b'-'));
    if !charset_ok { errs.push("镜像版本只能是字母数字与 . _ -（≤64 位）".into()); }
    if errs.is_empty() { Ok(()) } else { Err(errs) }
}

pub fn generate_jwt_secret() -> String {
    let mut bytes = [0u8; 48];
    rand::thread_rng().fill_bytes(&mut bytes);
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn generate_db_password() -> String {
    const CHARSET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    let mut bytes = [0u8; 24];
    rand::thread_rng().fill_bytes(&mut bytes);
    bytes.iter().map(|b| CHARSET[*b as usize % CHARSET.len()] as char).collect()
}

pub fn render_env(cfg: &WizardConfig, jwt: &str, db_pw: &str) -> String {
    format!(
        "TRANSDB_FRONTEND_PORT={port}\n\
         TRANSDB_ADMIN_PASSWORD={admin}\n\
         TRANSDB_JWT_SECRET={jwt}\n\
         TRANSDB_DB_PASSWORD={db_pw}\n\
         TRANSDB_ES_JAVA_OPTS=-Xms{es}g -Xmx{es}g\n\
         TRANSDB_JAVA_OPTS=-Xmx{be}g\n\
         TRANSDB_APP_VERSION={ver}\n",
        port = cfg.port,
        admin = cfg.admin_password,
        es = cfg.es_heap_gb,
        be = cfg.backend_heap_gb,
        ver = cfg.app_version,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validate_rejects_short_password_and_bad_heap_and_bad_version() {
        let mut cfg = default_config();
        cfg.admin_password = "123".into();
        let errs = validate(&cfg).unwrap_err();
        assert!(errs.iter().any(|e| e.contains("管理员密码")));

        let mut cfg = default_config();
        cfg.admin_password = "longenough".into();
        cfg.es_heap_gb = 0;
        assert!(validate(&cfg).is_err());

        let mut cfg = default_config();
        cfg.admin_password = "longenough".into();
        cfg.app_version = "bad tag!".into();
        assert!(validate(&cfg).is_err());
    }

    #[test]
    fn validate_accepts_defaults_with_8char_password() {
        let mut cfg = default_config();
        cfg.admin_password = "12345678".into();
        assert!(validate(&cfg).is_ok());
    }

    #[test]
    fn secrets_are_random_long_and_alphanumeric() {
        let a = (generate_jwt_secret(), generate_db_password());
        let b = (generate_jwt_secret(), generate_db_password());
        assert_ne!(a.0, b.0);
        assert_eq!(a.0.len(), 96);
        assert!(a.0.chars().all(|c| c.is_ascii_hexdigit()));
        assert_eq!(a.1.len(), 24);
        assert!(a.1.chars().all(|c| c.is_ascii_alphanumeric()));
    }

    #[test]
    fn render_env_contains_all_keys_with_advanced_defaults() {
        let cfg = default_config();
        let env = render_env(&cfg, "JWT_X", "DB_X");
        assert!(env.contains("TRANSDB_FRONTEND_PORT=80\n"));
        assert!(env.contains("TRANSDB_ADMIN_PASSWORD=\n") || env.contains("TRANSDB_ADMIN_PASSWORD="));
        assert!(env.contains("TRANSDB_JWT_SECRET=JWT_X\n"));
        assert!(env.contains("TRANSDB_DB_PASSWORD=DB_X\n"));
        assert!(env.contains("TRANSDB_ES_JAVA_OPTS=-Xms2g -Xmx2g\n"));
        assert!(env.contains("TRANSDB_JAVA_OPTS=-Xmx1g\n"));
        assert!(env.contains("TRANSDB_APP_VERSION=0.1.0\n"));
    }
}

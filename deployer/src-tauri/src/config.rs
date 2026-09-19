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
    // 密码会原样写入 .env：空白/# 会截断取值，$ 和引号会被 shell/compose 展开或破坏语法
    if cfg.admin_password.chars().any(|c| c.is_whitespace() || matches!(c, '#' | '$' | '\'' | '"')) {
        errs.push("管理员密码不能包含空格、#、$ 或引号".into());
    }
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

/// 读回 .env 里的应用镜像版本号（TRANSDB_APP_VERSION 行）；缺失时 None（compose 走默认）
pub fn read_env_version(old_env: &str) -> Option<String> {
    old_env
        .lines()
        .find(|l| l.starts_with("TRANSDB_APP_VERSION="))
        .and_then(|l| l.split_once('=').map(|(_, v)| v.to_string()))
        .filter(|v| !v.is_empty())
}

/// 把 .env 的 TRANSDB_APP_VERSION 替换/追加为指定值——升级流程先置 latest 再拉取，
/// 完成后回写实际版本号，日常 restart/up 仍钉在具体版本
pub fn env_with_version(old_env: &str, version: &str) -> String {
    let line = format!("TRANSDB_APP_VERSION={version}");
    let mut found = false;
    let mut out: Vec<String> = old_env
        .lines()
        .map(|l| {
            if l.starts_with("TRANSDB_APP_VERSION=") {
                found = true;
                line.clone()
            } else {
                l.to_string()
            }
        })
        .collect();
    if !found {
        out.push(line);
    }
    let mut s = out.join("\n");
    if !s.is_empty() {
        s.push('\n');
    }
    s
}

/// 从既有 .env 读回三密钥（存在且非空才算命中）——保留数据卷/断点续跑重提交配置时必须复用，
/// 重新生成会使 JWT/DB 密钥与旧数据卷失配（postgres 密码仅卷为空时生效）
pub fn reuse_secrets(old_env: &str) -> Option<(String, String, String)> {
    let find = |k: &str| {
        old_env.lines().find(|l| l.starts_with(k)).and_then(|l| l.split_once('=').map(|(_, v)| v.to_string())).filter(|v| !v.is_empty())
    };
    Some((find("TRANSDB_JWT_SECRET=")?, find("TRANSDB_DB_PASSWORD=")?, find("TRANSDB_ADMIN_PASSWORD=")?))
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
    fn validate_rejects_password_with_env_metacharacters() {
        // $$ 会被 .env/compose 变量展开，必须拒绝；常规符号 @ - 连写的密码应通过
        let mut cfg = default_config();
        cfg.admin_password = "p@$$w0rd".into();
        let errs = validate(&cfg).unwrap_err();
        assert!(errs.iter().any(|e| e.contains("管理员密码不能包含空格、#、$ 或引号")));
        let mut cfg = default_config();
        cfg.admin_password = "transdb-test-2026".into();
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
    fn reuse_secrets_reads_all_three_values_from_env() {
        let env = "TRANSDB_FRONTEND_PORT=80\n\
                   TRANSDB_ADMIN_PASSWORD=old-admin-pw\n\
                   TRANSDB_JWT_SECRET=jwt-x\n\
                   TRANSDB_DB_PASSWORD=db-x\n";
        assert_eq!(reuse_secrets(env), Some(("jwt-x".into(), "db-x".into(), "old-admin-pw".into())));
    }

    #[test]
    fn reuse_secrets_none_on_missing_key_or_empty_value() {
        assert_eq!(reuse_secrets(""), None); // 全缺
        assert_eq!(reuse_secrets("TRANSDB_JWT_SECRET=jwt\nTRANSDB_DB_PASSWORD=db\n"), None); // 缺 ADMIN
        assert_eq!(
            reuse_secrets("TRANSDB_ADMIN_PASSWORD=\nTRANSDB_JWT_SECRET=jwt\nTRANSDB_DB_PASSWORD=db\n"),
            None, // 空值视同未命中
        );
    }

    #[test]
    fn env_with_version_replaces_existing_line() {
        let env = render_env(&default_config(), "JWT_X", "DB_X");
        let next = env_with_version(&env, "0.2.0");
        assert!(next.contains("TRANSDB_APP_VERSION=0.2.0\n"));
        assert!(!next.contains("0.1.0"));
        // 其余行原样保留
        assert!(next.contains("TRANSDB_JWT_SECRET=JWT_X\n"));
    }

    #[test]
    fn env_with_version_appends_when_missing() {
        let env = "TRANSDB_FRONTEND_PORT=80\nTRANSDB_JWT_SECRET=jwt\n";
        assert_eq!(env_with_version(env, "1.2.3"), "TRANSDB_FRONTEND_PORT=80\nTRANSDB_JWT_SECRET=jwt\nTRANSDB_APP_VERSION=1.2.3\n");
    }

    #[test]
    fn read_env_version_returns_value_and_none_when_missing() {
        let env = render_env(&default_config(), "JWT_X", "DB_X");
        assert_eq!(read_env_version(&env).as_deref(), Some("0.1.0"));
        assert_eq!(read_env_version("TRANSDB_FRONTEND_PORT=80\n"), None);
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

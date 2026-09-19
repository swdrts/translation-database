use serde_json::{Map, Value};
use std::collections::HashSet;
use std::path::{Path, PathBuf};

/// 2026-09-19 实测可达的国内镜像：GET /v2/ 返回 401（鉴权质询）或 200 是
/// Docker registry 存活的标准信号；dockerproxy.net 因 manifest 探测 404 不入默认
pub const DEFAULT_MIRRORS: &[&str] = &[
    "https://docker.1ms.run",
    "https://docker.xuanyuan.me",
    "https://docker.m.daocloud.io",
    "https://hub.rat.dev",
];

/// Docker Desktop（Windows/macOS）均读取此用户级配置，改完需重启 daemon 生效
pub fn daemon_json_path() -> PathBuf {
    dirs::home_dir().unwrap_or_else(PathBuf::new).join(".docker").join("daemon.json")
}

pub fn read_mirrors(path: &Path) -> Vec<String> {
    std::fs::read_to_string(path)
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .and_then(|v| v.get("registry-mirrors")?.as_array().cloned())
        .map(|arr| arr.into_iter().filter_map(|v| v.as_str().map(String::from)).collect())
        .unwrap_or_default()
}

/// 合并写入 registry-mirrors，保留 daemon.json 其余键（如 insecure-registries）；
/// 文件损坏或非对象时直接覆盖重建，避免被坏文件卡死
pub fn write_mirrors(path: &Path, mirrors: &[String]) -> Result<(), String> {
    let mut doc = std::fs::read_to_string(path)
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .and_then(|v| match v {
            Value::Object(m) => Some(m),
            _ => None,
        })
        .unwrap_or_else(Map::new);
    doc.insert(
        "registry-mirrors".into(),
        Value::Array(mirrors.iter().map(|m| Value::String(m.clone())).collect()),
    );
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("创建 .docker 目录失败：{e}"))?;
    }
    let pretty = serde_json::to_string_pretty(&Value::Object(doc)).map_err(|e| e.to_string())?;
    std::fs::write(path, pretty).map_err(|e| format!("写入 daemon.json 失败：{e}"))
}

/// trim、去尾部斜杠、按序去重；空行自然被过滤
pub fn normalize(mirrors: Vec<String>) -> Vec<String> {
    let mut seen = HashSet::new();
    mirrors
        .into_iter()
        .map(|m| m.trim().trim_end_matches('/').to_string())
        .filter(|m| !m.is_empty() && seen.insert(m.clone()))
        .collect()
}

pub fn validate_mirror(url: &str) -> bool {
    let host = if let Some(rest) = url.strip_prefix("https://") {
        rest
    } else if let Some(rest) = url.strip_prefix("http://") {
        rest
    } else {
        return false;
    };
    !host.is_empty() && url.len() <= 200 && !url.chars().any(char::is_whitespace)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_are_well_formed() {
        assert_eq!(DEFAULT_MIRRORS.len(), 4);
        assert!(DEFAULT_MIRRORS.iter().all(|m| validate_mirror(m)));
        assert!(DEFAULT_MIRRORS.contains(&"https://docker.1ms.run"));
        assert!(DEFAULT_MIRRORS.contains(&"https://docker.m.daocloud.io"));
    }

    #[test]
    fn write_preserves_other_daemon_keys_and_roundtrips() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("daemon.json");
        std::fs::write(&path, r#"{"insecure-registries":["my.local:5000"],"max-concurrent-downloads":5}"#).unwrap();
        write_mirrors(&path, &["https://docker.1ms.run".into()]).unwrap();
        assert_eq!(read_mirrors(&path), vec!["https://docker.1ms.run".to_string()]);
        let doc: Value = serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(doc["insecure-registries"][0], "my.local:5000");
        assert_eq!(doc["max-concurrent-downloads"], 5);
    }

    #[test]
    fn read_missing_or_corrupt_file_returns_empty_and_write_recovers() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("daemon.json");
        assert!(read_mirrors(&path).is_empty());
        std::fs::write(&path, "{ not json").unwrap();
        assert!(read_mirrors(&path).is_empty());
        write_mirrors(&path, &["https://hub.rat.dev".into()]).unwrap();
        assert_eq!(read_mirrors(&path), vec!["https://hub.rat.dev".to_string()]);
    }

    #[test]
    fn normalize_trims_dedups_and_strips_trailing_slash() {
        let out = normalize(vec![
            " https://docker.1ms.run/ ".into(),
            "https://docker.1ms.run".into(),
            "".into(),
            "https://docker.m.daocloud.io".into(),
        ]);
        assert_eq!(out, vec!["https://docker.1ms.run".to_string(), "https://docker.m.daocloud.io".to_string()]);
    }

    #[test]
    fn validate_mirror_requires_scheme_and_host() {
        assert!(validate_mirror("https://docker.1ms.run"));
        assert!(validate_mirror("http://192.168.1.10:5000"));
        assert!(!validate_mirror("docker.1ms.run"));
        assert!(!validate_mirror("https://"));
        assert!(!validate_mirror("https://a b.com"));
    }
}
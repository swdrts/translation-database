use crate::config::WizardConfig;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "stage", rename_all = "snake_case")]
pub enum DeployStage {
    CheckEnv,
    InstallDocker,
    WaitEngine,
    Configure,
    Pull,
    Up,
    Done,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PersistedState {
    pub stage: DeployStage,
    pub deployed: bool,
    pub config: Option<WizardConfig>,
    /// 首次部署成功后已默认开启「工具自身登录自启」：保证只 enable 一次，
    /// 用户在设置页主动关掉后，后续重部署/改端口不得再擅自打开。
    /// serde(default)：旧版 state.json 无此字段时反序列化为 false，其余字段（含 deployed）不丢
    #[serde(default)]
    pub autostart_done: bool,
}

impl PersistedState {
    pub fn initial() -> Self {
        Self { stage: DeployStage::CheckEnv, deployed: false, config: None, autostart_done: false }
    }
}

pub fn data_dir() -> std::io::Result<PathBuf> {
    let dir = dirs::data_dir().ok_or_else(|| {
        std::io::Error::new(std::io::ErrorKind::NotFound, "无法定位系统数据目录")
    })?;
    let dir = dir.join("transdb");
    std::fs::create_dir_all(&dir)?;
    Ok(dir)
}

pub fn load(dir: &Path) -> Result<PersistedState, std::io::Error> {
    match std::fs::read_to_string(dir.join("state.json")) {
        Ok(s) => Ok(serde_json::from_str(&s).unwrap_or_else(|_| PersistedState::initial())),
        // 文件缺失（首次启动）或编码/内容损坏：维持既有语义，回退初始状态重开，
        // 避免单字节损坏把用户永久卡死
        Err(e) if matches!(e.kind(), std::io::ErrorKind::NotFound | std::io::ErrorKind::InvalidData) => {
            Ok(PersistedState::initial())
        }
        // 其他 io 错误（如 PermissionDenied）上抛：静默回退会把 deployed=true 误重置，
        // 可能诱导用户在已部署环境上重复部署
        Err(e) => Err(e),
    }
}

pub fn save(dir: &Path, st: &PersistedState) -> std::io::Result<()> {
    // state.json 只供断点续跑与端口展示使用；admin_password 明文不落此文件（.env 已有）
    let mut to_disk = st.clone();
    if let Some(c) = &mut to_disk.config {
        c.admin_password = String::new();
    }
    let tmp = dir.join("state.json.tmp");
    std::fs::write(&tmp, serde_json::to_vec_pretty(&to_disk)?)?;
    std::fs::rename(tmp, dir.join("state.json"))
}

pub fn next_stage(stage: &DeployStage) -> Option<DeployStage> {
    use DeployStage::*;
    Some(match stage {
        CheckEnv => InstallDocker,
        InstallDocker => WaitEngine,
        WaitEngine => Configure,
        Configure => Pull,
        Pull => Up,
        Up => Done,
        Done => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stage_sequence_is_linear() {
        let mut s = DeployStage::CheckEnv;
        let mut seq = vec![s.clone()];
        while let Some(n) = next_stage(&s) { seq.push(n.clone()); s = n; }
        assert_eq!(seq.len(), 7);
        assert_eq!(seq.last().unwrap(), &DeployStage::Done);
        assert_eq!(next_stage(&DeployStage::Done), None);
    }

    #[test]
    fn save_then_load_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        // autostart_done 取 true：若序列化丢字段，load 经 serde(default) 回 false 即可被断言捕获
        let st = PersistedState { stage: DeployStage::Pull, deployed: false, config: Some(crate::config::default_config()), autostart_done: true };
        save(dir.path(), &st).unwrap();
        assert_eq!(load(dir.path()).unwrap(), st);
        // 旧版 state.json（无 autostart_done 字段）：serde(default) 兜底 false，deployed 等既有字段不丢
        //（DeployStage 为 internally-tagged 枚举，自身序列化为 {"stage":"done"}，故外层 stage 嵌套一层）
        std::fs::write(dir.path().join("state.json"), r#"{"stage":{"stage":"done"},"deployed":true,"config":null}"#).unwrap();
        let legacy = load(dir.path()).unwrap();
        assert!(legacy.deployed && !legacy.autostart_done);
    }

    #[test]
    fn missing_or_corrupt_file_falls_back_to_initial() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(load(dir.path()).unwrap(), PersistedState::initial());
        std::fs::write(dir.path().join("state.json"), "{ not json").unwrap();
        assert_eq!(load(dir.path()).unwrap(), PersistedState::initial());
    }

    #[test]
    fn saved_state_never_contains_admin_password() {
        let dir = tempfile::tempdir().unwrap();
        let mut cfg = crate::config::default_config();
        cfg.admin_password = "super-secret-pw".into();
        save(dir.path(), &PersistedState { stage: DeployStage::Pull, deployed: false, config: Some(cfg), autostart_done: false }).unwrap();
        let raw = std::fs::read_to_string(dir.path().join("state.json")).unwrap();
        assert!(!raw.contains("super-secret-pw"));
    }
}

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
}

impl PersistedState {
    pub fn initial() -> Self {
        Self { stage: DeployStage::CheckEnv, deployed: false, config: None }
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

pub fn load(dir: &Path) -> PersistedState {
    std::fs::read_to_string(dir.join("state.json"))
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_else(PersistedState::initial)
}

pub fn save(dir: &Path, st: &PersistedState) -> std::io::Result<()> {
    let tmp = dir.join("state.json.tmp");
    std::fs::write(&tmp, serde_json::to_vec_pretty(st)?)?;
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
        let st = PersistedState { stage: DeployStage::Pull, deployed: false, config: Some(crate::config::default_config()) };
        save(dir.path(), &st).unwrap();
        assert_eq!(load(dir.path()), st);
    }

    #[test]
    fn missing_or_corrupt_file_falls_back_to_initial() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(load(dir.path()), PersistedState::initial());
        std::fs::write(dir.path().join("state.json"), "{ not json").unwrap();
        assert_eq!(load(dir.path()), PersistedState::initial());
    }
}

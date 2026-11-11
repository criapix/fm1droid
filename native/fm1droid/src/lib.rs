//! FM1 Droid: the fm1-emu machine for Android (engine thread, audio, panel, JNI).

#[allow(dead_code)]
#[path = "../../../vendor/fm1-emu/crates/fm1-live/src/panel.rs"]
mod panel;

pub mod app;
pub mod engine;
pub mod flashrec;
pub mod resample;
pub mod ui;

#[cfg(target_os = "android")]
mod aaudio;
#[cfg(target_os = "android")]
mod jni_api;

#[cfg(test)]
mod smoke {
    #[test]
    fn links_against_fm1_emu() {
        assert_eq!(crate::panel::W, 1280);
        assert_eq!(crate::panel::H, 820);
        assert_eq!(fm1_soc::FLASH_SIZE % 4096, 0);
    }
}

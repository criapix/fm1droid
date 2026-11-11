//! JNI for com.criapix.fm1droid.Native.

use crate::aaudio::{self, Stream};
use crate::app::app;
use crate::engine::{self, Cmd};
use crate::panel;
use fm1_soc::Action;
use jni::objects::{JByteArray, JByteBuffer, JClass};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jlong, jstring};
use jni::JNIEnv;
use std::sync::atomic::Ordering;
use std::sync::Mutex;

static STREAM: Mutex<Option<Stream>> = Mutex::new(None);

fn bytes(env: &JNIEnv, a: &JByteArray) -> Vec<u8> {
    env.convert_byte_array(a).unwrap_or_default()
}

fn jstr(env: &JNIEnv, s: String) -> jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// null on success, the reason otherwise.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_boot<'l>(
    env: JNIEnv<'l>, _c: JClass<'l>, pkg: JByteArray<'l>, saved: JByteArray<'l>, syx: JByteArray<'l>, mhz: jint, jit: jboolean,
) -> jstring {
    let (pkg, saved, syx) = (bytes(&env, &pkg), bytes(&env, &saved), bytes(&env, &syx));
    match app().boot(pkg, saved, syx, mhz as u32, jit != 0) {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => jstr(&env, e),
    }
}

/// "ok:<identity>" or "err:<reason>".
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_probe<'l>(env: JNIEnv<'l>, _c: JClass<'l>, pkg: JByteArray<'l>) -> jstring {
    let pkg = bytes(&env, &pkg);
    jstr(&env, match engine::probe(&pkg) {
        Ok(id) => format!("ok:{id}"),
        Err(e) => format!("err:{e}"),
    })
}

/// The device's sample rate, or a negative number when the stream did not open.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_audioStart(_env: JNIEnv, _c: JClass) -> jint {
    let mut slot = STREAM.lock().unwrap();
    *slot = None;
    match Stream::open(app().sink.clone(), app().shared.clone()) {
        Ok(s) => {
            let rate = s.rate;
            *slot = Some(s);
            app().send(Cmd::Pause(false));
            rate
        }
        Err(_) => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_audioStop(_env: JNIEnv, _c: JClass) {
    *STREAM.lock().unwrap() = None;
    app().send(Cmd::Pause(true));
}

#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_audioLost(_env: JNIEnv, _c: JClass) -> jboolean {
    aaudio::LOST.load(Ordering::Relaxed) as jboolean
}

/// Emulated time over time spent emulating (>= 1.3 keeps up comfortably).
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_speed(_env: JNIEnv, _c: JClass) -> jfloat {
    app().shared.view.lock().unwrap().speed
}

#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_starved(_env: JNIEnv, _c: JClass) -> jlong {
    app().shared.starved.load(Ordering::Relaxed) as jlong
}

/// bit 0: booted, bit 1: halted
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_status(_env: JNIEnv, _c: JClass) -> jint {
    let v = app().shared.view.lock().unwrap();
    v.booted as jint | (v.halted as jint) << 1
}

#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_flashRev(_env: JNIEnv, _c: JClass) -> jint {
    app().shared.view.lock().unwrap().flash_rev as jint
}

#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_flashSnapshot(env: JNIEnv, _c: JClass) -> jbyteArray {
    let v = app().shared.view.lock().unwrap();
    env.byte_array_from_slice(&v.flash).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_setLead(_env: JNIEnv, _c: JClass, frames: jint) {
    app().send(Cmd::Lead(frames.max(0) as usize));
}

/// A key or button (matrix id 0..40) down / up, with the velocity (1..127) of a key going down.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_key(_env: JNIEnv, _c: JClass, id: jint, down: jboolean, vel: jint) {
    if !(0..41).contains(&id) {
        return;
    }
    let down = down != 0;
    app().ui.lock().unwrap().set_down(id as usize, down);
    if down {
        app().send(Cmd::Act(Action::KeyVel { vel: vel.clamp(1, 127) as u8 }));
    }
    app().send(Cmd::Act(Action::Key { id: id as u8, down }));
}

/// Turn panel encoder `i` (index into panel::ENCS) by `clicks` (+ = clockwise).
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_enc(_env: JNIEnv, _c: JClass, i: jint, clicks: jint) {
    let Some(e) = panel::ENCS.get(i as usize) else { return };
    app().ui.lock().unwrap().turn(i as usize, clicks);
    app().send(Cmd::Act(Action::Enc { enc: e.1, clicks }));
}

/// The MASTER pot, 0..1023.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_pot(_env: JNIEnv, _c: JClass, v: jint) {
    let v = v.clamp(0, 1023) as u16;
    app().ui.lock().unwrap().set_pot(v);
    app().send(Cmd::Act(Action::Adc { ch: 4, value: v }));
}

/// MIDI bytes into the MIDI IN jack.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_midi<'l>(env: JNIEnv<'l>, _c: JClass<'l>, data: JByteArray<'l>, off: jint, len: jint) {
    let all = bytes(&env, &data);
    let (off, len) = (off.max(0) as usize, len.max(0) as usize);
    for &b in all.iter().skip(off).take(len) {
        app().send(Cmd::Act(Action::Midi { b }));
    }
}

/// What is at panel point (x, y): -1 nothing, 0..6 an encoder, 7 the pot, 100 + id a button or key.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_hit(_env: JNIEnv, _c: JClass, x: jfloat, y: jfloat) -> jint {
    match app().ui.lock().unwrap().layout.hit(x, y) {
        Some(panel::Ctl::Enc(i)) => i as jint,
        Some(panel::Ctl::Pot) => 7,
        Some(panel::Ctl::Btn(id)) | Some(panel::Ctl::Key(id)) => 100 + id as jint,
        None => -1,
    }
}

/// The velocity of a touch on key `id` at panel height `y` (1 at its top .. 127 at its bottom).
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_keyVel(_env: JNIEnv, _c: JClass, id: jint, y: jfloat) -> jint {
    app().ui.lock().unwrap().layout.key_vel(id as u8, y) as jint
}

/// Lay the panel out for a screen whose width/height ratio is `aspect`: returns the logical
/// width (the panel is 820 tall) to size the bitmap with.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_setLayout(_env: JNIEnv, _c: JClass, width: jint) -> jint {
    app().ui.lock().unwrap().set_width(width.max(0) as usize) as jint
}

/// The free spot for the app's floating menu button, in logical panel pixels: [x, y].
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_menuPos(env: JNIEnv, _c: JClass) -> jni::sys::jfloatArray {
    let (x, y) = app().ui.lock().unwrap().layout.menu;
    match env.new_float_array(2) {
        Ok(a) => {
            let _ = env.set_float_array_region(&a, 0, &[x, y]);
            a.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// Draw the panel into a direct ByteBuffer of (logical width) * 820 * 4 bytes (RGBA).
/// True when the buffer was rewritten, false when nothing changed (or the buffer is too small).
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_draw<'l>(env: JNIEnv<'l>, _c: JClass<'l>, buf: JByteBuffer<'l>) -> jboolean {
    let (Ok(ptr), Ok(cap)) = (env.get_direct_buffer_address(&buf), env.get_direct_buffer_capacity(&buf)) else { return 0 };
    let mut ui = app().ui.lock().unwrap();
    let n = ui.bytes();
    if cap != n {
        return 0; // a bitmap of another width: the app is about to swap it
    }
    // SAFETY: a direct buffer of exactly that many bytes, owned by the caller for this call.
    let out = unsafe { std::slice::from_raw_parts_mut(ptr, n) };
    let (lcd, leds) = {
        let v = app().shared.view.lock().unwrap();
        (v.lcd.clone(), v.leds)
    };
    ui.draw(&lcd, &leds, out) as jboolean
}

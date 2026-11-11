//! A low-latency AAudio output stream at the device's own rate (API 26+), by plain FFI.

use crate::app::{App, AudioSink};
use crate::engine::{Shared, RATE};
use crate::resample::Resampler;
use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

const FORMAT_PCM_FLOAT: i32 = 2;
const PERFORMANCE_MODE_LOW_LATENCY: i32 = 12;
const CALLBACK_CONTINUE: i32 = 0;

type DataCb = extern "C" fn(stream: *mut c_void, user: *mut c_void, data: *mut c_void, frames: i32) -> i32;
type ErrorCb = extern "C" fn(stream: *mut c_void, user: *mut c_void, error: i32);

#[link(name = "aaudio")]
extern "C" {
    fn AAudio_createStreamBuilder(builder: *mut *mut c_void) -> i32;
    fn AAudioStreamBuilder_setFormat(b: *mut c_void, format: i32);
    fn AAudioStreamBuilder_setChannelCount(b: *mut c_void, channels: i32);
    fn AAudioStreamBuilder_setPerformanceMode(b: *mut c_void, mode: i32);
    fn AAudioStreamBuilder_setDataCallback(b: *mut c_void, cb: DataCb, user: *mut c_void);
    fn AAudioStreamBuilder_setErrorCallback(b: *mut c_void, cb: ErrorCb, user: *mut c_void);
    fn AAudioStreamBuilder_openStream(b: *mut c_void, stream: *mut *mut c_void) -> i32;
    fn AAudioStreamBuilder_delete(b: *mut c_void) -> i32;
    fn AAudioStream_getSampleRate(s: *mut c_void) -> i32;
    fn AAudioStream_getFramesPerBurst(s: *mut c_void) -> i32;
    fn AAudioStream_setBufferSizeInFrames(s: *mut c_void, frames: i32) -> i32;
    fn AAudioStream_requestStart(s: *mut c_void) -> i32;
    fn AAudioStream_requestStop(s: *mut c_void) -> i32;
    fn AAudioStream_close(s: *mut c_void) -> i32;
}

struct Ctx {
    sink: Arc<Mutex<AudioSink>>,
    shared: Arc<Shared>,
}

/// Set by the error callback (a headset unplugged, the device changed): the app reopens.
pub static LOST: AtomicBool = AtomicBool::new(false);

pub struct Stream {
    raw: *mut c_void,
    ctx: *mut Ctx,
    pub rate: i32,
    pub burst: i32,
}
// SAFETY: the pointers are only used under the mutex that owns the Stream.
unsafe impl Send for Stream {}

extern "C" fn on_data(_s: *mut c_void, user: *mut c_void, data: *mut c_void, frames: i32) -> i32 {
    // SAFETY: `user` is the Ctx the Stream owns until after AAudioStream_close returns;
    // `data` holds `frames` stereo float frames (the format and channel count we asked for).
    let (ctx, out) = unsafe { (&*(user as *const Ctx), std::slice::from_raw_parts_mut(data as *mut f32, frames as usize * 2)) };
    App::fill(&ctx.sink, &ctx.shared, out);
    CALLBACK_CONTINUE
}

extern "C" fn on_error(_s: *mut c_void, _user: *mut c_void, _error: i32) {
    LOST.store(true, Ordering::Relaxed);
}

impl Stream {
    pub fn open(sink: Arc<Mutex<AudioSink>>, shared: Arc<Shared>) -> Result<Stream, String> {
        unsafe {
            let mut b = std::ptr::null_mut();
            if AAudio_createStreamBuilder(&mut b) != 0 {
                return Err("AAudio: no stream builder".into());
            }
            let ctx = Box::into_raw(Box::new(Ctx { sink: sink.clone(), shared }));
            AAudioStreamBuilder_setFormat(b, FORMAT_PCM_FLOAT);
            AAudioStreamBuilder_setChannelCount(b, 2);
            AAudioStreamBuilder_setPerformanceMode(b, PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_setDataCallback(b, on_data, ctx as *mut c_void);
            AAudioStreamBuilder_setErrorCallback(b, on_error, ctx as *mut c_void);
            let mut raw = std::ptr::null_mut();
            let r = AAudioStreamBuilder_openStream(b, &mut raw);
            AAudioStreamBuilder_delete(b);
            if r != 0 {
                drop(Box::from_raw(ctx));
                return Err(format!("AAudio: open failed ({r})"));
            }
            let (rate, burst) = (AAudioStream_getSampleRate(raw), AAudioStream_getFramesPerBurst(raw));
            AAudioStream_setBufferSizeInFrames(raw, burst * 2);
            sink.lock().unwrap().rs = Resampler::new(RATE, rate as u32);
            LOST.store(false, Ordering::Relaxed);
            let r = AAudioStream_requestStart(raw);
            if r != 0 {
                AAudioStream_close(raw);
                drop(Box::from_raw(ctx));
                return Err(format!("AAudio: start failed ({r})"));
            }
            Ok(Stream { raw, ctx, rate, burst })
        }
    }
}

impl Drop for Stream {
    fn drop(&mut self) {
        unsafe {
            AAudioStream_requestStop(self.raw);
            AAudioStream_close(self.raw); // no callback runs after this returns
            drop(Box::from_raw(self.ctx));
        }
    }
}

//! The process-wide engine: command queue, shared view, the ring's audio end.

use crate::engine::{self, Cmd, Shared};
use crate::resample::Resampler;
use crate::ui::Ui;
use std::sync::atomic::Ordering;
use std::sync::{mpsc, Arc, Mutex, OnceLock};

pub struct AudioSink {
    pub rx: rtrb::Consumer<[f32; 2]>,
    pub rs: Resampler,
}

pub struct App {
    /// `Sender` is not `Sync` on older toolchains: the mutex makes `App` shareable.
    pub cmds: Mutex<mpsc::Sender<Cmd>>,
    pub shared: Arc<Shared>,
    pub sink: Arc<Mutex<AudioSink>>,
    pub ui: Mutex<Ui>,
}

static APP: OnceLock<App> = OnceLock::new();

pub fn app() -> &'static App {
    APP.get_or_init(|| {
        let (tx, rx) = mpsc::channel();
        let shared = Arc::new(Shared::new());
        let (prod, cons) = rtrb::RingBuffer::new(8192);
        let s = shared.clone();
        std::thread::Builder::new().name("fm1-engine".into()).spawn(move || engine::run(s, rx, prod)).unwrap();
        App {
            cmds: Mutex::new(tx),
            shared,
            sink: Arc::new(Mutex::new(AudioSink { rx: cons, rs: Resampler::new(engine::RATE, 48_000) })),
            ui: Mutex::new(Ui::new()),
        }
    })
}

impl App {
    pub fn send(&self, c: Cmd) {
        let _ = self.cmds.lock().unwrap().send(c);
    }

    pub fn boot(&self, pkg: Vec<u8>, saved: Vec<u8>, syx: Vec<u8>, mhz: u32, jit: bool) -> Result<(), String> {
        let (reply, got) = mpsc::channel();
        self.cmds
            .lock()
            .unwrap()
            .send(Cmd::Boot { pkg, saved, syx, mhz, jit, reply })
            .map_err(|_| "engine stopped".to_owned())?;
        got.recv().map_err(|_| "engine stopped".to_owned())?
    }

    /// The audio callback's body: never blocks.
    pub fn fill(sink: &Mutex<AudioSink>, shared: &Shared, out: &mut [f32]) {
        match sink.try_lock() {
            Ok(mut s) => {
                let AudioSink { rx, rs } = &mut *s;
                let missing = rs.process(out, || rx.pop().ok());
                if missing > 0 {
                    shared.starved.fetch_add(missing as u64, Ordering::Relaxed);
                }
                shared.fill_min.fetch_min(rx.slots() as u32, Ordering::Relaxed);
            }
            Err(_) => out.fill(0.0),
        }
    }
}

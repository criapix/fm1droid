//! The emulated FM-1 on a thread of its own, keeping `lead` frames of its 44.1 kHz audio in a
//! ring ahead of the audio callback. A cut-down vendor/fm1-emu/crates/fm1-plugin/src/engine.rs.

use crate::flashrec;
use fm1_core::{Bus, Cpu};
use fm1_soc::{machine, Action, Board, Soc};
use std::sync::atomic::AtomicU64;
use std::sync::{mpsc, Arc, Mutex};
use std::time::{Duration, Instant};

const RAM_BASE: u32 = 0x01c0_0000;
const RAM_SIZE: usize = 1 << 20;
/// The firmware's output rate.
pub const RATE: u32 = 44_100;

pub enum Cmd {
    /// Boot a package (.fwsc) with a save (flashrec records, may be empty) and optionally a
    /// DX7 32-voice bank (.syx, may be empty) for the stock firmware's slots 1-32.
    Boot { pkg: Vec<u8>, saved: Vec<u8>, syx: Vec<u8>, mhz: u32, reply: mpsc::Sender<Result<(), String>> },
    Act(Action),
    /// Frames to keep in the ring.
    Lead(usize),
    Pause(bool),
    Quit,
}

pub struct View {
    pub lcd: Vec<u16>,
    pub leds: [f32; 41],
    pub booted: bool,
    pub halted: bool,
    /// emulated time over time spent emulating, over the last second
    pub speed: f32,
    /// the save (flashrec records) and a counter bumped each time it changes
    pub flash: Vec<u8>,
    pub flash_rev: u32,
}

pub struct Shared {
    pub view: Mutex<View>,
    /// frames the audio callback found missing
    pub starved: AtomicU64,
}

impl Shared {
    pub fn new() -> Self {
        Shared {
            view: Mutex::new(View {
                lcd: vec![0; 240 * 240], leds: [0.0; 41], booted: false, halted: false,
                speed: 0.0, flash: Vec::new(), flash_rev: 0,
            }),
            starved: AtomicU64::new(0),
        }
    }
}

struct Machine {
    cpu: Cpu,
    cpu1: Option<Cpu>,
    soc: Soc,
    pending_tick: bool,
    halted: bool,
    /// the flash as the package gives it, to find what the firmware wrote since
    flash0: Vec<u8>,
}

/// Check a package without booting it: the firmware's identity, or why it is refused.
pub fn probe(pkg: &[u8]) -> Result<String, String> {
    let st = std::panic::catch_unwind(|| fm1_soc::stock_from_package(pkg, None))
        .map_err(|_| "malformed firmware package".to_owned())??;
    if st.board != Board::Fm1 {
        return Err("this package is for another device (SMK-37), not the FM-1".into());
    }
    Ok(st.identity.to_string())
}

/// Write whole sectors to the flash and to the memory-mapped (XIP) view of it.
fn write_sector(soc: &mut Soc, at: usize, data: &[u8]) {
    soc.flash.mem[at..at + data.len()].copy_from_slice(data);
    let from = at.max(fm1_soc::XIP_FLASH_OFFSET);
    let end = (at + data.len()).min(fm1_soc::XIP_FLASH_OFFSET + soc.xip.len());
    if from < end {
        soc.xip[from - fm1_soc::XIP_FLASH_OFFSET..end - fm1_soc::XIP_FLASH_OFFSET]
            .copy_from_slice(&data[from - at..end - at]);
    }
}

impl Machine {
    fn boot(pkg: &[u8], saved: &[u8], syx: &[u8], mhz: u32) -> Result<Self, String> {
        let sectors = flashrec::parse(saved, fm1_soc::FLASH_SIZE)?;
        let st = std::panic::catch_unwind(|| fm1_soc::stock_from_package(pkg, None))
            .map_err(|_| "malformed firmware package".to_owned())??;
        if st.board != Board::Fm1 {
            return Err("this package is for another device (SMK-37), not the FM-1".into());
        }
        let mut soc = Soc::new(RAM_SIZE, st.xip);
        soc.periph.hz = mhz as u64 * 1_000_000;
        soc.periph.skip_idle = true;
        let mut cpu = Cpu::new(st.entry);
        cpu.jit_on = true;
        cpu.user = true; // the SPL hands cpu0 over in user mode
        let params = RAM_BASE + 0x7fc00;
        soc.write32(params, params + 0x40);
        cpu.regs[0] = fm1_soc::stock_boot(&mut soc, &st.flash, params, st.board);
        let flash0 = soc.flash.mem.clone();
        for (at, data) in &sectors {
            write_sector(&mut soc, *at as usize, data);
        }
        if !syx.is_empty() {
            let usr = st.usr.ok_or("this firmware has no DX7 patch bank")?;
            let mut flash = soc.flash.mem.clone();
            fm1_soc::fwsc::usr_load_syx(&mut flash, usr, st.usr_layout, syx, 0)?;
            for at in (0..flash.len()).step_by(flashrec::SECTOR) {
                if flash[at..at + flashrec::SECTOR] != soc.flash.mem[at..at + flashrec::SECTOR] {
                    let sector = flash[at..at + flashrec::SECTOR].to_vec();
                    write_sector(&mut soc, at, &sector);
                }
            }
        }
        Ok(Machine { cpu, cpu1: None, soc, pending_tick: false, halted: false, flash0 })
    }

    fn run(&mut self, n: u32) {
        let jit = self.cpu.jit_on;
        let Machine { cpu, cpu1, soc, pending_tick, .. } = self;
        if machine::run_dual(cpu1, soc, n, RAM_SIZE, jit, |soc, k| machine::run_blocks(cpu, soc, k, pending_tick)).is_err() {
            self.halted = true;
        }
    }
}

pub fn run(shared: Arc<Shared>, cmds: mpsc::Receiver<Cmd>, mut out: rtrb::Producer<[f32; 2]>) {
    let mut m: Option<Machine> = None;
    let (mut lead, mut paused) = (768usize, false);
    let (mut last_view, mut last_flash) = (Instant::now(), Instant::now());
    let mut last_rec: Vec<u8> = Vec::new();
    let (mut speed_t, mut speed_ms, mut busy) = (Instant::now(), 0u64, Duration::ZERO);
    loop {
        loop {
            match cmds.try_recv() {
                Ok(Cmd::Boot { pkg, saved, syx, mhz, reply }) => {
                    let result = Machine::boot(&pkg, &saved, &syx, mhz).map(|next| {
                        m = Some(next);
                        last_rec.clear();
                        let mut v = shared.view.lock().unwrap();
                        (v.booted, v.halted, v.speed) = (true, false, 0.0);
                        v.flash.clear(); // the previous firmware's sectors must not leak into this one's save
                        (speed_t, speed_ms, busy) = (Instant::now(), 0, Duration::ZERO);
                    });
                    let _ = reply.send(result);
                }
                Ok(Cmd::Act(a)) => {
                    if let Some(m) = m.as_mut() {
                        m.soc.periph.apply(&a);
                    }
                }
                Ok(Cmd::Lead(n)) => lead = n.clamp(128, 4096),
                Ok(Cmd::Pause(p)) => paused = p,
                Ok(Cmd::Quit) | Err(mpsc::TryRecvError::Disconnected) => return,
                Err(mpsc::TryRecvError::Empty) => break,
            }
        }
        let Some(mm) = m.as_mut() else {
            std::thread::sleep(Duration::from_millis(5));
            continue;
        };
        if paused {
            std::thread::sleep(Duration::from_millis(5));
            continue;
        }
        let fill = out.buffer().capacity() - out.slots();
        if mm.halted || fill >= lead {
            std::thread::sleep(Duration::from_micros(500));
        } else {
            let t0 = Instant::now();
            mm.run(20_000);
            busy += t0.elapsed();
            for f in std::mem::take(&mut mm.soc.periph.audio).chunks_exact(2) {
                let _ = out.push([f[0] as f32 / 32768.0, f[1] as f32 / 32768.0]);
            }
        }
        if last_view.elapsed() >= Duration::from_millis(16) {
            last_view = Instant::now();
            let mut v = shared.view.lock().unwrap();
            v.lcd.copy_from_slice(&mm.soc.lcd.fb);
            v.leds = mm.soc.periph.led_take();
            v.halted = mm.halted;
            if speed_t.elapsed() >= Duration::from_secs(1) {
                let ms = mm.soc.periph.ms();
                if speed_ms > 0 && busy > Duration::ZERO {
                    v.speed = ms.saturating_sub(speed_ms) as f32 / busy.as_secs_f32() / 1000.0;
                }
                (speed_t, speed_ms, busy) = (Instant::now(), ms.max(1), Duration::ZERO);
            }
        }
        if last_flash.elapsed() >= Duration::from_secs(2) && !mm.soc.flash.busy() {
            last_flash = Instant::now();
            let rec = flashrec::diff(&mm.soc.flash.mem, &mm.flash0);
            if rec != last_rec {
                let mut v = shared.view.lock().unwrap();
                v.flash = rec.clone();
                v.flash_rev = v.flash_rev.wrapping_add(1);
                last_rec = rec;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn start() -> (mpsc::Sender<Cmd>, Arc<Shared>, rtrb::Consumer<[f32; 2]>) {
        let (tx, rx) = mpsc::channel();
        let shared = Arc::new(Shared::new());
        let (prod, cons) = rtrb::RingBuffer::new(8192);
        let s = shared.clone();
        std::thread::spawn(move || run(s, rx, prod));
        (tx, shared, cons)
    }

    fn boot(tx: &mpsc::Sender<Cmd>, pkg: Vec<u8>, saved: Vec<u8>) -> Result<(), String> {
        let (rtx, rrx) = mpsc::channel();
        tx.send(Cmd::Boot { pkg, saved, syx: vec![], mhz: 240, reply: rtx }).unwrap();
        rrx.recv_timeout(Duration::from_secs(30)).expect("boot reply")
    }

    /// Take `frames` frames as fast as the machine makes them; returns the peak level.
    fn drain(cons: &mut rtrb::Consumer<[f32; 2]>, frames: usize) -> f32 {
        let (mut got, mut peak, t0) = (0, 0f32, Instant::now());
        while got < frames {
            assert!(t0.elapsed() < Duration::from_secs(120), "machine too slow: {got}/{frames}");
            match cons.pop() {
                Ok(f) => {
                    got += 1;
                    peak = peak.max(f[0].abs()).max(f[1].abs());
                }
                Err(_) => std::thread::sleep(Duration::from_millis(1)),
            }
        }
        peak
    }

    fn felucca() -> Vec<u8> {
        std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/testdata/felucca.fwsc"))
            .expect("run tools/fetch-testfw.ps1 first")
    }

    #[test]
    fn garbage_is_refused_without_killing_the_thread() {
        let (tx, shared, _cons) = start();
        assert!(boot(&tx, vec![1, 2, 3], vec![]).is_err());
        assert!(!shared.view.lock().unwrap().booted);
        assert!(boot(&tx, vec![0; 100_000], vec![]).is_err());
    }

    #[test]
    fn probe_accepts_a_real_package_and_refuses_garbage() {
        assert!(probe(&felucca()).is_ok());
        assert!(probe(&[0; 64]).is_err());
    }

    #[test]
    fn a_real_firmware_boots_draws_and_plays_a_key() {
        let (tx, shared, mut cons) = start();
        let t0 = Instant::now();
        boot(&tx, felucca(), vec![]).unwrap();
        drain(&mut cons, RATE as usize * 4); // 4 s emulados: boot e tela inicial
        {
            let v = shared.view.lock().unwrap();
            assert!(v.booted && !v.halted);
            assert!(v.lcd.iter().any(|&p| p != 0), "the screen stayed black");
        }
        tx.send(Cmd::Act(Action::Adc { ch: 4, value: 900 })).unwrap(); // MASTER
        tx.send(Cmd::Act(Action::KeyVel { vel: 110 })).unwrap();
        tx.send(Cmd::Act(Action::Key { id: 21, down: true })).unwrap();
        let peak = drain(&mut cons, RATE as usize * 2);
        tx.send(Cmd::Act(Action::Key { id: 21, down: false })).unwrap();
        println!("6 s emulados em {:?} de parede", t0.elapsed());
        assert!(peak > 0.005, "a held key made no sound (peak {peak})");
        tx.send(Cmd::Quit).unwrap();
    }

    #[test]
    fn a_restored_save_comes_back_in_the_next_snapshot() {
        let (tx, shared, mut cons) = start();
        // um setor na área de dados do fim do flash, que o pacote não ocupa
        let at = (fm1_soc::FLASH_SIZE - flashrec::SECTOR) as u32;
        let mut saved = at.to_le_bytes().to_vec();
        saved.extend(vec![0x5A; flashrec::SECTOR]);
        boot(&tx, felucca(), saved.clone()).unwrap();
        // o snapshot sai a cada 2 s de tempo de PAREDE: mantém a máquina andando e espera
        let t0 = Instant::now();
        while shared.view.lock().unwrap().flash_rev == 0 {
            assert!(t0.elapsed() < Duration::from_secs(30), "no flash snapshot appeared");
            drain(&mut cons, RATE as usize / 2);
        }
        let v = shared.view.lock().unwrap();
        assert!(v.flash_rev >= 1);
        let found = flashrec::parse(&v.flash, fm1_soc::FLASH_SIZE).unwrap();
        assert!(found.iter().any(|(a, d)| *a == at && d.iter().all(|b| *b == 0x5A)));
    }
}

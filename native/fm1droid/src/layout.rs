//! A panel layout that adapts to the screen's shape. The panel is always 820 logical pixels
//! tall; its width follows the screen (1280 up to 1900), and the blocks of controls are spread
//! over it: the side clusters grow, the keyboard stretches, the screen stays in the middle.
//! Drawing and hit-testing both come from the same `Layout`, so they cannot disagree.
//! Primitives (rrect, knob, text...) are the vendor panel's; positions are ours.

use crate::panel::{self, Ctl};

pub const H: usize = 820;
pub const MIN_W: usize = 1280;
pub const MAX_W: usize = 1900;

const KEY_TOP: u32 = 0x6a5557;
const KEY_BOT: u32 = 0x4f3f42;
const KEY_LINE: u32 = 0x8f7c7e;

pub struct Layout {
    pub w: usize,
    /// text size (pixels per font dot) for the knob labels
    pub label_scale: usize,
    pub lcd: (f32, f32),
    pub bezel: [f32; 4],
    /// (cx, cy, r), in the order of `panel::ENCS`
    pub encs: [(f32, f32, f32); 7],
    pub pot: (f32, f32, f32),
    /// (label, matrix id, x, y, w, h)
    pub buttons: Vec<(&'static str, u8, f32, f32, f32, f32)>,
    /// (matrix id, x, y, w, h, black)
    pub keys: Vec<(u8, f32, f32, f32, f32, bool)>,
    pub tray_oct: [f32; 4],
    pub tray_btn: [f32; 4],
    pub tray_keys: [f32; 4],
    pub play_rec: [f32; 4],
    /// where the app's floating menu button sits (a free spot, in logical pixels)
    pub menu: (f32, f32),
}

impl Layout {
    pub fn new(w: usize) -> Layout {
        let w = w.clamp(MIN_W, MAX_W);
        let wf = w as f32;
        let m = 34.0;
        // how much of the extra width there is: 0 at 1280, 1 at 1777 (a 19.5:9 phone)
        let t = ((wf - 1280.0) / 497.0).clamp(0.0, 1.0);
        // knob/button sizes: left cluster is limited by height, right by width
        let (sl, sr) = (1.0 + 0.28 * t, 1.0 + 0.30 * t);
        let right_w = 360.0 * sr;
        // both side blocks end up equally wide (the left one spreads its knobs apart, not bigger)
        let left_w = 260.0 + (right_w - 260.0) * t;
        let centre_w = 516.0;
        let cx0 = 352.0 + ((wf - centre_w) / 2.0 - 352.0) * t; // the screen drifts to the middle
        let g = ((wf - 2.0 * m - left_w - right_w - centre_w) / 2.0).max(0.0);
        let (lx0, rx0) = (m, wf - m - right_w);
        let kx = left_w / 260.0;
        // the original coordinates (panel.rs) mapped into each block, centred on the top area
        let lx = |x: f32| lx0 + (x - 50.0) * kx;
        let ly = |y: f32| 281.0 + (y - 259.5) * sl;
        let rx = |x: f32| rx0 + (x - 896.0) * sr;
        let ry = |y: f32| 281.0 + (y - 221.0) * sr;

        let mut encs = [(0.0, 0.0, 0.0); 7];
        for (i, e) in panel::ENCS.iter().enumerate() {
            encs[i] = if i < 3 { (lx(e.2), ly(e.3), e.4 * sl) } else { (rx(e.2), ry(e.3), e.4 * sr) };
        }
        let pot = (lx(panel::POT.0), ly(panel::POT.1), panel::POT.2 * sl);

        let buttons = panel::buttons()
            .into_iter()
            .map(|(name, id, x, y, bw, bh)| {
                let (x, y, bw, bh) = (x as f32, y as f32, bw as f32, bh as f32);
                if id < 2 { (name, id, lx(x), ly(y), bw * kx, bh * sl) } else { (name, id, rx(x), ry(y), bw * sr, bh * sr) }
            })
            .collect();

        // the keyboard tray spans the whole body; 16 white keys share its width
        let tray = [m, 562.0, wf - 2.0 * m, 236.0];
        let p = (tray[2] - 54.0) / 15.85;
        let (kw, gap, x0) = (0.85 * p, 0.15 * p, tray[0] + 27.0);
        let mut keys = Vec::new();
        let whites = [0u8, 2, 4, 6, 7, 9, 11, 12, 14, 16, 18, 19, 21, 23, 24, 26];
        for (i, n) in whites.iter().enumerate() {
            keys.push((14 + n, x0 + i as f32 * p, 680.0, kw, 106.0, false));
        }
        let blacks = [(1u8, 0usize), (3, 1), (5, 2), (8, 4), (10, 5), (13, 7), (15, 8), (17, 9), (20, 11), (22, 12), (25, 14)];
        for (n, after) in blacks {
            let cx = x0 + (after + 1) as f32 * p - gap / 2.0;
            keys.push((14 + n, cx - kw / 2.0, 574.0, kw, 94.0, true));
        }

        let menu = if g >= 90.0 { (cx0 - g / 2.0, 80.0) } else { (wf - 40.0, 40.0) };
        Layout {
            w,
            label_scale: if sl > 1.15 { 3 } else { 2 },
            lcd: (cx0 + 18.0, 42.0),
            bezel: [cx0, 24.0, 516.0, 516.0],
            encs,
            pot,
            buttons,
            keys,
            tray_oct: [lx(50.0), ly(388.0), left_w, 64.0 * sl],
            tray_btn: [rx(896.0), ry(222.0), 360.0 * sr, 160.0 * sr],
            tray_keys: tray,
            play_rec: [rx(1134.0), ry(302.0), 112.0 * sr, 72.0 * sr],
            menu,
        }
    }

    /// What is at (x, y) in logical pixels.
    pub fn hit(&self, x: f32, y: f32) -> Option<Ctl> {
        for (i, (cx, cy, r)) in self.encs.iter().enumerate() {
            if (x - cx).powi(2) + (y - cy).powi(2) <= (r + 6.0).powi(2) {
                return Some(Ctl::Enc(i));
            }
        }
        let (px, py, pr) = self.pot;
        if (x - px).powi(2) + (y - py).powi(2) <= (pr + 6.0).powi(2) {
            return Some(Ctl::Pot);
        }
        let inside = |bx: f32, by: f32, bw: f32, bh: f32| x >= bx && x < bx + bw && y >= by && y < by + bh;
        for &(_, id, bx, by, bw, bh) in &self.buttons {
            if inside(bx, by, bw, bh) {
                return Some(Ctl::Btn(id));
            }
        }
        // black keys first: they sit above the white row
        let mut ks: Vec<_> = self.keys.iter().collect();
        ks.sort_by_key(|k| !k.5);
        for &&(id, kx, ky, kw, kh, _) in &ks {
            if inside(kx, ky, kw, kh) {
                return Some(Ctl::Key(id));
            }
        }
        None
    }

    /// Velocity of a touch on key `id` at height `y`: 1 at its top, 127 at its bottom.
    pub fn key_vel(&self, id: u8, y: f32) -> u8 {
        self.keys.iter().find(|k| k.0 == id).map_or(100, |&(_, _, ky, _, kh, _)| {
            (1.0 + 126.0 * ((y - ky) / kh).clamp(0.0, 1.0)).round() as u8
        })
    }

    /// The parts that never change: body, trays, bezel, labels.
    pub fn base(&self) -> Vec<u32> {
        panel::canvas(self.w);
        let mut b = vec![panel::ROOM; self.w * H];
        panel::rrect(&mut b, 14.0, 14.0, (self.w - 28) as f32, (H - 28) as f32, 30.0, panel::BODY_TOP, panel::BODY_BOT);
        let [bx, by, bw, bh] = self.bezel;
        panel::rrect(&mut b, bx, by, bw, bh, 22.0, 0x0e0e10, 0x0b0b0c);
        for (t, r) in [(self.tray_oct, 14.0), (self.tray_btn, 16.0)] {
            panel::rrect(&mut b, t[0], t[1], t[2], t[3], r, panel::TRAY, panel::TRAY);
        }
        let k = self.tray_keys;
        panel::rrect(&mut b, k[0], k[1], k[2], k[3], 26.0, 0x2f2f33, 0x27272a);
        let ls = self.label_scale;
        let lab = (ls * 7) as f32 + 8.0;
        panel::text(&mut b, self.pot.0, self.pot.1 - self.pot.2 - lab, "MASTER", ls, panel::SILK);
        for (i, e) in panel::ENCS.iter().enumerate() {
            let (cx, cy, r) = self.encs[i];
            panel::text(&mut b, cx, cy - r - lab, e.0, ls, panel::SILK);
        }
        // PLAY / REC outlined, as on the real panel
        let p = self.play_rec;
        panel::rrect(&mut b, p[0], p[1], p[2], p[3], 10.0, 0x3c6b43, 0x3c6b43);
        panel::rrect(&mut b, p[0] + 2.0, p[1] + 2.0, p[2] - 4.0, p[3] - 4.0, 9.0, panel::TRAY, panel::TRAY);
        panel::text(&mut b, self.w as f32 / 2.0, 548.0, "FM-1", 1, 0x8c8a85);
        b
    }

    pub fn draw(&self, buf: &mut [u32], base: &[u32], st: &panel::State) {
        panel::canvas(self.w);
        buf.copy_from_slice(base);
        let (lx, ly) = (self.lcd.0 as usize, self.lcd.1 as usize);
        for y in 0..240 {
            for x in 0..240 {
                let p = st.lcd[y * 240 + x] as u32;
                let c = ((p >> 11) * 255 / 31) << 16 | (((p >> 5) & 63) * 255 / 63) << 8 | (p & 31) * 255 / 31;
                let o = (ly + 2 * y) * self.w + lx + 2 * x;
                buf[o] = c;
                buf[o + 1] = c;
                buf[o + self.w] = c;
                buf[o + self.w + 1] = c;
            }
        }
        // knobs: encoders turn 18 degrees a click; the pot sweeps 270 degrees
        for (i, &(cx, cy, r)) in self.encs.iter().enumerate() {
            let glow = (st.turning[i] as f32 / 10.0).min(1.0);
            panel::knob(buf, cx, cy, r, (st.enc_clicks[i] as f32 * 18.0).to_radians(), glow);
        }
        let pa = (-135.0 + 270.0 * st.pot as f32 / 1023.0).to_radians();
        panel::knob(buf, self.pot.0, self.pot.1, self.pot.2, pa, (st.turning[7] as f32 / 10.0).min(1.0));
        let bright = |f: f32| (f / 0.08).clamp(0.0, 1.0);
        for &(name, id, x, y, w, h) in &self.buttons {
            let down = st.down[id as usize];
            let led = bright(st.leds[id as usize]);
            let tint = if id == 12 { panel::GREEN } else { panel::AMBER };
            let off = if down { 2.0 } else { 0.0 };
            panel::rrect(buf, x + 1.0, y + 3.0, w, h, 9.0, 0x1c1d20, 0x1c1d20);
            let (top, bot) = (panel::mix(panel::BTN_TOP, tint, led * 0.85), panel::mix(panel::BTN_BOT, tint, led * 0.7));
            panel::rrect(buf, x, y + off, w, h, 9.0, top, bot);
            let ink = if led > 0.5 { 0x2a1a03 } else { panel::BTN_INK };
            panel::text(buf, x + w / 2.0, y + h / 2.0 - 7.0 + off, name, 2, ink);
        }
        for &(id, x, y, w, h, _) in &self.keys {
            let down = st.down[id as usize];
            let led = bright(st.leds[id as usize]);
            let off = if down { 3.0 } else { 0.0 };
            panel::rrect(buf, x + 1.0, y + 4.0, w, h, w / 2.0, 0x1a1a1c, 0x1a1a1c);
            let heat = if down { 0.55 } else { led * 0.6 };
            panel::rrect(buf, x, y + off, w, h, w / 2.0, panel::mix(KEY_TOP, panel::AMBER, heat), panel::mix(KEY_BOT, panel::AMBER, heat * 0.8));
            let line = panel::mix(KEY_LINE, 0xffe2b0, if down { 1.0 } else { led });
            panel::rrect(buf, x + w / 2.0 - 2.5, y + 18.0 + off, 5.0, h - 36.0, 2.5, line, line);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const WIDTHS: [usize; 5] = [1280, 1500, 1777, 1900, 5000];

    #[test]
    fn width_is_clamped() {
        assert_eq!(Layout::new(100).w, MIN_W);
        assert_eq!(Layout::new(5000).w, MAX_W);
    }

    #[test]
    fn every_control_hits_at_its_own_centre() {
        for w in WIDTHS {
            let l = Layout::new(w);
            for (i, &(cx, cy, _)) in l.encs.iter().enumerate() {
                assert_eq!(l.hit(cx, cy), Some(Ctl::Enc(i)), "enc {i} at width {w}");
            }
            assert_eq!(l.hit(l.pot.0, l.pot.1), Some(Ctl::Pot), "pot at width {w}");
            for &(name, id, x, y, bw, bh) in &l.buttons {
                assert_eq!(l.hit(x + bw / 2.0, y + bh / 2.0), Some(Ctl::Btn(id)), "button {name} at width {w}");
            }
            for &(id, x, y, kw, kh, _) in &l.keys {
                assert_eq!(l.hit(x + kw / 2.0, y + kh / 2.0), Some(Ctl::Key(id)), "key {id} at width {w}");
            }
        }
    }

    #[test]
    fn nothing_overlaps_and_everything_is_on_the_body() {
        for w in WIDTHS {
            let l = Layout::new(w);
            let mut rects: Vec<(String, f32, f32, f32, f32)> = Vec::new();
            for (i, &(cx, cy, r)) in l.encs.iter().enumerate() {
                rects.push((format!("enc{i}"), cx - r, cy - r, 2.0 * r, 2.0 * r));
            }
            rects.push(("pot".into(), l.pot.0 - l.pot.2, l.pot.1 - l.pot.2, 2.0 * l.pot.2, 2.0 * l.pot.2));
            for &(n, _, x, y, bw, bh) in &l.buttons {
                rects.push((n.into(), x, y, bw, bh));
            }
            for &(id, x, y, kw, kh, _) in &l.keys {
                rects.push((format!("key{id}"), x, y, kw, kh));
            }
            for (n, x, y, rw, rh) in &rects {
                assert!(*x >= 14.0 && *y >= 14.0 && x + rw <= l.w as f32 - 14.0 && y + rh <= H as f32 - 14.0, "{n} off the body at {w}");
            }
            for i in 0..rects.len() {
                for j in i + 1..rects.len() {
                    let (a, b) = (&rects[i], &rects[j]);
                    let apart = a.1 + a.3 <= b.1 || b.1 + b.3 <= a.1 || a.2 + a.4 <= b.2 || b.2 + b.4 <= a.2;
                    assert!(apart, "{} overlaps {} at width {w}", a.0, b.0);
                }
            }
            // the clusters stay clear of the keyboard tray and the screen's bezel
            for (n, x, y, rw, rh) in &rects {
                if !n.starts_with("key") {
                    assert!(y + rh < l.tray_keys[1], "{n} runs into the keyboard at {w}");
                    let b = l.bezel;
                    assert!(x + rw <= b[0] || *x >= b[0] + b[2], "{n} runs into the screen at {w}");
                }
            }
        }
    }

    #[test]
    fn keyboard_spans_the_body_and_the_screen_is_centred() {
        let l = Layout::new(1777);
        let last = l.keys.iter().filter(|k| !k.5).map(|k| k.1 + k.3).fold(0.0, f32::max);
        assert!(last > 1777.0 - 34.0 - 40.0, "keys stop short: {last}");
        assert!((l.bezel[0] + l.bezel[2] / 2.0 - 1777.0 / 2.0).abs() < 20.0, "screen off centre");
    }

    #[test]
    fn key_velocity_runs_from_top_to_bottom() {
        let l = Layout::new(1777);
        let (id, _, y, _, h, _) = l.keys.iter().find(|k| !k.5).copied().unwrap();
        assert_eq!(l.key_vel(id, y), 1);
        assert_eq!(l.key_vel(id, y + h), 127);
    }
}

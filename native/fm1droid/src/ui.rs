//! The panel state and its drawing, on a layout that adapts to the screen (see layout.rs).

use crate::layout::{Layout, H};
use crate::panel;

pub struct Ui {
    pub layout: Layout,
    base: Vec<u32>,
    buf: Vec<u32>,
    pub down: [bool; 41],
    pub enc_clicks: [i32; 7],
    pub pot: u16,
    /// frames of glow left: the seven encoders, then the pot
    pub turning: [u32; 8],
    /// something changed since the last draw
    pub dirty: bool,
    last_lcd: Vec<u16>,
    last_leds: [u8; 41],
}

impl Ui {
    pub fn new() -> Self {
        let layout = Layout::new(crate::layout::MIN_W);
        let base = layout.base();
        Ui {
            buf: vec![0; base.len()], base, layout, down: [false; 41], enc_clicks: [0; 7], pot: 900, turning: [0; 8],
            dirty: true, last_lcd: Vec::new(), last_leds: [0; 41],
        }
    }

    pub fn width(&self) -> usize {
        self.layout.w
    }

    /// Re-lay the panel out for a screen `w` logical pixels wide (clamped); returns the width used.
    pub fn set_width(&mut self, w: usize) -> usize {
        let w = w.clamp(crate::layout::MIN_W, crate::layout::MAX_W);
        if w != self.layout.w {
            self.layout = Layout::new(w);
            self.base = self.layout.base();
            self.buf = vec![0; self.base.len()];
        }
        self.dirty = true;
        self.layout.w
    }

    pub fn turn(&mut self, i: usize, clicks: i32) {
        self.enc_clicks[i] += clicks;
        self.turning[i] = 10;
        self.dirty = true;
    }

    pub fn set_down(&mut self, id: usize, down: bool) {
        if self.down[id] != down {
            self.down[id] = down;
            self.dirty = true;
        }
    }

    pub fn set_pot(&mut self, v: u16) {
        self.pot = v;
        self.turning[7] = 10;
        self.dirty = true;
    }

    /// Draw into `out` as RGBA bytes (width * 820 * 4), the order of an ARGB_8888 Bitmap's buffer.
    /// Returns false (and leaves `out` alone) when nothing changed since the last draw.
    pub fn draw(&mut self, lcd: &[u16], leds: &[f32; 41], out: &mut [u8]) -> bool {
        let mut q = [0u8; 41];
        for (o, l) in q.iter_mut().zip(leds) {
            *o = (l * 16.0).round().clamp(0.0, 255.0) as u8;
        }
        if q != self.last_leds || self.last_lcd != lcd {
            self.last_leds = q;
            self.last_lcd.clear();
            self.last_lcd.extend_from_slice(lcd);
            self.dirty = true;
        }
        if !self.dirty && self.turning.iter().all(|t| *t == 0) {
            return false;
        }
        let st = panel::State { lcd, enc_clicks: &self.enc_clicks, pot: self.pot, down: &self.down, leds, turning: &self.turning };
        self.layout.draw(&mut self.buf, &self.base, &st);
        for (px, o) in self.buf.iter().zip(out.chunks_exact_mut(4)) {
            o[0] = (px >> 16) as u8;
            o[1] = (px >> 8) as u8;
            o[2] = *px as u8;
            o[3] = 255;
        }
        for t in self.turning.iter_mut() {
            *t = t.saturating_sub(1);
        }
        self.dirty = false;
        true
    }

    pub fn bytes(&self) -> usize {
        self.layout.w * H * 4
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lcd_centre(ui: &Ui) -> (usize, usize) {
        ((ui.layout.lcd.0 + 240.0) as usize, (ui.layout.lcd.1 + 240.0) as usize)
    }

    #[test]
    fn draws_the_lcd_where_the_layout_puts_it_at_any_width() {
        for w in [1280, 1777] {
            let mut ui = Ui::new();
            ui.set_width(w);
            let lcd = vec![0xF800u16; 240 * 240]; // vermelho puro em RGB565
            let mut out = vec![0u8; ui.bytes()];
            assert!(ui.draw(&lcd, &[0.0; 41], &mut out));
            let (cx, cy) = lcd_centre(&ui);
            let px = &out[(cy * ui.width() + cx) * 4..][..4];
            assert!(px[0] > 200 && px[1] < 60 && px[2] < 60 && px[3] == 255, "{w}: {px:?}");
        }
    }

    #[test]
    fn a_turned_knob_glows_and_fades() {
        let mut ui = Ui::new();
        ui.turn(3, 2);
        assert_eq!((ui.enc_clicks[3], ui.turning[3]), (2, 10));
        let mut out = vec![0u8; ui.bytes()];
        ui.draw(&vec![0; 240 * 240], &[0.0; 41], &mut out);
        assert_eq!(ui.turning[3], 9);
    }

    #[test]
    fn an_unchanged_panel_is_not_redrawn() {
        let mut ui = Ui::new();
        let lcd = vec![0u16; 240 * 240];
        let mut out = vec![0u8; ui.bytes()];
        assert!(ui.draw(&lcd, &[0.0; 41], &mut out), "first draw");
        assert!(!ui.draw(&lcd, &[0.0; 41], &mut out), "nothing changed");
        ui.set_down(20, true);
        assert!(ui.draw(&lcd, &[0.0; 41], &mut out), "a key went down");
        let mut lit = [0.0f32; 41];
        lit[9] = 0.08;
        assert!(ui.draw(&lcd, &lit, &mut out), "a LED lit");
        assert!(!ui.draw(&lcd, &lit, &mut out));
    }

    #[test]
    fn a_new_width_forces_a_redraw_and_resizes_the_buffer() {
        let mut ui = Ui::new();
        let lcd = vec![0u16; 240 * 240];
        let mut out = vec![0u8; ui.bytes()];
        ui.draw(&lcd, &[0.0; 41], &mut out);
        assert_eq!(ui.set_width(1777), 1777);
        assert_eq!(ui.bytes(), 1777 * H * 4);
        let mut big = vec![0u8; ui.bytes()];
        assert!(ui.draw(&lcd, &[0.0; 41], &mut big));
    }
}

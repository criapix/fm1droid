//! The panel as drawn (vendor fm1-live's panel.rs) and the state the drawing needs.

use crate::panel;

pub const W: usize = panel::W;
pub const H: usize = panel::H;

pub struct Ui {
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
        let base = panel::base();
        Ui {
            buf: vec![0; base.len()], base, down: [false; 41], enc_clicks: [0; 7], pot: 900, turning: [0; 8],
            dirty: true, last_lcd: Vec::new(), last_leds: [0; 41],
        }
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

    /// Draw into `out` as RGBA bytes (W * H * 4), the order of an ARGB_8888 Bitmap's buffer.
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
        panel::draw(&mut self.buf, &self.base, &st);
        for (px, o) in self.buf.iter().zip(out.chunks_exact_mut(4)) {
            o[0] = (px >> 16) as u8;
            o[1] = (px >> 8) as u8;
            o[2] = *px as u8;
            o[3] = 255;
        }
        for t in self.turning.iter_mut() {
            *t = t.saturating_sub(1);
        }
        // one more frame while a glow is fading, so the last step is drawn
        self.dirty = false;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The LCD is drawn at 2x from (LCD_X, LCD_Y): its middle pixel.
    fn lcd_centre() -> (usize, usize) {
        (panel::LCD_X + 240, panel::LCD_Y + 240)
    }

    #[test]
    fn draws_the_lcd_where_the_panel_puts_it() {
        let mut ui = Ui::new();
        let lcd = vec![0xF800u16; 240 * 240]; // vermelho puro em RGB565
        let mut out = vec![0u8; W * H * 4];
        assert!(ui.draw(&lcd, &[0.0; 41], &mut out));
        let (cx, cy) = lcd_centre();
        let px = &out[(cy * W + cx) * 4..][..4];
        assert!(px[0] > 200 && px[1] < 60 && px[2] < 60 && px[3] == 255, "{px:?}");
    }

    #[test]
    fn a_turned_knob_glows_and_fades() {
        let mut ui = Ui::new();
        ui.turn(3, 2);
        assert_eq!((ui.enc_clicks[3], ui.turning[3]), (2, 10));
        let mut out = vec![0u8; W * H * 4];
        ui.draw(&vec![0; 240 * 240], &[0.0; 41], &mut out);
        assert_eq!(ui.turning[3], 9);
    }

    #[test]
    fn an_unchanged_panel_is_not_redrawn() {
        let mut ui = Ui::new();
        let lcd = vec![0u16; 240 * 240];
        let mut out = vec![0u8; W * H * 4];
        assert!(ui.draw(&lcd, &[0.0; 41], &mut out), "first draw");
        assert!(!ui.draw(&lcd, &[0.0; 41], &mut out), "nothing changed");
        ui.set_down(20, true);
        assert!(ui.draw(&lcd, &[0.0; 41], &mut out), "a key went down");
        let mut lit = [0.0f32; 41];
        lit[9] = 0.08;
        assert!(ui.draw(&lcd, &lit, &mut out), "a LED lit");
        assert!(!ui.draw(&lcd, &lit, &mut out));
    }
}

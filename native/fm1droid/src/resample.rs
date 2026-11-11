//! 44.1 kHz (the firmware's I2S rate) to the device's rate: Catmull-Rom over four frames.

pub struct Resampler {
    step: f64,
    frac: f64,
    h: [[f32; 2]; 4],
}

impl Resampler {
    pub fn new(src_hz: u32, dst_hz: u32) -> Self {
        Resampler { step: src_hz as f64 / dst_hz as f64, frac: 0.0, h: [[0.0; 2]; 4] }
    }

    /// Fill `out` (interleaved stereo). `pull` gives the next source frame, `None` when the
    /// ring is empty (a silent frame is used). Returns how many frames were missing.
    pub fn process(&mut self, out: &mut [f32], mut pull: impl FnMut() -> Option<[f32; 2]>) -> u32 {
        let mut starved = 0;
        for f in out.chunks_exact_mut(2) {
            self.frac += self.step;
            while self.frac >= 1.0 {
                self.frac -= 1.0;
                let s = pull().unwrap_or_else(|| {
                    starved += 1;
                    [0.0, 0.0]
                });
                self.h = [self.h[1], self.h[2], self.h[3], s];
            }
            let t = self.frac as f32;
            for c in 0..2 {
                let (p0, p1, p2, p3) = (self.h[0][c], self.h[1][c], self.h[2][c], self.h[3][c]);
                f[c] = p1 + 0.5 * t * (p2 - p0 + t * (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3 + t * (3.0 * (p1 - p2) + p3 - p0)));
            }
        }
        starved
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_rate_passes_samples_through_delayed() {
        let mut r = Resampler::new(44_100, 44_100);
        let mut src = (1..=100).map(|i| [i as f32, -(i as f32)]);
        let mut out = vec![0.0f32; 20];
        assert_eq!(r.process(&mut out, || src.next()), 0);
        // interpolação cúbica com t = 0 devolve a amostra de dois quadros atrás
        assert_eq!(&out[4..10], &[1.0, -1.0, 2.0, -2.0, 3.0, -3.0]);
    }

    #[test]
    fn upsampling_consumes_source_at_the_rate_ratio() {
        let mut r = Resampler::new(44_100, 48_000);
        let mut pulled = 0u32;
        let mut out = vec![0.0f32; 48_000 * 2];
        r.process(&mut out, || { pulled += 1; Some([0.5, 0.5]) });
        assert!((44_099..=44_101).contains(&pulled), "pulled {pulled}");
        assert!(out[1000..].iter().all(|s| (s - 0.5).abs() < 1e-4), "DC must stay DC");
    }

    #[test]
    fn starvation_is_counted_and_silent() {
        let mut r = Resampler::new(44_100, 44_100);
        let mut out = vec![1.0f32; 16];
        assert_eq!(r.process(&mut out, || None), 8);
        assert!(out.iter().all(|s| *s == 0.0));
    }
}

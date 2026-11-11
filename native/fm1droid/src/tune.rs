//! The arithmetic of the audio output's tuning, kept apart from AAudio so it runs in host tests.

/// How late a frame handed over now will be heard, in microseconds. AAudio says frame `at_frame`
/// left the device at `at_ns`; `written` frames have been handed over so far; it is `now_ns`.
pub fn latency_us(written: i64, at_frame: i64, at_ns: i64, now_ns: i64, rate: i32) -> i64 {
    let ahead_ns = (written - at_frame) * 1_000_000_000 / rate.max(1) as i64;
    (at_ns + ahead_ns - now_ns) / 1000
}

/// The stream's buffer after a check: one burst more when the device reported new underruns,
/// never more than four bursts.
pub fn next_buffer(burst: i32, current: i32, new_xruns: i32) -> i32 {
    if new_xruns > 0 {
        (current + burst).min(burst * 4).max(current)
    } else {
        current
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn latency_is_what_is_still_queued_minus_the_time_gone_by() {
        // 4800 frames at 48 kHz are 100 ms ahead of frame 0, which played 50 ms ago
        assert_eq!(latency_us(4800, 0, 1_000_000_000, 1_050_000_000, 48_000), 50_000);
        // nothing queued and the timestamp is now: no latency
        assert_eq!(latency_us(100, 100, 5_000, 5_000, 44_100), 0);
        // a zero rate must not divide by zero
        let _ = latency_us(10, 0, 0, 0, 0);
    }

    #[test]
    fn the_buffer_grows_a_burst_per_bad_check_up_to_four() {
        assert_eq!(next_buffer(192, 192, 0), 192);
        assert_eq!(next_buffer(192, 192, 3), 384);
        assert_eq!(next_buffer(192, 576, 1), 768);
        assert_eq!(next_buffer(192, 768, 1), 768);
        assert_eq!(next_buffer(192, 1000, 1), 1000); // already above the cap: never shrinks
    }
}

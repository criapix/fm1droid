//! Flash saves: the 4096-byte sectors that differ from the package's flash, each as a
//! 4-byte little-endian offset followed by the sector.

pub const SECTOR: usize = 4096;
pub const RECORD: usize = SECTOR + 4;

pub fn diff(now: &[u8], base: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    for s in 0..now.len().min(base.len()) / SECTOR {
        let r = s * SECTOR..(s + 1) * SECTOR;
        if now[r.clone()] != base[r.clone()] {
            out.extend_from_slice(&((s * SECTOR) as u32).to_le_bytes());
            out.extend_from_slice(&now[r]);
        }
    }
    out
}

pub fn parse(bytes: &[u8], flash_size: usize) -> Result<Vec<(u32, Vec<u8>)>, String> {
    if bytes.len() % RECORD != 0 {
        return Err("save file has a wrong length".into());
    }
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::new();
    for rec in bytes.chunks_exact(RECORD) {
        let at = u32::from_le_bytes(rec[..4].try_into().unwrap());
        if at as usize % SECTOR != 0 || at as usize + SECTOR > flash_size || !seen.insert(at) {
            return Err("save file has a bad sector offset".into());
        }
        out.push((at, rec[4..].to_vec()));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn diff_lists_only_changed_sectors() {
        let base = vec![0xAAu8; SECTOR * 4];
        let mut now = base.clone();
        now[SECTOR * 2 + 5] = 0x12;
        let rec = diff(&now, &base);
        assert_eq!(rec.len(), RECORD);
        assert_eq!(u32::from_le_bytes(rec[..4].try_into().unwrap()), (SECTOR * 2) as u32);
        assert_eq!(rec[4 + 5], 0x12);
    }

    #[test]
    fn parse_round_trips_and_rejects_garbage() {
        let base = vec![0u8; SECTOR * 4];
        let mut now = base.clone();
        now[0] = 1;
        now[SECTOR * 3] = 2;
        let rec = diff(&now, &base);
        let parsed = parse(&rec, SECTOR * 4).unwrap();
        assert_eq!(parsed.iter().map(|s| s.0).collect::<Vec<_>>(), vec![0, (SECTOR * 3) as u32]);
        assert!(parse(&rec[..rec.len() - 1], SECTOR * 4).is_err(), "truncated");
        assert!(parse(&rec, SECTOR * 2).is_err(), "offset past the flash");
        let mut dup = rec[..RECORD].to_vec();
        dup.extend_from_slice(&rec[..RECORD]);
        assert!(parse(&dup, SECTOR * 4).is_err(), "duplicate sector");
        assert!(parse(&[], SECTOR * 4).unwrap().is_empty());
    }
}

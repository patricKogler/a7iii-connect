//! Culling marks by shot key, in the phone's flags.txt format: `key<TAB>PICK|REJECT` per line.

use crate::library::write_atomic;
use std::collections::HashMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Mark {
    Pick,
    Reject,
}

impl Mark {
    /// Reads a mark as the phone stores it. Versions 0.15 to 0.21 kept culling rounds: -1
    /// rejected, 1 and up a pick that many rounds deep.
    pub fn parse(v: &str) -> Option<Mark> {
        match v.trim() {
            "PICK" => Some(Mark::Pick),
            "REJECT" => Some(Mark::Reject),
            other => other.parse::<i32>().ok().and_then(|n| {
                if n < 0 {
                    Some(Mark::Reject)
                } else if n >= 1 {
                    Some(Mark::Pick)
                } else {
                    None
                }
            }),
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Mark::Pick => "PICK",
            Mark::Reject => "REJECT",
        }
    }
}

pub struct Marks {
    file: PathBuf,
    map: HashMap<String, Mark>,
}

impl Marks {
    pub fn load(file: &Path) -> Marks {
        let map = fs::read_to_string(file).map(|t| decode(&t)).unwrap_or_default();
        Marks { file: file.to_path_buf(), map }
    }

    pub fn get(&self, key: &str) -> Option<Mark> {
        self.map.get(key).copied()
    }

    pub fn set(&mut self, key: &str, mark: Option<Mark>) -> io::Result<()> {
        match mark {
            Some(m) => self.map.insert(key.to_string(), m),
            None => self.map.remove(key),
        };
        write_atomic(&self.file, encode(&self.map).as_bytes())
    }

    pub fn all(&self) -> &HashMap<String, Mark> {
        &self.map
    }
}

pub fn decode(text: &str) -> HashMap<String, Mark> {
    text.lines()
        .filter_map(|l| {
            let (k, v) = l.split_once('\t')?;
            Some((k.to_string(), Mark::parse(v)?))
        })
        .collect()
}

pub fn encode(map: &HashMap<String, Mark>) -> String {
    let mut keys: Vec<&String> = map.keys().collect();
    keys.sort();
    keys.into_iter().map(|k| format!("{k}\t{}\n", map[k].as_str())).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_phones_flags_file() {
        let m = decode("DSC1.ARW@1\tPICK\nDSC2.ARW@2\tREJECT\nDSC3.ARW@3\t2\nDSC4.ARW@4\t-1\nDSC5.ARW@5\t0\nbroken\n");
        assert_eq!(m.len(), 4);
        assert_eq!(m["DSC3.ARW@3"], Mark::Pick);
        assert_eq!(m["DSC4.ARW@4"], Mark::Reject);
        assert_eq!(decode(&encode(&m)), m);
    }
}

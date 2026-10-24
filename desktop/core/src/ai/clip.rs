//! Search in words: CLIP (ViT-B/32) puts pictures and descriptions in one space, so "a dog on
//! the beach" lands near photos of one. Each photo is looked at once and its vector kept beside
//! the library (`.shootback/search.txt`); a search only runs the text half.

use super::catalog::CLIP;
use super::tensor::{self, Input, Rgb};
use crate::cull::{pack_embedding, unpack_embedding};
use crate::library::STATE_DIR;
use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

const SIZE: usize = 224;
const MEAN: [f32; 3] = [0.481_454_66, 0.457_827_5, 0.408_210_73];
const STD: [f32; 3] = [0.268_629_5, 0.261_302_6, 0.275_777_1];
/// Tokens the text model reads, the start and end marks included.
const CONTEXT: usize = 77;

/// Where `px` (sRGB 0..1) sits, as a unit vector.
pub fn embed_image(px: &Rgb) -> Result<Vec<f32>, String> {
    let session = super::session(&CLIP, "visual.onnx")?;
    // Shorter side to 224, then the middle.
    let k = SIZE as f32 / px.width.min(px.height) as f32;
    let (w, h) = (((px.width as f32 * k).round() as usize).max(SIZE), ((px.height as f32 * k).round() as usize).max(SIZE));
    let big = px.resized(w, h);
    let (x0, y0) = ((w - SIZE) / 2, (h - SIZE) / 2);
    let data = (0..SIZE).flat_map(|y| (0..SIZE).flat_map(move |x| [x0 + x, y0 + y])).collect::<Vec<_>>();
    let crop = Rgb { width: SIZE, height: SIZE, data: data.chunks(2).flat_map(|p| big.at(p[0], p[1])).collect() };
    let input = crop.planar(MEAN, STD);
    let mut out = tensor::run(&mut session.lock().unwrap(), vec![Input::F32("pixel_values", vec![1, 3, SIZE as i64, SIZE as i64], input)])?;
    let (_, mut v) = out.pop().ok_or("the search model gave nothing back")?;
    super::faces::normalize(&mut v);
    Ok(v)
}

/// What a search for `words` looks for: put as a caption, which CLIP matches best.
pub fn embed_query(words: &str) -> Result<Vec<f32>, String> {
    embed_text(&format!("a photo of {}", words.trim()))
}

/// Where a description sits, as a unit vector.
pub fn embed_text(text: &str) -> Result<Vec<f32>, String> {
    let ids = tokenizer()?.encode(text);
    let session = super::session(&CLIP, "textual.onnx")?;
    let mut input = vec![0i32; CONTEXT];
    let mut mask = vec![0i32; CONTEXT];
    for (i, &t) in ids.iter().enumerate() {
        input[i] = t as i32;
        mask[i] = 1;
    }
    let shape = vec![1, CONTEXT as i64];
    let mut out = tensor::run(&mut session.lock().unwrap(), vec![Input::I32("input_ids", shape.clone(), input), Input::I32("attention_mask", shape, mask)])?;
    let (_, mut v) = out.pop().ok_or("the search model gave nothing back")?;
    super::faces::normalize(&mut v);
    Ok(v)
}

fn tokenizer() -> Result<&'static Tokenizer, String> {
    static T: OnceLock<Result<Tokenizer, String>> = OnceLock::new();
    T.get_or_init(|| {
        let dir = super::ensure(&CLIP)?;
        let text = fs::read_to_string(dir.join("vocab.txt")).map_err(|e| e.to_string())?;
        Ok(Tokenizer::new(&text))
    })
    .as_ref()
    .map_err(Clone::clone)
}

/// CLIP's byte-pair tokenizer (its simple_tokenizer.py), from the merges file.
pub struct Tokenizer {
    byte_char: [char; 256],
    encoder: HashMap<String, u32>,
    ranks: HashMap<(String, String), usize>,
    start: u32,
    end: u32,
}

impl Tokenizer {
    pub fn new(merges_file: &str) -> Tokenizer {
        // Bytes as printable characters: the printable ones stand for themselves.
        let mut bs: Vec<u32> = (b'!' as u32..=b'~' as u32).chain(0xA1..=0xAC).chain(0xAE..=0xFF).collect();
        let mut cs = bs.clone();
        let mut n = 0;
        for b in 0..256u32 {
            if !bs.contains(&b) {
                bs.push(b);
                cs.push(256 + n);
                n += 1;
            }
        }
        let mut byte_char = ['\0'; 256];
        for (b, c) in bs.iter().zip(&cs) {
            byte_char[*b as usize] = char::from_u32(*c).unwrap();
        }
        let merges: Vec<(String, String)> =
            merges_file.lines().skip(1).take(49152 - 256 - 2).filter_map(|l| l.split_once(' ').map(|(a, b)| (a.to_string(), b.to_string()))).collect();
        let mut vocab: Vec<String> = bs.iter().map(|&b| byte_char[b as usize].to_string()).collect();
        vocab.extend(bs.iter().map(|&b| format!("{}</w>", byte_char[b as usize])));
        vocab.extend(merges.iter().map(|(a, b)| format!("{a}{b}")));
        vocab.push("<|startoftext|>".into());
        vocab.push("<|endoftext|>".into());
        let encoder: HashMap<String, u32> = vocab.into_iter().enumerate().map(|(i, v)| (v, i as u32)).collect();
        let ranks = merges.into_iter().enumerate().map(|(i, m)| (m, i)).collect();
        Tokenizer { byte_char, start: encoder["<|startoftext|>"], end: encoder["<|endoftext|>"], encoder, ranks }
    }

    /// The token ids for `text`, between the start and end marks, cut to fit.
    pub fn encode(&self, text: &str) -> Vec<u32> {
        let clean = text.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase();
        let mut ids = vec![self.start];
        for word in words(&clean) {
            let mapped: String = word.bytes().map(|b| self.byte_char[b as usize]).collect();
            ids.extend(self.bpe(&mapped).iter().filter_map(|t| self.encoder.get(t)));
        }
        ids.truncate(CONTEXT - 1);
        ids.push(self.end);
        ids
    }

    fn bpe(&self, token: &str) -> Vec<String> {
        let chars: Vec<char> = token.chars().collect();
        let mut word: Vec<String> = chars.iter().map(|c| c.to_string()).collect();
        if let Some(last) = word.last_mut() {
            last.push_str("</w>");
        }
        loop {
            let best = word.windows(2).filter_map(|p| self.ranks.get(&(p[0].clone(), p[1].clone())).map(|&r| (r, p[0].clone(), p[1].clone()))).min();
            let Some((_, a, b)) = best else { break };
            let mut merged = Vec::with_capacity(word.len());
            let mut i = 0;
            while i < word.len() {
                if i + 1 < word.len() && word[i] == a && word[i + 1] == b {
                    merged.push(format!("{a}{b}"));
                    i += 2;
                } else {
                    merged.push(word[i].clone());
                    i += 1;
                }
            }
            word = merged;
            if word.len() == 1 {
                break;
            }
        }
        word
    }
}

/// The text cut into pieces as CLIP's pattern does: contractions, runs of letters, single digits,
/// and runs of anything else that isn't a space.
fn words(text: &str) -> Vec<&str> {
    const ENDINGS: [&str; 7] = ["'s", "'t", "'re", "'ve", "'m", "'ll", "'d"];
    let mut out = Vec::new();
    let mut rest = text;
    while let Some(c) = rest.chars().next() {
        if c.is_whitespace() {
            rest = &rest[c.len_utf8()..];
            continue;
        }
        let len = if let Some(e) = ENDINGS.iter().find(|e| rest.starts_with(**e)) {
            e.len()
        } else if c.is_alphabetic() {
            rest.find(|c: char| !c.is_alphabetic()).unwrap_or(rest.len())
        } else if c.is_numeric() {
            c.len_utf8()
        } else {
            rest.find(|c: char| c.is_whitespace() || c.is_alphabetic() || c.is_numeric()).unwrap_or(rest.len())
        };
        out.push(&rest[..len]);
        rest = &rest[len..];
    }
    out
}

/// Every photo's vector, kept beside the library: `.shootback/search.txt`, a line per shot (key,
/// a tab, the vector packed a byte per value). Lines are only added; a later one wins.
pub struct SearchStore {
    file: PathBuf,
}

impl SearchStore {
    pub fn new(root: &Path) -> SearchStore {
        SearchStore { file: root.join(STATE_DIR).join("search.txt") }
    }

    pub fn load(&self) -> HashMap<String, Vec<f32>> {
        let text = fs::read_to_string(&self.file).unwrap_or_default();
        text.lines().filter_map(|l| l.split_once('\t')).filter_map(|(k, v)| Some((k.to_string(), unpack_embedding(v)?))).collect()
    }

    pub fn add(&self, key: &str, v: &[f32]) -> std::io::Result<()> {
        fs::create_dir_all(self.file.parent().unwrap())?;
        let mut f = fs::OpenOptions::new().create(true).append(true).open(&self.file)?;
        writeln!(f, "{key}\t{}", pack_embedding(v))
    }
}

/// Which shots a search finds, best first: those that score near the best and clearly above
/// what any picture scores against unrelated words.
pub fn ranked(query: &[f32], index: &HashMap<String, Vec<f32>>) -> Vec<(String, f32)> {
    /// CLIP's cosines run low: matches about 0.25 to 0.35, unrelated pictures under about 0.2.
    const FLOOR: f32 = 0.21;
    /// Shots this far below the best match aren't it.
    const BAND: f32 = 0.06;
    let mut scored: Vec<(String, f32)> =
        index.iter().filter(|(_, v)| v.len() == query.len()).map(|(k, v)| (k.clone(), crate::people::cosine(query, v))).collect();
    scored.sort_by(|a, b| b.1.total_cmp(&a.1));
    let best = scored.first().map_or(0.0, |s| s.1);
    scored.retain(|s| s.1 >= FLOOR && s.1 >= best - BAND);
    scored
}

#[cfg(test)]
mod tests {
    #[test]
    fn words_split_like_clips_pattern() {
        assert_eq!(super::words("a dog's day, 2026!"), vec!["a", "dog", "'s", "day", ",", "2", "0", "2", "6", "!"]);
    }
}

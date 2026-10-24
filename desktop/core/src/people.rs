//! People: faces grouped by who they are, as the phone does it (People.kt), and the remembered
//! people (`.shootback/people.txt`, the phone's file format) put together with the phone's, so a
//! name given on either side shows on both.

use crate::cull::{pack_embedding, unpack_embedding, Insight};
use std::collections::HashMap;

/// A face joins the person it looks most like at this cosine or more (same-person pairs measure
/// about 0.7 to 1 with SFace, strangers under about 0.3); otherwise it starts someone new.
pub const JOIN: f32 = 0.42;
/// Two unnamed groups this alike are one person seen two ways; merged. Also how alike a person
/// on the phone and one here have to be to be the same person.
const MERGE: f32 = 0.5;
/// Unnamed people in fewer shots than this aren't shown.
const MIN_SHOTS: usize = 2;

/// One face in one shot.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct FaceRef {
    pub shot: String,
    pub index: usize,
}

/// A person: faces that look like the same someone. `id` stays from one grouping to the next.
#[derive(Clone, Debug)]
pub struct Person {
    pub id: String,
    pub name: Option<String>,
    pub centroid: Vec<f32>,
    pub faces: Vec<FaceRef>,
}

impl Person {
    /// What filters go by: the name when there is one, so two people given the same name are one.
    pub fn key(&self) -> String {
        self.name.clone().unwrap_or_else(|| self.id.clone())
    }

    pub fn shots(&self) -> usize {
        let mut s: Vec<&str> = self.faces.iter().map(|f| f.shot.as_str()).collect();
        s.sort_unstable();
        s.dedup();
        s.len()
    }
}

/// A remembered person: who, and what they look like.
#[derive(Clone, Debug, PartialEq)]
pub struct Anchor {
    pub id: String,
    pub name: Option<String>,
    pub centroid: Vec<f32>,
}

/// Everyone remembered, and the number the next new person's id gets.
#[derive(Clone, Debug, PartialEq)]
pub struct Remembered {
    pub people: Vec<Anchor>,
    pub next: usize,
}

impl Default for Remembered {
    fn default() -> Self {
        Remembered { people: Vec::new(), next: 1 }
    }
}

fn first_free<'a>(ids: impl Iterator<Item = &'a str>) -> usize {
    ids.filter_map(|id| id.strip_prefix('p').unwrap_or(id).parse::<usize>().ok()).max().unwrap_or(0) + 1
}

pub fn cosine(a: &[f32], b: &[f32]) -> f32 {
    a.iter().zip(b).map(|(x, y)| x * y).sum()
}

fn normalized(v: &[f32]) -> Vec<f32> {
    let mut v = v.to_vec();
    crate::ai::faces::normalize(&mut v);
    v
}

struct Group {
    id: String,
    name: Option<String>,
    sum: Vec<f32>,
    centroid: Vec<f32>,
    faces: Vec<FaceRef>,
}

impl Group {
    fn new(id: String, name: Option<String>, sum: Vec<f32>) -> Group {
        Group { centroid: normalized(&sum), id, name, sum, faces: Vec::new() }
    }

    fn add(&mut self, r: FaceRef, e: &[f32]) {
        self.sum.iter_mut().zip(e).for_each(|(s, v)| *s += v);
        self.faces.push(r);
        self.centroid = normalized(&self.sum);
    }
}

/// People in `insights`, starting from `known`; named people in no photo now come back with no
/// faces. New people are numbered from `known.next` on.
pub fn group(insights: &HashMap<String, Insight>, known: &Remembered) -> Vec<Person> {
    let mut groups: Vec<Group> = known.people.iter().map(|a| Group::new(a.id.clone(), a.name.clone(), a.centroid.clone())).collect();
    let mut next = known.next.max(first_free(known.people.iter().map(|a| a.id.as_str())));
    let mut embeddings: HashMap<FaceRef, &[f32]> = HashMap::new();
    let mut keys: Vec<&String> = insights.keys().collect();
    keys.sort();
    for key in keys {
        for (i, f) in insights[key].faces.iter().enumerate() {
            let Some(e) = f.embedding.as_deref() else { continue };
            if groups.first().is_some_and(|g| g.sum.len() != e.len()) {
                continue;
            }
            let r = FaceRef { shot: key.clone(), index: i };
            embeddings.insert(r.clone(), e);
            let best = groups.iter_mut().max_by(|a, b| cosine(&a.centroid, e).total_cmp(&cosine(&b.centroid, e)));
            match best {
                Some(g) if cosine(&g.centroid, e) >= JOIN => g.add(r, e),
                _ => {
                    let mut g = Group::new(format!("p{next}"), None, vec![0.0; e.len()]);
                    next += 1;
                    g.add(r, e);
                    groups.push(g);
                }
            }
        }
    }
    // Unnamed groups that turn out alike join the bigger one.
    loop {
        let mut pair = None;
        'search: for a in 0..groups.len() {
            for b in 0..groups.len() {
                let (ga, gb) = (&groups[a], &groups[b]);
                if a != b && gb.name.is_none() && !gb.faces.is_empty() && ga.faces.len() >= gb.faces.len() && cosine(&ga.centroid, &gb.centroid) >= MERGE {
                    pair = Some((a, b));
                    break 'search;
                }
            }
        }
        let Some((a, b)) = pair else { break };
        let gone = groups.remove(b);
        let a = if b < a { a - 1 } else { a };
        for r in gone.faces {
            let e = embeddings[&r];
            groups[a].add(r, e);
        }
    }
    groups
        .into_iter()
        .filter(|g| !g.faces.is_empty() || g.name.is_some())
        .map(|g| Person { id: g.id, name: g.name, centroid: g.centroid, faces: g.faces })
        .collect()
}

/// People worth showing: named ones, and anyone in two shots or more; named first, then the most
/// photographed.
pub fn shown(people: &[Person]) -> Vec<&Person> {
    let mut out: Vec<&Person> = people.iter().filter(|p| !p.faces.is_empty() && (p.name.is_some() || p.shots() >= MIN_SHOTS)).collect();
    out.sort_by_key(|p| (p.name.is_none(), std::cmp::Reverse(p.shots())));
    out
}

/// What to remember after a grouping: people in a photo twice or named.
pub fn remember(people: &[Person], known: &Remembered) -> Remembered {
    let next = known.next.max(first_free(known.people.iter().map(|a| a.id.as_str()))).max(first_free(people.iter().map(|p| p.id.as_str())));
    let people = people
        .iter()
        .filter(|p| p.name.is_some() || p.faces.len() >= 2)
        .map(|p| Anchor { id: p.id.clone(), name: p.name.clone(), centroid: p.centroid.clone() })
        .collect();
    Remembered { people, next }
}

/// `known` with the people in `seen` (as they were on screen) called `name`; blank forgets it.
pub fn rename(known: &Remembered, seen: &[&Person], name: &str) -> Remembered {
    let n = Some(name.trim()).filter(|n| !n.is_empty()).map(str::to_string);
    let ids: Vec<&str> = seen.iter().map(|p| p.id.as_str()).collect();
    let mut people: Vec<Anchor> =
        known.people.iter().map(|a| if ids.contains(&a.id.as_str()) { Anchor { name: n.clone(), ..a.clone() } } else { a.clone() }).collect();
    if n.is_some() {
        for p in seen {
            if !known.people.iter().any(|a| a.id == p.id) {
                people.push(Anchor { id: p.id.clone(), name: n.clone(), centroid: p.centroid.clone() });
            }
        }
    }
    Remembered { people, next: known.next }
}

/// The phone's remembered people (`theirs`) put together with these. The same person on both
/// sides (by look) keeps the phone's id, and a name from either side; when both named them
/// differently the phone's name stays. People only known here come along, renumbered if their
/// id is taken on the phone.
pub fn merge(mine: &Remembered, theirs: &Remembered) -> Remembered {
    let mut out = theirs.people.clone();
    let mut next = mine.next.max(theirs.next).max(first_free(out.iter().map(|a| a.id.as_str()))).max(first_free(mine.people.iter().map(|a| a.id.as_str())));
    for a in &mine.people {
        let same = out
            .iter_mut()
            .filter(|o| o.centroid.len() == a.centroid.len())
            .map(|o| (cosine(&o.centroid, &a.centroid), o))
            .filter(|(c, _)| *c >= MERGE)
            .max_by(|x, y| x.0.total_cmp(&y.0));
        if let Some((_, o)) = same {
            if o.name.is_none() {
                o.name = a.name.clone();
            }
            continue;
        }
        let id = if out.iter().any(|o| o.id == a.id) {
            next += 1;
            format!("p{}", next - 1)
        } else {
            a.id.clone()
        };
        out.push(Anchor { id, ..a.clone() });
    }
    next = next.max(first_free(out.iter().map(|a| a.id.as_str())));
    Remembered { people: out, next }
}

/// One line per person (id, name, look) after a `#next` line, as on the phone.
pub fn encode(r: &Remembered) -> String {
    let mut out = format!("#next\t{}\n", r.next);
    for a in &r.people {
        let name = a.name.as_deref().unwrap_or("").replace(['\t', '\n'], " ");
        out.push_str(&format!("{}\t{name}\t{}\n", a.id, pack_embedding(&a.centroid)));
    }
    out
}

pub fn decode(text: &str) -> Remembered {
    let mut next = 1;
    let mut people = Vec::new();
    for line in text.lines() {
        let parts: Vec<&str> = line.split('\t').collect();
        if parts.len() == 2 && parts[0] == "#next" {
            next = parts[1].parse().unwrap_or(1);
        }
        if parts.len() != 3 {
            continue;
        }
        let Some(e) = unpack_embedding(parts[2]) else { continue };
        people.push(Anchor { id: parts[0].to_string(), name: Some(parts[1].to_string()).filter(|n| !n.is_empty()), centroid: e });
    }
    let next = next.max(first_free(people.iter().map(|a| a.id.as_str())));
    Remembered { people, next }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cull::FaceInfo;

    fn unit(v: &[f32]) -> Vec<f32> {
        normalized(v)
    }

    fn shot(faces: &[&[f32]]) -> Insight {
        Insight {
            faces: faces.iter().map(|e| FaceInfo { b: [0.0, 0.0, 0.2, 0.2], eyes_open: None, smile: None, sharp: 1.0, embedding: Some(unit(e)) }).collect(),
            sharp: 1.0,
            labels: Vec::new(),
            text: String::new(),
            version: Insight::VERSION,
        }
    }

    #[test]
    fn faces_group_into_people_and_names_stick() {
        let ann = [1.0, 0.1, 0.0];
        let bob = [0.0, 1.0, 0.1];
        let mut insights = HashMap::new();
        insights.insert("a".to_string(), shot(&[&ann, &bob]));
        insights.insert("b".to_string(), shot(&[&[0.95, 0.2, 0.05]]));
        insights.insert("c".to_string(), shot(&[&[0.1, 0.9, 0.0]]));
        let people = group(&insights, &Remembered::default());
        assert_eq!(people.len(), 2);
        assert!(people.iter().all(|p| p.shots() == 2));
        let first = people.iter().find(|p| p.faces.iter().any(|f| f.shot == "b")).unwrap();
        let known = rename(&remember(&people, &Remembered::default()), &[first], "Ann");
        let again = group(&insights, &known);
        let ann_now = again.iter().find(|p| p.name.as_deref() == Some("Ann")).unwrap();
        assert_eq!(ann_now.id, first.id);
        assert_eq!(shown(&again)[0].name.as_deref(), Some("Ann"));
    }

    #[test]
    fn the_phones_people_and_ours_come_together() {
        let phone = Remembered { people: vec![Anchor { id: "p1".into(), name: Some("Ann".into()), centroid: unit(&[1.0, 0.0, 0.0]) }], next: 2 };
        let mine = Remembered {
            people: vec![
                Anchor { id: "p4".into(), name: None, centroid: unit(&[0.9, 0.1, 0.0]) },
                Anchor { id: "p1".into(), name: Some("Bob".into()), centroid: unit(&[0.0, 1.0, 0.0]) },
            ],
            next: 5,
        };
        let m = merge(&mine, &phone);
        assert_eq!(m.people.len(), 2);
        assert_eq!(m.people[0].name.as_deref(), Some("Ann"));
        let bob = &m.people[1];
        assert_eq!(bob.name.as_deref(), Some("Bob"));
        assert_ne!(bob.id, "p1");
        assert!(m.next > bob.id[1..].parse::<usize>().unwrap());
        assert_eq!(decode(&encode(&m)).people.len(), 2);
        assert_eq!(decode(&encode(&m)).people[1].name, bob.name);
        // Putting it together again changes nothing.
        assert_eq!(merge(&m, &m).people.len(), 2);
    }
}

//! People: faces grouped by who they are, as the phone does it (People.kt), and the remembered
//! people put together with the phone's, so a name given on either side shows on both.
//!
//! The computer tells faces apart with ArcFace and the phone with SFace, whose looks can't be
//! compared. So the computer keeps its people in `.shootback/people-desktop.txt`, and
//! `.shootback/people.txt` (the phone's file, which sync puts together with the phone's) holds
//! them as the phone sees them: each face here has an SFace look too, and a person's SFace look is
//! what pairs them with a person on the phone.

use crate::cull::{pack_embedding, unpack_embedding, Insight};
use std::collections::HashMap;

/// A face joins the person it looks most like at this cosine or more, otherwise it starts someone
/// new; and two unnamed groups this alike are one person seen two ways, merged. Also how alike a
/// person on the phone and one here have to be to be the same person. By the length of the looks:
/// SFace's (128; same-person pairs about 0.6 to 1, strangers under about 0.3) or ArcFace's (512;
/// same-person pairs about 0.6 to 1, strangers under about 0.15).
fn thresholds(len: usize) -> (f32, f32) {
    if len == PHONE_LOOK {
        (0.42, 0.5)
    } else {
        (0.35, 0.45)
    }
}
/// Faces narrower than this (of the picture's width) only join someone already found.
const CLEAR_FACE: f32 = 0.045;
/// The length of the phone's (SFace) looks.
const PHONE_LOOK: usize = 128;
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
    // Faces big in the picture first: they're told apart best, so they make the people, and
    // small faces (whose looks are rougher) only join someone, never start someone new.
    let all: Vec<(FaceRef, &[f32], bool)> = keys
        .iter()
        .flat_map(|key| {
            insights[*key]
                .faces
                .iter()
                .enumerate()
                .filter_map(|(i, f)| Some((FaceRef { shot: (*key).clone(), index: i }, f.embedding.as_deref()?, f.width() >= CLEAR_FACE)))
        })
        .collect();
    for (r, e, clear) in all.iter().filter(|f| f.2).chain(all.iter().filter(|f| !f.2)) {
        if groups.first().is_some_and(|g| g.sum.len() != e.len()) {
            continue;
        }
        let best = groups.iter_mut().max_by(|a, b| cosine(&a.centroid, e).total_cmp(&cosine(&b.centroid, e)));
        match best {
            Some(g) if cosine(&g.centroid, e) >= thresholds(e.len()).0 => g.add(r.clone(), e),
            _ if *clear => {
                let mut g = Group::new(format!("p{next}"), None, vec![0.0; e.len()]);
                next += 1;
                g.add(r.clone(), e);
                groups.push(g);
            }
            _ => continue,
        }
        embeddings.insert(r.clone(), e);
    }
    // Unnamed groups that turn out alike join the bigger one.
    loop {
        let mut pair = None;
        'search: for a in 0..groups.len() {
            for b in 0..groups.len() {
                let (ga, gb) = (&groups[a], &groups[b]);
                if a != b
                    && gb.name.is_none()
                    && !gb.faces.is_empty()
                    && ga.faces.len() >= gb.faces.len()
                    && cosine(&ga.centroid, &gb.centroid) >= thresholds(ga.centroid.len()).1
                {
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
/// photographed. People given the same name are one (someone can look unlike themselves: a
/// child grown up, a face far off), under the id of the one in the most shots.
pub fn shown(people: &[Person]) -> Vec<Person> {
    let mut by_key: Vec<Person> = Vec::new();
    let mut sorted: Vec<&Person> = people.iter().filter(|p| !p.faces.is_empty()).collect();
    sorted.sort_by_key(|p| std::cmp::Reverse(p.shots()));
    for p in sorted {
        match by_key.iter_mut().find(|q| q.name.is_some() && q.name == p.name) {
            Some(q) => q.faces.extend(p.faces.iter().cloned()),
            None => by_key.push(p.clone()),
        }
    }
    let mut out: Vec<Person> = by_key.into_iter().filter(|p| p.name.is_some() || p.shots() >= MIN_SHOTS).collect();
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
            .filter(|(c, _)| *c >= thresholds(a.centroid.len()).1)
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

/// Each person's look as the phone sees them (their faces' SFace looks put together), by id.
pub fn phone_looks(people: &[Person], insights: &HashMap<String, Insight>) -> HashMap<String, Vec<f32>> {
    let mut out = HashMap::new();
    for p in people {
        let mut sum = vec![0.0; PHONE_LOOK];
        let mut any = false;
        for f in &p.faces {
            if let Some(e) = insights.get(&f.shot).and_then(|i| i.faces.get(f.index)).and_then(|f| f.phone.as_deref()).filter(|e| e.len() == PHONE_LOOK) {
                sum.iter_mut().zip(e).for_each(|(s, v)| *s += v);
                any = true;
            }
        }
        if any {
            out.insert(p.id.clone(), normalized(&sum));
        }
    }
    out
}

/// The phone's person (in `phone`) who is `look`, if one is.
fn on_phone<'a>(phone: &'a mut [Anchor], look: &[f32]) -> Option<&'a mut Anchor> {
    phone
        .iter_mut()
        .filter(|o| o.centroid.len() == look.len())
        .map(|o| (cosine(&o.centroid, look), o))
        .filter(|(c, _)| *c >= thresholds(PHONE_LOOK).1)
        .max_by(|x, y| x.0.total_cmp(&y.0))
        .map(|(_, o)| o)
}

/// `phone` (the people as the phone sees them) with the people remembered here (`mine`, their
/// phone looks in `looks`) in it: one the phone has gets the name given here, if there is one or
/// they are among `renamed` (a name taken away); the rest come along with new ids.
pub fn for_phone(mine: &Remembered, looks: &HashMap<String, Vec<f32>>, phone: &Remembered, renamed: &[String]) -> Remembered {
    let mut out = phone.people.clone();
    let mut next = phone.next.max(first_free(out.iter().map(|a| a.id.as_str())));
    for a in &mine.people {
        let Some(look) = looks.get(&a.id) else { continue };
        if let Some(o) = on_phone(&mut out, look) {
            if a.name.is_some() || renamed.contains(&a.id) {
                o.name = a.name.clone();
            }
            continue;
        }
        out.push(Anchor { id: format!("p{next}"), name: a.name.clone(), centroid: look.clone() });
        next += 1;
    }
    Remembered { people: out, next }
}

/// `known` (the people remembered here) with the names the phone gave the people in `people`
/// (their phone looks in `looks`): all of them when `phone_first` (the phone just changed them),
/// otherwise only for people with no name here.
pub fn names_from_phone(people: &[Person], looks: &HashMap<String, Vec<f32>>, known: &Remembered, phone: &Remembered, phone_first: bool) -> Remembered {
    let mut out = known.clone();
    let mut theirs: Vec<Anchor> = phone.people.iter().filter(|a| a.name.is_some()).cloned().collect();
    for p in people {
        let Some(look) = looks.get(&p.id) else { continue };
        let Some(o) = on_phone(&mut theirs, look) else { continue };
        if o.name == p.name || (p.name.is_some() && !phone_first) {
            continue;
        }
        match out.people.iter_mut().find(|a| a.id == p.id) {
            Some(a) => a.name = o.name.clone(),
            None => out.people.push(Anchor { id: p.id.clone(), name: o.name.clone(), centroid: p.centroid.clone() }),
        }
    }
    out
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
            faces: faces
                .iter()
                .map(|e| FaceInfo { b: [0.0, 0.0, 0.2, 0.2], eyes_open: None, smile: None, sharp: 1.0, embedding: Some(unit(e)), phone: None })
                .collect(),
            sharp: 1.0,
            labels: Vec::new(),
            text: String::new(),
            version: Insight::VERSION,
            model: Insight::FACE_MODEL,
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

    #[test]
    fn names_pass_to_the_phone_by_its_look() {
        let one = |i: usize| (0..PHONE_LOOK).map(|k| if k == i { 1.0 } else { 0.05 }).collect::<Vec<f32>>();
        // Three people here (each seen twice, so remembered), with the phone's look of them.
        let mut insights = HashMap::new();
        let mut people = Vec::new();
        for (n, name) in [Some("Ann"), None, None].into_iter().enumerate() {
            let mut i = shot(&[&[n as f32, 1.0, 0.0]]);
            i.faces[0].phone = Some(unit(&one(n)));
            insights.insert(format!("s{n}"), i);
            people.push(Person {
                id: format!("d{n}"),
                name: name.map(String::from),
                centroid: unit(&[n as f32, 1.0, 0.0]),
                faces: vec![FaceRef { shot: format!("s{n}"), index: 0 }; 2],
            });
        }
        let looks = phone_looks(&people, &insights);
        assert_eq!(looks.len(), 3);
        let phone = Remembered {
            people: vec![
                Anchor { id: "p1".into(), name: None, centroid: unit(&one(0)) },
                Anchor { id: "p2".into(), name: Some("Bob".into()), centroid: unit(&one(1)) },
            ],
            next: 3,
        };
        let mine = remember(&people, &Remembered::default());
        let out = for_phone(&mine, &looks, &phone, &[]);
        assert_eq!(out.people.len(), 3);
        assert_eq!(out.people[0].name.as_deref(), Some("Ann"));
        assert_eq!(out.people[1].name.as_deref(), Some("Bob"));
        assert_eq!((out.people[2].id.as_str(), out.people[2].centroid.len()), ("p3", PHONE_LOOK));
        // Bob's name comes back here; Ann keeps hers.
        let named = names_from_phone(&people, &looks, &mine, &out, false);
        let name = |id: &str| named.people.iter().find(|a| a.id == id).and_then(|a| a.name.clone());
        assert_eq!((name("d0").as_deref(), name("d1").as_deref(), name("d2")), (Some("Ann"), Some("Bob"), None));
        // Renamed on the phone: taken when the phone just changed them, not otherwise.
        let mut renamed = out.clone();
        renamed.people[0].name = Some("Anna".into());
        let first = |n: &Remembered| n.people.iter().find(|a| a.id == "d0").and_then(|a| a.name.clone());
        assert_eq!(first(&names_from_phone(&people, &looks, &mine, &renamed, true)).as_deref(), Some("Anna"));
        assert_eq!(first(&names_from_phone(&people, &looks, &mine, &renamed, false)).as_deref(), Some("Ann"));
    }

    #[test]
    fn one_name_is_one_person_and_small_faces_only_join() {
        let ann = [1.0, 0.0, 0.0];
        let mut insights = HashMap::new();
        insights.insert("a".to_string(), shot(&[&ann]));
        insights.insert("b".to_string(), shot(&[&ann]));
        // Far off and looking unlike anyone: too small to be someone new.
        let mut far = shot(&[&[0.0, 0.0, 1.0]]);
        far.faces[0].b = [0.0, 0.0, 0.02, 0.03];
        insights.insert("c".to_string(), far);
        let people = group(&insights, &Remembered::default());
        assert_eq!(people.len(), 1);
        // Two groups called Ann (one far off, the other near) show as one Ann in both shots.
        let split = vec![
            Person { id: "p1".into(), name: Some("Ann".into()), centroid: unit(&ann), faces: vec![FaceRef { shot: "a".into(), index: 0 }] },
            Person {
                id: "p2".into(),
                name: Some("Ann".into()),
                centroid: unit(&[0.0, 1.0, 0.0]),
                faces: vec![FaceRef { shot: "b".into(), index: 0 }, FaceRef { shot: "c".into(), index: 0 }],
            },
        ];
        let s = shown(&split);
        assert_eq!(s.len(), 1);
        assert_eq!((s[0].id.as_str(), s[0].shots()), ("p2", 3));
    }
}

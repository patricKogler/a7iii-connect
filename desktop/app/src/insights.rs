//! What's in each shot (faces and focus), looked at on a thread and kept beside the library, as
//! the phone does: culling asks for the shots coming up first, and once the face models are
//! there the rest of the library is looked at too, newest first. The faces found are grouped into
//! people, who can be named; the names travel to and from the phone.

use crate::images;
use shootback_core::ai::{self, catalog::FACES, tensor::Rgb};
use shootback_core::cull::{self, CullStore, FaceInfo, Insight};
use shootback_core::library::{write_atomic, Shot, STATE_DIR};
use shootback_core::people::{self, Person, Remembered};
use std::collections::{HashMap, HashSet, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

/// Long edge of the picture faces and focus are judged on, as on the phone.
const EDGE: u32 = 1600;
/// Faces narrower than this many pixels (at `EDGE`) are too small to recognise.
const MIN_FACE_PX: f32 = 40.0;
/// Faces turned further than this (`Face::turned`, about 35 degrees) are profiles: not recognised.
const TURNED: f32 = 0.55;
/// People are grouped again at most this often while shots come in.
const REGROUP: Duration = Duration::from_secs(2);

struct Queue {
    todo: Mutex<VecDeque<Shot>>,
    ready: Condvar,
    stop: AtomicBool,
    /// Why faces aren't looked for (the model didn't download, say); focus still is.
    faces_error: Mutex<Option<String>>,
}

pub struct Insights {
    root: PathBuf,
    pub known: HashMap<String, Insight>,
    remembered: Remembered,
    pub people: Vec<Person>,
    queue: Arc<Queue>,
    rx: Receiver<(String, Insight)>,
    /// The whole library is being looked at (the face models are there).
    all: bool,
    dirty: bool,
    grouped: Instant,
    /// People were grouped again since `follow` last said so.
    regrouped: bool,
    ctx: eframe::egui::Context,
}

impl Insights {
    pub fn new(ctx: &eframe::egui::Context, root: &Path) -> Insights {
        let queue = Arc::new(Queue { todo: Mutex::default(), ready: Condvar::new(), stop: AtomicBool::new(false), faces_error: Mutex::default() });
        let (tx, rx) = channel();
        let store = CullStore::new(root);
        let mut known = store.insights();
        // Faces seen by the models before can't be put with these; they're looked at again.
        for i in known.values_mut().filter(|i| !fresh(i)) {
            i.faces.iter_mut().for_each(|f| f.embedding = None);
        }
        let done: HashSet<String> = known.iter().filter(|(_, i)| fresh(i)).map(|(k, _)| k.clone()).collect();
        let (q, c) = (queue.clone(), ctx.clone());
        std::thread::Builder::new().name("insights".into()).spawn(move || work(&store, done, &q, &tx, &c)).expect("insights thread");
        let mut me = Insights {
            root: root.to_path_buf(),
            known,
            remembered: Remembered::default(),
            people: Vec::new(),
            queue,
            rx,
            all: false,
            dirty: false,
            grouped: Instant::now(),
            regrouped: false,
            ctx: ctx.clone(),
        };
        me.reload_people();
        me
    }

    pub fn stop(&self) {
        self.queue.stop.store(true, Ordering::Relaxed);
        self.queue.ready.notify_all();
    }

    pub fn set_root(&mut self, root: &Path) {
        self.stop();
        *self = Insights::new(&self.ctx.clone(), root);
    }

    pub fn faces_error(&self) -> Option<String> {
        self.queue.faces_error.lock().unwrap().clone()
    }

    /// The people remembered here, and (`phone`) as the phone sees them.
    fn people_file(&self, phone: bool) -> PathBuf {
        self.root.join(STATE_DIR).join(if phone { "people.txt" } else { "people-desktop.txt" })
    }

    fn read_people(&self, phone: bool) -> Remembered {
        people::decode(&std::fs::read_to_string(self.people_file(phone)).unwrap_or_default())
    }

    /// Reads the remembered people again (a phone changed them) and groups.
    pub fn reload_people(&mut self) {
        self.remembered = self.read_people(false);
        self.regroup(true, &[]);
    }

    /// Looks at `shots` before anything else, in that order.
    pub fn first(&self, shots: &[Shot]) {
        let mut q = self.queue.todo.lock().unwrap();
        for s in shots.iter().rev().filter(|s| !self.looked_at(s)) {
            q.push_front(s.clone());
        }
        self.queue.ready.notify_one();
    }

    /// Takes in what was found; once the face models are there, looks at every shot in `shots`
    /// (the library, newest first) too, again when `rescanned`. True when people were grouped
    /// again.
    pub fn follow(&mut self, shots: &[Shot], rescanned: bool) -> bool {
        while let Ok((k, i)) = self.rx.try_recv() {
            self.dirty |= !i.faces.is_empty();
            self.known.insert(k, i);
        }
        if (!self.all || rescanned) && ai::installed(&FACES) && self.faces_error().is_none() {
            self.all = true;
            let mut q = self.queue.todo.lock().unwrap();
            q.extend(shots.iter().filter(|s| !self.looked_at(s)).cloned());
            self.queue.ready.notify_one();
        }
        if self.dirty && self.grouped.elapsed() >= REGROUP {
            self.regroup(false, &[]);
        }
        std::mem::take(&mut self.regrouped)
    }

    /// Fetches the face models (for People, before culling has needed them).
    pub fn fetch_models(&self) {
        std::thread::spawn(|| ai::ensure(&FACES));
    }

    /// How many of `keys` have been looked at.
    pub fn count(&self, keys: impl Iterator<Item = String>) -> (usize, usize) {
        keys.fold((0, 0), |(k, n), key| (k + self.known.get(&key).is_some_and(fresh) as usize, n + 1))
    }

    fn looked_at(&self, s: &Shot) -> bool {
        self.known.get(&s.key()).is_some_and(fresh)
    }

    /// Groups the faces into people again, takes names from the phone (over names given here
    /// when `phone_first`) and gives it these people, the ones with `renamed` ids just renamed.
    fn regroup(&mut self, phone_first: bool, renamed: &[String]) {
        self.dirty = false;
        self.regrouped = true;
        self.grouped = Instant::now();
        self.people = people::group(&self.known, &self.remembered);
        let looks = people::phone_looks(&self.people, &self.known);
        let phone = self.read_people(true);
        let now = people::names_from_phone(&self.people, &looks, &people::remember(&self.people, &self.remembered), &phone, phone_first);
        for p in &mut self.people {
            if let Some(a) = now.people.iter().find(|a| a.id == p.id) {
                p.name.clone_from(&a.name);
            }
        }
        if now != self.remembered {
            self.remembered = now;
            self.save(false, &self.remembered);
        }
        let theirs = people::for_phone(&self.remembered, &looks, &phone, renamed);
        if theirs != phone {
            self.save(true, &theirs);
        }
    }

    fn save(&self, phone: bool, r: &Remembered) {
        let f = self.people_file(phone);
        let _ = std::fs::create_dir_all(f.parent().unwrap());
        let _ = write_atomic(&f, people::encode(r).as_bytes());
    }

    /// Calls the people with `ids` `name` (blank forgets the name).
    pub fn rename(&mut self, ids: &[String], name: &str) {
        let seen: Vec<&Person> = self.people.iter().filter(|p| ids.contains(&p.id)).collect();
        self.remembered = people::rename(&self.remembered, &seen, name);
        self.save(false, &self.remembered);
        self.regroup(false, ids);
    }

    /// The shots each shown person is in, by `Person::key`.
    pub fn shots_by_person(&self) -> HashMap<String, HashSet<String>> {
        let mut out: HashMap<String, HashSet<String>> = HashMap::new();
        for p in &self.people {
            out.entry(p.key()).or_default().extend(p.faces.iter().map(|f| f.shot.clone()));
        }
        out
    }
}

fn work(store: &CullStore, mut done: HashSet<String>, q: &Queue, tx: &Sender<(String, Insight)>, ctx: &eframe::egui::Context) {
    let mut faces = true;
    loop {
        let shot = {
            let mut todo = q.todo.lock().unwrap();
            loop {
                if q.stop.load(Ordering::Relaxed) {
                    return;
                }
                match todo.pop_front() {
                    Some(s) if done.contains(&s.key()) => continue,
                    Some(s) => break s,
                    None => todo = q.ready.wait(todo).unwrap(),
                }
            }
        };
        let key = shot.key();
        done.insert(key.clone());
        // A shot that can't be decoded counts as looked at (with nothing found), for now.
        let (insight, with_faces) = match analyse(&shot, &mut faces) {
            Ok(r) => r,
            Err(Some(e)) => {
                *q.faces_error.lock().unwrap() = Some(e);
                analyse(&shot, &mut faces).unwrap_or_else(|_| (empty(), false))
            }
            Err(None) => (empty(), false),
        };
        // Without faces it's only kept for now, so they're looked for next time.
        if with_faces {
            let _ = store.add_insight(&key, &insight);
        }
        if tx.send((key, insight)).is_err() {
            return;
        }
        ctx.request_repaint();
    }
}

/// Looked at as it's looked at now.
fn fresh(i: &Insight) -> bool {
    i.version == Insight::VERSION && i.model == Insight::FACE_MODEL
}

fn empty() -> Insight {
    Insight { faces: Vec::new(), sharp: 0.0, labels: Vec::new(), text: String::new(), version: Insight::VERSION, model: Insight::FACE_MODEL }
}

/// Faces and focus in a shot, and whether faces were looked for. Fails with why faces can't be
/// (then `faces_ok` is cleared, and it can be tried again without), or None when the shot
/// couldn't be decoded.
fn analyse(shot: &Shot, faces_ok: &mut bool) -> Result<(Insight, bool), Option<String>> {
    let show = shot.display();
    let img = images::decode(&show.path, EDGE, show.orientation).ok_or(None)?;
    let [w, h] = img.size;
    let grey: Vec<f32> = img.pixels.iter().map(|c| (0.299 * c.r() as f32 + 0.587 * c.g() as f32 + 0.114 * c.b() as f32) / 255.0).collect();
    let overall = cull::sharpness(&grey, w, h, None);
    let mut infos = Vec::new();
    if *faces_ok {
        let rgb: Vec<u8> = img.pixels.iter().flat_map(|c| [c.r(), c.g(), c.b()]).collect();
        let px = Rgb::from_bytes(&rgb, w, h);
        let found = ai::faces::find(&px).map_err(|e| {
            *faces_ok = false;
            Some(e)
        })?;
        let (fw, fh) = (w as f32, h as f32);
        for f in found {
            let b = [(f.b[0] / fw).clamp(0.0, 1.0), (f.b[1] / fh).clamp(0.0, 1.0), (f.b[2] / fw).clamp(0.0, 1.0), (f.b[3] / fh).clamp(0.0, 1.0)];
            // The middle of the face: its edges are often hair and background.
            let (bw, bh) = ((b[2] - b[0]) * fw, (b[3] - b[1]) * fh);
            let region =
                [(b[0] * fw + bw * 0.15) as usize, (b[1] * fh + bh * 0.15) as usize, (b[2] * fw - bw * 0.15) as usize, (b[3] * fh - bh * 0.15) as usize];
            let sharp = if region[2] < region[0] + 8 || region[3] < region[1] + 8 { 0.0 } else { cull::sharpness(&grey, w, h, Some(region)) };
            let known = f.width() >= MIN_FACE_PX && f.turned() <= TURNED;
            let embedding = known.then(|| ai::faces::embed(&px, &f).ok()).flatten();
            let phone = known.then(|| ai::faces::embed_phone(&px, &f).ok()).flatten();
            infos.push(FaceInfo { b, eyes_open: None, smile: None, sharp, embedding, phone });
        }
    }
    let looked = *faces_ok;
    let main = infos.iter().max_by(|a, b| a.width().total_cmp(&b.width())).map(|f| f.sharp);
    let model = if looked { Insight::FACE_MODEL } else { 0 };
    Ok((Insight { sharp: main.unwrap_or(overall), faces: infos, labels: Vec::new(), text: String::new(), version: Insight::VERSION, model }, looked))
}

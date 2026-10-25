//! Several shots selected at once in the library, for doing something to all of them. The shot
//! last clicked stays the active one (`App::selected`): what the details show and Edit opens.

use crate::app::App;
use eframe::egui::Modifiers;
use std::collections::HashSet;

#[derive(Default)]
pub struct Selection {
    /// The selected shots, by key; empty or just the active one when only one is selected.
    pub keys: HashSet<String>,
    /// Where Shift ranges start: the shot last clicked without Shift.
    pub anchor: Option<String>,
    /// Tick mode (started from a tile's tick or a day's): a plain click adds or takes away, so no
    /// key needs holding on a touchpad.
    pub ticking: bool,
}

impl Selection {
    pub fn clear(&mut self) {
        self.keys.clear();
        self.ticking = false;
    }
}

impl App {
    /// Two or more shots are selected.
    pub fn many(&self) -> bool {
        self.selection.keys.len() > 1
    }

    /// Ticks show on every tile: something is being gathered.
    pub fn gathering(&self) -> bool {
        self.selection.ticking || self.many()
    }

    pub fn is_selected(&self, key: &str) -> bool {
        self.selection.keys.contains(key) || self.selected.as_deref() == Some(key)
    }

    /// Only `key` selected (or nothing).
    pub fn select_one(&mut self, key: Option<String>) {
        self.selection.clear();
        self.selection.anchor = key.clone();
        self.selected = key;
    }

    /// What a batch acts on: the selected shots in the order shown, or the active one alone.
    pub fn targets(&self) -> Vec<String> {
        let visible = self.visible();
        if self.many() {
            visible.iter().map(|&i| self.library.shots[i].key()).filter(|k| self.selection.keys.contains(k)).collect()
        } else {
            self.selected.iter().filter(|k| visible.iter().any(|&i| &self.library.shots[i].key() == *k)).cloned().collect()
        }
    }

    /// Keeps the selection to the shots in view, and lets it go when the active shot was changed
    /// elsewhere (in Edit, say).
    pub fn tidy_selection(&mut self, visible: &[usize]) {
        let s = &mut self.selection;
        if s.keys.is_empty() {
            return;
        }
        let shown: HashSet<String> = visible.iter().map(|&i| self.library.shots[i].key()).collect();
        s.keys.retain(|k| shown.contains(k));
        if self.selected.as_ref().is_none_or(|a| !s.keys.contains(a)) {
            s.clear();
        }
    }

    /// A click on a tile, with the keys held.
    pub fn click(&mut self, key: &str, visible: &[usize], m: Modifiers) {
        if m.shift {
            self.select_range(key, visible, m.command);
        } else if m.command || self.selection.ticking {
            self.toggle(key);
        } else {
            self.select_one(Some(key.to_string()));
        }
    }

    /// A click on a tile's tick: starts tick mode with just this shot, or adds or takes it away.
    pub fn tick(&mut self, key: &str) {
        if self.gathering() {
            self.selection.ticking = true;
            self.toggle(key);
        } else {
            self.select_one(Some(key.to_string()));
            self.selection.keys.insert(key.to_string());
            self.selection.ticking = true;
        }
    }

    /// Adds `key` to the selection, or takes it away.
    pub fn toggle(&mut self, key: &str) {
        let s = &mut self.selection;
        if s.keys.is_empty() {
            if let Some(a) = &self.selected {
                s.keys.insert(a.clone());
            }
        }
        s.anchor = Some(key.to_string());
        if s.keys.remove(key) {
            if self.selected.as_deref() == Some(key) {
                self.selected = s.keys.iter().next().cloned();
            }
            if s.keys.is_empty() {
                s.ticking = false;
            }
        } else {
            s.keys.insert(key.to_string());
            self.selected = Some(key.to_string());
        }
    }

    /// Everything from the anchor to `key` in the order shown; `add` keeps what was selected.
    pub fn select_range(&mut self, key: &str, visible: &[usize], add: bool) {
        let keys: Vec<String> = visible.iter().map(|&i| self.library.shots[i].key()).collect();
        let anchor = self.selection.anchor.clone().or_else(|| self.selected.clone()).unwrap_or_else(|| key.to_string());
        let (Some(a), Some(b)) = (keys.iter().position(|k| *k == anchor), keys.iter().position(|k| k == key)) else {
            self.select_one(Some(key.to_string()));
            return;
        };
        if !add {
            self.selection.keys.clear();
        }
        self.selection.keys.extend(keys[a.min(b)..=a.max(b)].iter().cloned());
        self.selection.anchor = Some(anchor);
        self.selected = Some(key.to_string());
    }

    /// Shift and an arrow key: moves the active shot by `by` and selects from the anchor to it.
    pub fn extend(&mut self, visible: &[usize], by: isize) {
        if self.selection.anchor.is_none() || self.selection.keys.is_empty() {
            self.selection.anchor = self.selected.clone();
        }
        self.step(visible, by);
        if let Some(k) = self.selected.clone() {
            self.select_range(&k, visible, false);
        }
    }

    pub fn select_all(&mut self, visible: &[usize]) {
        self.selection.keys = visible.iter().map(|&i| self.library.shots[i].key()).collect();
        if self.selected.as_ref().is_none_or(|k| !self.selection.keys.contains(k)) {
            self.selected = visible.first().map(|&i| self.library.shots[i].key());
        }
    }

    /// A day's tick: selects all of `day` (tiles by index), or takes them away when all are in.
    pub fn tick_day(&mut self, day: &[usize]) {
        let keys: Vec<String> = day.iter().map(|&i| self.library.shots[i].key()).collect();
        let all_in = self.gathering() && keys.iter().all(|k| self.selection.keys.contains(k));
        if !self.gathering() {
            self.selection.keys.clear();
        }
        self.selection.ticking = true;
        if all_in {
            for k in &keys {
                self.selection.keys.remove(k);
            }
            if self.selected.as_ref().is_some_and(|a| keys.contains(a)) {
                self.selected = self.selection.keys.iter().next().cloned();
            }
            if self.selection.keys.is_empty() {
                self.selection.ticking = false;
            }
        } else {
            self.selection.keys.extend(keys.iter().cloned());
            if self.selected.as_ref().is_none_or(|a| !self.selection.keys.contains(a)) {
                self.selected = keys.first().cloned();
            }
        }
    }

    /// Whether every shot of `day` is selected.
    pub fn day_selected(&self, day: &[usize]) -> bool {
        self.gathering() && day.iter().all(|&i| self.selection.keys.contains(&self.library.shots[i].key()))
    }
}

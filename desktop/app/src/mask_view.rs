//! Masks: the panel that adds and tunes them, and what they put on the photo: handles for the
//! gradients, the brush, clicks that pick an object, and a red tint over the selected mask.

use crate::develop_view::{number, round_to, section};
use crate::editor::Editor;
use crate::masks::Finding;
use crate::theme::palette;
use crate::widgets::{chip, hint, DevelopSlider};
use eframe::egui::{self, Color32, Key, Pos2, Rect, RichText, Sense, Stroke, Ui, Vec2};
use shootback_core::ai::{self, catalog, Progress};
use shootback_core::develop::look::MASK_MAX;
use shootback_core::develop::maps::MapKind;
use shootback_core::develop::params::{local, Mask, MaskKind, Pick, Settings};

/// The brush, and which mask the photo's tools work on.
pub struct MaskTool {
    pub selected: Option<usize>,
    /// Tint the selected mask on the photo.
    pub show: bool,
    /// Brush radius, a fraction of the photo's long edge.
    pub size: f32,
    /// How much of the brush fades, 0..1.
    pub feather: f32,
    /// How much one dab adds, 0..1.
    pub flow: f32,
    pub erase: bool,
}

impl Default for MaskTool {
    fn default() -> Self {
        MaskTool { selected: None, show: true, size: 0.04, feather: 0.5, flow: 0.35, erase: false }
    }
}

/// The Masks panel, in place of the Develop ones while in masks mode.
pub fn panel(ui: &mut Ui, ed: &mut Editor, s: &mut Settings) {
    section(ui, "Masks", s, None, true, |ui, s| body(ui, ed, s));
    let sel = ed.tool.selected.filter(|&k| k < s.masks.len());
    ed.tool.selected = sel;
    ed.overlay = sel.filter(|_| ed.tool.show);
}

fn body(ui: &mut Ui, ed: &mut Editor, s: &mut Settings) {
    let pal = palette(ui.ctx());
    if s.masks.len() < MASK_MAX {
        ui.horizontal_wrapped(|ui| {
            ui.spacing_mut().item_spacing = Vec2::new(4.0, 4.0);
            for kind in MaskKind::ALL {
                let r = chip(ui, false, &format!("+ {}", short(kind))).on_hover_text(about(kind));
                if r.clicked() {
                    let m = match kind {
                        MaskKind::Linear => Mask::linear(),
                        MaskKind::Radial => Mask::radial(ed.photo_aspect()),
                        MaskKind::Brush | MaskKind::Object => Mask::with_map(kind),
                        MaskKind::Depth => Mask::depth(),
                        k => Mask::new(k),
                    };
                    s.masks.push(m);
                    ed.tool.selected = Some(s.masks.len() - 1);
                    ed.tool.erase = false;
                }
            }
        });
    } else {
        hint(ui, "That's the most masks one photo can have.");
    }
    if s.masks.is_empty() {
        hint(ui, "A mask applies its own sliders to part of the photo: a gradient, what you paint, the subject, the sky, something you click on, or what's near or far.");
        return;
    }
    ui.add_space(6.0);
    let mut remove = None;
    let mut duplicate = None;
    for i in 0..s.masks.len() {
        let selected = ed.tool.selected == Some(i);
        let m = &s.masks[i];
        let (rect, resp) = ui.allocate_exact_size(Vec2::new(ui.available_width(), 26.0), Sense::click());
        if selected {
            ui.painter().rect_filled(rect, pal.radius, pal.accent.gamma_multiply(0.14));
        } else if resp.hovered() {
            ui.painter().rect_filled(rect, pal.radius, pal.text3.gamma_multiply(0.08));
        }
        let label = format!("{}  {}{}", i + 1, m.kind.label(), if m.invert { " (inverted)" } else { "" });
        ui.painter().text(
            rect.left_center() + Vec2::new(8.0, 0.0),
            egui::Align2::LEFT_CENTER,
            label,
            egui::FontId::proportional(13.0),
            if selected { pal.strong } else { pal.text },
        );
        if let Some(state) = state_line(ed, m) {
            ui.painter().text(rect.right_center() - Vec2::new(8.0, 0.0), egui::Align2::RIGHT_CENTER, state, egui::FontId::proportional(11.5), pal.text3);
        }
        if resp.clicked() {
            ed.tool.selected = if selected { None } else { Some(i) };
        }
        resp.context_menu(|ui| {
            if ui.button("Duplicate").clicked() {
                duplicate = Some(i);
                ui.close_menu();
            }
            if ui.button("Remove").clicked() {
                remove = Some(i);
                ui.close_menu();
            }
        });
    }
    if let Some(i) = duplicate.filter(|_| s.masks.len() < MASK_MAX) {
        let mut copy = s.masks[i].clone();
        if copy.kind.has_own_map() {
            let fresh = Mask::with_map(copy.kind);
            ed.maps.copy_map(&copy.id, &fresh.id);
            copy.id = fresh.id;
        }
        s.masks.insert(i + 1, copy);
        ed.tool.selected = Some(i + 1);
    }
    if let Some(i) = remove {
        s.masks.remove(i);
        ed.tool.selected = None;
    }
    let Some(k) = ed.tool.selected.filter(|&k| k < s.masks.len()) else {
        ui.add_space(4.0);
        hint(ui, "Pick a mask to tune it.");
        return;
    };
    ui.add_space(8.0);
    tools(ui, ed, s, k);
    ui.add_space(4.0);
    let m = &mut s.masks[k];
    for q in local::ALL {
        let v = m.get(&q);
        let slider = DevelopSlider {
            label: q.label,
            text: number(v, q.decimals, true),
            value: v,
            min: q.min,
            max: q.max,
            origin: 0.0,
            default: q.default,
            swatch: None,
        };
        if let Some(n) = slider.show(ui) {
            m.set(&q, round_to(n, q.decimals));
        }
    }
    ui.add_space(4.0);
    ui.horizontal_wrapped(|ui| {
        ui.spacing_mut().item_spacing = Vec2::new(4.0, 4.0);
        if chip(ui, m.invert, "Invert").clicked() {
            m.invert = !m.invert;
        }
        if m.kind != MaskKind::Subject && chip(ui, m.exclude_subject, "Leave out subject").on_hover_text("Keeps the subject out of this mask").clicked() {
            m.exclude_subject = !m.exclude_subject;
        }
        if chip(ui, ed.tool.show, "Show  O").on_hover_text("Tints the mask red on the photo").clicked() {
            ed.tool.show = !ed.tool.show;
        }
        if chip(ui, false, "Remove").clicked() {
            remove = Some(k);
        }
    });
    if let Some(i) = remove {
        s.masks.remove(i);
        ed.tool.selected = None;
    }
}

fn short(kind: MaskKind) -> &'static str {
    match kind {
        MaskKind::Linear => "Linear",
        MaskKind::Radial => "Radial",
        MaskKind::Depth => "Depth",
        k => k.label(),
    }
}

fn about(kind: MaskKind) -> &'static str {
    match kind {
        MaskKind::Linear => "A gradient: full at one handle, gone at the other",
        MaskKind::Radial => "An ellipse that fades at its edge",
        MaskKind::Brush => "Paint where it applies",
        MaskKind::Subject => "The photo's main subject, found by a model",
        MaskKind::Sky => "The sky, found in the photo",
        MaskKind::Object => "Click on something to select it",
        MaskKind::Depth => "What's near, or far, by a depth model",
    }
}

/// The model a mask needs, if any.
fn model_of(kind: MaskKind) -> Option<&'static catalog::Model> {
    match kind {
        MaskKind::Subject => Some(&catalog::SUBJECT),
        MaskKind::Object => Some(&catalog::OBJECTS),
        MaskKind::Depth => Some(&catalog::DEPTH),
        _ => None,
    }
}

fn map_of(kind: MaskKind) -> Option<MapKind> {
    match kind {
        MaskKind::Subject => Some(MapKind::Subject),
        MaskKind::Sky => Some(MapKind::Sky),
        MaskKind::Depth => Some(MapKind::Depth),
        _ => None,
    }
}

/// A download in progress for the model `m` (or the runtime it needs), as a short line.
fn downloading(m: &catalog::Model) -> Option<String> {
    for model in [m, &catalog::RUNTIME] {
        if let Some(Progress::Downloading(f)) = ai::status(model) {
            return Some(format!("Downloading {} {:.0}%", model.label.to_lowercase(), f * 100.0));
        }
    }
    None
}

/// What a mask is waiting for, shown beside it in the list.
fn state_line(ed: &Editor, m: &Mask) -> Option<String> {
    if let Some(d) = model_of(m.kind).and_then(downloading) {
        return Some(d);
    }
    match (map_of(m.kind), m.kind) {
        (Some(kind), _) => match ed.maps.state(kind) {
            Finding::Running => Some("Looking ...".into()),
            Finding::Failed(_) => Some("Not found".into()),
            Finding::Idle => None,
        },
        (None, MaskKind::Object) => {
            if ed.maps.picking(&m.id) {
                Some("Selecting ...".into())
            } else if m.picks.is_empty() {
                Some("Click the photo".into())
            } else {
                None
            }
        }
        _ => None,
    }
}

/// The controls of the selected mask's tool.
fn tools(ui: &mut Ui, ed: &mut Editor, s: &mut Settings, k: usize) {
    let pal = palette(ui.ctx());
    let kind = s.masks[k].kind;
    if let Some(kind) = map_of(kind) {
        if let Finding::Failed(e) = ed.maps.state(kind).clone() {
            ui.label(RichText::new(e).size(12.0).color(pal.accent));
            if chip(ui, false, "Try again").clicked() {
                ed.maps.retry(kind);
            }
        }
    }
    if kind == MaskKind::Object {
        if let Finding::Failed(e) = &ed.maps.embedding_state {
            ui.label(RichText::new(e.clone()).size(12.0).color(pal.accent));
        }
    }
    if let Some(m) = model_of(kind) {
        if !ai::installed(m) && downloading(m).is_none() {
            hint(ui, &format!("Uses the {} model ({} MB, {}), downloaded the first time.", m.label.to_lowercase(), m.bytes() / 1_000_000, m.licence));
        }
    }
    let mask = &mut s.masks[k];
    let slider = |ui: &mut Ui, label: &str, v: f32, min: f32, max: f32, default: f32| {
        DevelopSlider { label, text: format!("{:.0}", v * 100.0), value: v, min, max, origin: min, default, swatch: None }.show(ui)
    };
    match kind {
        MaskKind::Linear => hint(ui, "Drag the handles on the photo: the effect is full at the solid one and gone at the hollow one."),
        MaskKind::Radial => {
            hint(ui, "Drag the middle to move it, the side handles to size and turn it.");
            if let Some(v) = slider(ui, "Feather", mask.feather / 100.0, 0.0, 1.0, 0.5) {
                mask.feather = (v * 100.0).round();
            }
        }
        MaskKind::Brush => {
            hint(ui, "Paint on the photo. Hold Alt, or switch to Erase, to take away. [ and ] change the size.");
            let t = &mut ed.tool;
            if let Some(v) = slider(ui, "Size", t.size, 0.005, 0.25, 0.04) {
                t.size = v;
            }
            if let Some(v) = slider(ui, "Feather", t.feather, 0.0, 1.0, 0.5) {
                t.feather = v;
            }
            if let Some(v) = slider(ui, "Flow", t.flow, 0.05, 1.0, 0.35) {
                t.flow = v;
            }
            ui.horizontal(|ui| {
                if chip(ui, !t.erase, "Paint").clicked() {
                    t.erase = false;
                }
                if chip(ui, t.erase, "Erase").clicked() {
                    t.erase = true;
                }
            });
        }
        MaskKind::Object => {
            hint(
                ui,
                "Click what to select. Alt-click takes a part away; Undo takes back a click. Smaller and Larger switch between a part and the whole thing.",
            );
            if !mask.picks.is_empty() {
                ui.horizontal_wrapped(|ui| {
                    if mask.grain > -2 && chip(ui, false, "Smaller").clicked() {
                        mask.grain -= 1;
                    }
                    if mask.grain < 2 && chip(ui, false, "Larger").clicked() {
                        mask.grain += 1;
                    }
                    if chip(ui, false, "Start over").clicked() {
                        mask.picks.clear();
                        mask.grain = 0;
                    }
                });
            }
        }
        MaskKind::Depth => {
            hint(ui, "Selects a range of distances: 0 is the farthest part of the photo, 100 the nearest.");
            if let Some(v) = slider(ui, "From", mask.x0, 0.0, 1.0, 0.6) {
                mask.x0 = round_to(v.min(mask.x1), 2);
            }
            if let Some(v) = slider(ui, "To", mask.x1, 0.0, 1.0, 1.0) {
                mask.x1 = round_to(v.max(mask.x0), 2);
            }
            if let Some(v) = slider(ui, "Softness", mask.feather / 100.0, 0.0, 1.0, 0.3) {
                mask.feather = (v * 100.0).round();
            }
        }
        MaskKind::Subject | MaskKind::Sky => {}
    }
}

/// The selected mask's handles and tools over the photo, drawn in `r` (where the preview is).
/// Returns whether it took the pointer.
pub fn overlay(ed: &mut Editor, ui: &mut Ui, r: Rect) -> bool {
    let Some(k) = ed.tool.selected else { return false };
    let Some(m) = ed.s.masks.get(k).cloned() else { return false };
    let pal = palette(ui.ctx());
    let to_screen = |ed: &Editor, u: f32, v: f32| ed.to_preview(u, v).map(|(x, y)| Pos2::new(r.left() + x * r.width(), r.top() + y * r.height()));
    let to_photo = |ed: &Editor, p: Pos2| ed.to_photo((p.x - r.left()) / r.width(), (p.y - r.top()) / r.height());
    let painter = ui.painter_at(r.expand(8.0));
    let resp = ui.interact(r, ui.id().with(("mask-tool", k)), Sense::click_and_drag());
    let alt = ui.input(|i| i.modifiers.alt);
    let dot = |p: Pos2, filled: bool| {
        painter.circle_filled(p, 6.5, Color32::from_black_alpha(120));
        if filled {
            painter.circle_filled(p, 5.0, Color32::WHITE);
        } else {
            painter.circle_stroke(p, 4.5, Stroke::new(2.0_f32, Color32::WHITE));
        }
    };
    let grab_id = ui.id().with("mask-grab");
    match m.kind {
        MaskKind::Linear => {
            let (Some(a), Some(b)) = (to_screen(ed, m.x0, m.y0), to_screen(ed, m.x1, m.y1)) else { return false };
            let d = b - a;
            let n = Vec2::new(-d.y, d.x).normalized() * r.width().max(r.height());
            let lines = ui.painter_at(r);
            for (p, w) in [(a, 1.5_f32), (b, 1.0)] {
                lines.line_segment([p - n, p + n], Stroke::new(w, Color32::from_white_alpha(150)));
            }
            painter.line_segment([a, b], Stroke::new(1.0_f32, Color32::from_white_alpha(110)));
            dot(a, true);
            dot(b, false);
            let near = |p: Pos2| {
                if p.distance(a) < 12.0 {
                    Some(0)
                } else if p.distance(b) < 12.0 {
                    Some(1)
                } else {
                    Some(2)
                }
            };
            drag_points(ed, ui, &resp, grab_id, near, |ed, grip, from, to| {
                let (Some(p0), Some(p1)) = (to_photo(ed, from), to_photo(ed, to)) else { return };
                let (du, dv) = (p1.0 - p0.0, p1.1 - p0.1);
                let mut s = ed.s.clone();
                let mm = &mut s.masks[k];
                if grip != 1 {
                    mm.x0 += du;
                    mm.y0 += dv;
                }
                if grip != 0 {
                    mm.x1 += du;
                    mm.y1 += dv;
                }
                ed.change(s);
            });
            true
        }
        MaskKind::Radial => {
            let aspect = ed.photo_aspect();
            let (cs, sn) = (m.angle.to_radians().cos(), m.angle.to_radians().sin());
            // Points on the ellipse in photo fractions (radii are of the width and the height).
            let at = |t: f32, scale: f32| {
                let (ex, ey) = (m.x1 * t.cos() * scale, m.y1 * t.sin() * scale);
                // Turn in pixel space, where the angle is.
                let (px, py) = (ex * aspect, ey);
                let (rx, ry) = (px * cs - py * sn, px * sn + py * cs);
                (m.x0 + rx / aspect, m.y0 + ry)
            };
            let ring = |scale: f32| -> Vec<Pos2> {
                (0..=64).map(|i| at(i as f32 / 64.0 * std::f32::consts::TAU, scale)).filter_map(|(u, v)| to_screen(ed, u, v)).collect()
            };
            painter.add(egui::Shape::line(ring(1.0), Stroke::new(1.5_f32, Color32::from_white_alpha(170))));
            let inner = 1.0 - (m.feather / 100.0).max(0.02);
            painter.add(egui::Shape::dashed_line(&ring(inner), Stroke::new(1.0_f32, Color32::from_white_alpha(110)), 4.0, 4.0));
            let c = to_screen(ed, m.x0, m.y0);
            let (hu, hv) = at(0.0, 1.0);
            let hx = to_screen(ed, hu, hv);
            let (hu, hv) = at(std::f32::consts::FRAC_PI_2, 1.0);
            let hy = to_screen(ed, hu, hv);
            let (Some(c), Some(hx), Some(hy)) = (c, hx, hy) else { return false };
            dot(c, true);
            dot(hx, false);
            dot(hy, false);
            let near = |p: Pos2| {
                if p.distance(hx) < 12.0 {
                    Some(1)
                } else if p.distance(hy) < 12.0 {
                    Some(2)
                } else {
                    Some(0)
                }
            };
            drag_points(ed, ui, &resp, grab_id, near, |ed, grip, from, to| {
                let (Some(p0), Some(p1)) = (to_photo(ed, from), to_photo(ed, to)) else { return };
                let mut s = ed.s.clone();
                let mm = &mut s.masks[k];
                if grip == 0 {
                    mm.x0 += p1.0 - p0.0;
                    mm.y0 += p1.1 - p0.1;
                } else {
                    // The handle follows the pointer: the radius is its distance from the
                    // middle, the side handle's direction the angle.
                    let (dx, dy) = ((p1.0 - mm.x0) * aspect, p1.1 - mm.y0);
                    let dist = (dx * dx + dy * dy).sqrt();
                    if grip == 1 {
                        mm.x1 = (dist / aspect).max(0.005);
                        mm.angle = dy.atan2(dx).to_degrees();
                    } else {
                        mm.y1 = dist.max(0.005);
                    }
                }
                ed.change(s);
            });
            true
        }
        MaskKind::Brush => {
            let hover = resp.hover_pos();
            // The brush's size on screen, from how far a step across the photo moves on screen.
            let radius_px = hover.and_then(|p| {
                let (u, v) = to_photo(ed, p)?;
                let edge = if ed.photo_aspect() >= 1.0 { (ed.tool.size, 0.0) } else { (0.0, ed.tool.size) };
                let q = to_screen(ed, u + edge.0, v + edge.1)?;
                Some(p.distance(q))
            });
            if let (Some(p), Some(rp)) = (hover, radius_px) {
                ui.ctx().set_cursor_icon(egui::CursorIcon::None);
                painter.circle_stroke(p, rp, Stroke::new(1.5_f32, Color32::WHITE));
                painter.circle_stroke(p, rp * (1.0 - ed.tool.feather), Stroke::new(1.0_f32, Color32::from_white_alpha(120)));
                painter.circle_stroke(p, rp + 1.5, Stroke::new(1.0_f32, Color32::from_black_alpha(120)));
            }
            let erase = ed.tool.erase ^ alt;
            let last_id = ui.id().with("brush-last");
            if resp.is_pointer_button_down_on() {
                if let (Some(p), Some(rp)) = (resp.interact_pointer_pos(), radius_px.or(Some(10.0))) {
                    let last: Option<Pos2> = ui.data(|d| d.get_temp(last_id));
                    // Dabs a fifth of the brush apart along the stroke.
                    let step = (rp * 0.2).max(1.0);
                    let from = last.unwrap_or(p);
                    let n = ((p.distance(from) / step).floor() as usize).max(if last.is_none() { 1 } else { 0 });
                    let mut end = from;
                    for i in 1..=n {
                        let q = if last.is_none() { p } else { from + (p - from) * (i as f32 * step / p.distance(from)).min(1.0) };
                        if let Some((u, v)) = to_photo(ed, q) {
                            let t = &ed.tool;
                            let (size, feather, flow) = (t.size, t.feather, t.flow);
                            ed.maps.dab(&m.id, u, v, size, feather, flow, erase);
                        }
                        end = q;
                    }
                    ui.data_mut(|d| d.insert_temp(last_id, end));
                }
            } else {
                ui.data_mut(|d| d.remove::<Pos2>(last_id));
            }
            if resp.drag_stopped() || resp.clicked() {
                ed.maps.save();
            }
            true
        }
        MaskKind::Object => {
            for p in &m.picks {
                if let Some(q) = to_screen(ed, p.x, p.y) {
                    painter.circle_filled(q, 7.0, Color32::from_black_alpha(130));
                    painter.circle_filled(q, 5.0, if p.add { Color32::from_rgb(0x4E, 0xC0, 0x6A) } else { pal.seal });
                    let sign = if p.add { "+" } else { "-" };
                    painter.text(q, egui::Align2::CENTER_CENTER, sign, egui::FontId::proportional(10.0), Color32::WHITE);
                }
            }
            if resp.hovered() {
                ui.ctx().set_cursor_icon(egui::CursorIcon::Crosshair);
            }
            if resp.clicked() {
                if let Some((u, v)) = resp.interact_pointer_pos().and_then(|p| to_photo(ed, p)) {
                    let mut s = ed.s.clone();
                    s.masks[k].picks.push(Pick { x: u, y: v, add: !alt });
                    ed.change(s);
                }
            }
            true
        }
        _ => false,
    }
}

/// Dragging one of a shape's handles: `near` says which grip a press lands on; `apply` moves it
/// from one pointer position to the next.
fn drag_points(
    ed: &mut Editor,
    ui: &mut Ui,
    resp: &egui::Response,
    id: egui::Id,
    near: impl Fn(Pos2) -> Option<usize>,
    apply: impl Fn(&mut Editor, usize, Pos2, Pos2),
) {
    if let Some(p) = resp.hover_pos() {
        ui.ctx().set_cursor_icon(if near(p) == Some(0) || near(p) == Some(2) { egui::CursorIcon::Grab } else { egui::CursorIcon::PointingHand });
    }
    if resp.drag_started() {
        let g = resp.interact_pointer_pos().and_then(&near);
        ui.data_mut(|d| d.insert_temp(id, g));
    }
    let grip: Option<usize> = ui.data(|d| d.get_temp(id)).flatten();
    if let (Some(g), true) = (grip, resp.dragged()) {
        if let Some(to) = resp.interact_pointer_pos() {
            let from = to - resp.drag_delta();
            apply(ed, g, from, to);
        }
    }
    if resp.drag_stopped() {
        ui.data_mut(|d| d.remove::<Option<usize>>(id));
    }
}

/// The masks' shortcuts in Edit: O shows the tint, [ and ] size the brush, Esc lets go of the mask.
pub fn keys(ed: &mut Editor, ctx: &egui::Context) {
    if ctx.wants_keyboard_input() || ed.tool.selected.is_none() {
        return;
    }
    let (o, smaller, bigger, esc) = ctx.input(|i| {
        let plain = !i.modifiers.any();
        (plain && i.key_pressed(Key::O), i.key_pressed(Key::OpenBracket), i.key_pressed(Key::CloseBracket), i.key_pressed(Key::Escape))
    });
    if o {
        ed.tool.show = !ed.tool.show;
    }
    if smaller {
        ed.tool.size = (ed.tool.size / 1.2).max(0.005);
    }
    if bigger {
        ed.tool.size = (ed.tool.size * 1.2).min(0.25);
    }
    if esc && !ed.cropping {
        // Only lets go of the mask; a second Esc leaves masks mode.
        ed.tool.selected = None;
        ctx.input_mut(|i| i.consume_key(egui::Modifiers::NONE, Key::Escape));
    }
}

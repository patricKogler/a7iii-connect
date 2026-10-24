//! The Develop panels, in Lightroom's order: Light, Curve, Color, Mixer, Grading, Effects, Detail,
//! Optics, Geometry and Crop, plus the masks made on the phone.

use crate::editor::Editor;
use crate::theme::{display, palette};
use crate::widgets::{chip, hint, outline_button, DevelopSlider};
use eframe::egui::{self, collapsing_header::CollapsingState, Color32, FontId, Pos2, Rect, RichText, Sense, Stroke, Ui, Vec2};
use shootback_core::develop::look::{hsv_to_rgb, Source};
use shootback_core::develop::params::{local, p, Curve, Group, MaskKind, Param, Profile, Settings, WHITE_BALANCES};

/// Shows every panel, editing `s` (the open photo's settings).
pub fn panels(ui: &mut Ui, ed: &Editor, s: &mut Settings) {
    let source = ed.opened.as_ref().map(|o| &o.source);
    section(ui, "Light", s, Some(Group::Light), true, |ui, s| light(ui, ed, s));
    section(ui, "Curve", s, Some(Group::Curve), false, curve);
    section(ui, "Color", s, Some(Group::Color), true, |ui, s| color(ui, s, source));
    section(ui, "Color mixer", s, Some(Group::Mixer), false, mixer);
    section(ui, "Color grading", s, Some(Group::Grading), false, grading);
    section(ui, "Effects", s, Some(Group::Effects), false, effects);
    section(ui, "Detail", s, Some(Group::Detail), false, detail);
    section(ui, "Optics", s, Some(Group::Optics), false, |ui, s| optics(ui, s, source));
    section(ui, "Geometry", s, Some(Group::Geometry), false, geometry);
    section(ui, "Crop", s, Some(Group::Crop), false, |ui, s| crop(ui, s, source));
    if !s.masks.is_empty() {
        section(ui, "Masks", s, None, false, masks);
    }
}

/// A panel: Mincho title, a Reset when it's changed, and its controls.
fn section(ui: &mut Ui, title: &str, s: &mut Settings, group: Option<Group>, open: bool, body: impl FnOnce(&mut Ui, &mut Settings)) {
    let pal = palette(ui.ctx());
    let id = ui.make_persistent_id(("develop-section", title));
    let state = CollapsingState::load_with_default_open(ui.ctx(), id, open);
    let changed = group.is_some_and(|g| !s.is_default_group(g));
    let header = state.show_header(ui, |ui| {
        let mut job = egui::text::LayoutJob::default();
        job.append(
            title,
            0.0,
            egui::TextFormat { font_id: FontId::new(14.5, display()), color: pal.strong, extra_letter_spacing: 0.8 * pal.tracking, ..Default::default() },
        );
        if changed {
            job.append(
                "  ●",
                0.0,
                egui::TextFormat { font_id: FontId::proportional(8.0), color: pal.accent, valign: egui::Align::Center, ..Default::default() },
            );
        }
        let r = ui.add(egui::Label::new(job).sense(Sense::click()));
        crate::widgets::right_to_left(ui, |ui| {
            if let (true, Some(g)) = (changed, group) {
                if ui.add(egui::Label::new(RichText::new("Reset").size(12.0).color(pal.text3)).sense(Sense::click())).clicked() {
                    s.reset(g);
                }
            }
        });
        r
    });
    let (_, head, _) = header.body(|ui| {
        ui.add_space(2.0);
        body(ui, s);
        ui.add_space(6.0);
    });
    if head.inner.clicked() {
        let mut st = CollapsingState::load_with_default_open(ui.ctx(), id, open);
        st.toggle(ui);
        st.store(ui.ctx());
    }
    let r = ui.available_rect_before_wrap();
    ui.painter().hline(r.x_range(), r.top(), Stroke::new(1.0_f32, pal.border));
    ui.add_space(4.0);
}

fn number(v: f32, decimals: u8, signed: bool) -> String {
    let t = format!("{:.*}", decimals as usize, v);
    let zero = t.trim_start_matches('-').chars().all(|c| c == '0' || c == '.');
    if zero {
        format!("{:.*}", decimals as usize, 0.0)
    } else if signed && v > 0.0 {
        format!("+{t}")
    } else {
        t
    }
}

/// The resting point a slider's fill starts from: its default, or zero on a two-sided slider.
fn origin(q: &Param) -> f32 {
    if q.default.is_nan() {
        q.min
    } else if q.min < 0.0 && q.max > 0.0 {
        0.0
    } else {
        q.default
    }
}

fn round_to(v: f32, decimals: u8) -> f32 {
    let k = 10f32.powi(decimals as i32);
    (v * k).round() / k
}

/// A slider for one parameter.
fn param(ui: &mut Ui, s: &mut Settings, q: &Param) {
    param_with(ui, s, q, q.label, None);
}

fn param_with(ui: &mut Ui, s: &mut Settings, q: &Param, label: &str, swatch: Option<Color32>) {
    let v = s.get(q);
    let slider =
        DevelopSlider { label, text: number(v, q.decimals, q.min < 0.0), value: v, min: q.min, max: q.max, origin: origin(q), default: q.default, swatch };
    if let Some(n) = slider.show(ui) {
        s.set(q, if n == q.default { n } else { round_to(n, q.decimals) });
    }
}

fn switch(ui: &mut Ui, s: &mut Settings, q: &Param) {
    let mut on = s.on(q);
    if ui.checkbox(&mut on, q.label).changed() {
        s.set(q, if on { 1.0 } else { 0.0 });
    }
}

fn sub(ui: &mut Ui, text: &str) {
    ui.add_space(4.0);
    ui.label(RichText::new(text).size(11.5).color(palette(ui.ctx()).text3));
}

fn light(ui: &mut Ui, ed: &Editor, s: &mut Settings) {
    ui.horizontal_wrapped(|ui| {
        for pr in Profile::ALL {
            if chip(ui, s.profile == pr, pr.label()).clicked() {
                s.profile = pr;
            }
        }
    });
    if let Some(id) = s.lut.clone() {
        ui.add_space(4.0);
        ui.horizontal(|ui| {
            let missing = ed.lut_missing();
            let pal = palette(ui.ctx());
            ui.label(RichText::new(format!("Look: {id}")).color(if missing { pal.accent } else { pal.text }));
            if ui.add(egui::Label::new(RichText::new("Remove").size(12.0).color(pal.text3)).sense(Sense::click())).clicked() {
                s.lut = None;
            }
        });
        if ed.lut_missing() {
            hint(ui, "This look isn't in this library, so it renders without it.");
        } else {
            param(ui, s, &p::LUT_AMOUNT);
        }
    }
    ui.add_space(4.0);
    for q in [p::EXPOSURE, p::CONTRAST, p::HIGHLIGHTS, p::SHADOWS, p::WHITES, p::BLACKS] {
        param(ui, s, &q);
    }
}

fn color(ui: &mut Ui, s: &mut Settings, source: Option<&Source>) {
    let (k0, t0) = source.map_or((5500.0, 0.0), |o| o.as_shot());
    ui.horizontal_wrapped(|ui| {
        let as_shot = s.temperature().is_none() && s.tint().is_none();
        if chip(ui, as_shot, "As Shot").clicked() {
            s.set_white_balance(None, None);
        }
        for (name, k, t) in WHITE_BALANCES {
            if chip(ui, s.temperature() == Some(k) && s.tint() == Some(t), name).clicked() {
                s.set_white_balance(Some(k), Some(t));
            }
        }
    });
    ui.add_space(4.0);
    // Temperature moves evenly in mired (1/K), like Lightroom's: fine where light is warm.
    let k = s.temperature().unwrap_or(k0 as f32);
    let t = s.tint().unwrap_or(t0 as f32);
    let mired = |k: f32| -1.0e6 / k;
    let swatch = |h: f32| {
        let c = hsv_to_rgb(h, 0.7, 0.9).map(|v| (v * 255.0) as u8);
        Color32::from_rgb(c[0], c[1], c[2])
    };
    let temp = DevelopSlider {
        label: "Temp",
        text: format!("{:.0} K", k),
        value: mired(k),
        min: mired(p::TEMPERATURE.min),
        max: mired(p::TEMPERATURE.max),
        origin: mired(k0 as f32),
        default: f32::NAN,
        swatch: Some(swatch(40.0)),
    };
    if let Some(m) = temp.show(ui) {
        if m.is_nan() {
            s.set_white_balance(None, None);
        } else {
            s.set_white_balance(Some((-1.0e6 / m / 10.0).round() * 10.0), Some(t));
        }
    }
    let tint = DevelopSlider {
        label: "Tint",
        text: number(t, 0, true),
        value: t,
        min: p::TINT.min,
        max: p::TINT.max,
        origin: t0 as f32,
        default: f32::NAN,
        swatch: Some(swatch(300.0)),
    };
    if let Some(v) = tint.show(ui) {
        if v.is_nan() {
            s.set_white_balance(None, None);
        } else {
            s.set_white_balance(Some(k), Some(v.round()));
        }
    }
    ui.add_space(4.0);
    param(ui, s, &p::VIBRANCE);
    param(ui, s, &p::SATURATION);
}

fn band_color(hue: f32) -> Color32 {
    let c = hsv_to_rgb(hue, 0.75, 0.9).map(|v| (v * 255.0) as u8);
    Color32::from_rgb(c[0], c[1], c[2])
}

fn mixer(ui: &mut Ui, s: &mut Settings) {
    let id = ui.id().with("mixer-mode");
    let mut mode: usize = ui.memory(|m| m.data.get_temp(id).unwrap_or(0));
    ui.horizontal(|ui| {
        for (i, name) in ["Hue", "Saturation", "Luminance"].into_iter().enumerate() {
            if chip(ui, mode == i, name).clicked() {
                mode = i;
            }
        }
    });
    ui.memory_mut(|m| m.data.insert_temp(id, mode));
    let params = [p::MIX_HUE, p::MIX_SAT, p::MIX_LUM][mode];
    for (i, q) in params.iter().enumerate() {
        param_with(ui, s, q, q.label, Some(band_color(p::MIX_BANDS[i].1)));
    }
}

fn grading(ui: &mut Ui, s: &mut Settings) {
    let id = ui.id().with("grading-zone");
    let mut zone: usize = ui.memory(|m| m.data.get_temp(id).unwrap_or(1));
    ui.horizontal_wrapped(|ui| {
        for (i, name) in p::GRADE_ZONES.into_iter().enumerate() {
            if chip(ui, zone == i, name).clicked() {
                zone = i;
            }
        }
    });
    ui.memory_mut(|m| m.data.insert_temp(id, zone));
    let hue = s.get(&p::GRADE_HUE[zone]);
    param_with(ui, s, &p::GRADE_HUE[zone], "Hue", Some(band_color(hue)));
    param(ui, s, &p::GRADE_SAT[zone]);
    param(ui, s, &p::GRADE_LUM[zone]);
    ui.add_space(4.0);
    param(ui, s, &p::GRADE_BLENDING);
    param(ui, s, &p::GRADE_BALANCE);
}

fn effects(ui: &mut Ui, s: &mut Settings) {
    for q in [p::TEXTURE, p::CLARITY, p::DEHAZE] {
        param(ui, s, &q);
    }
    sub(ui, "Vignette");
    param_with(ui, s, &p::VIGNETTE, "Amount", None);
    if s.get(&p::VIGNETTE) != 0.0 {
        for q in [p::VIGNETTE_MIDPOINT, p::VIGNETTE_FEATHER, p::VIGNETTE_ROUNDNESS, p::VIGNETTE_HIGHLIGHTS] {
            param(ui, s, &q);
        }
    }
    sub(ui, "Grain");
    param_with(ui, s, &p::GRAIN, "Amount", None);
    if s.get(&p::GRAIN) > 0.0 {
        param(ui, s, &p::GRAIN_SIZE);
        param(ui, s, &p::GRAIN_ROUGHNESS);
    }
}

fn detail(ui: &mut Ui, s: &mut Settings) {
    param(ui, s, &p::SHARPEN);
    for q in [p::SHARPEN_RADIUS, p::SHARPEN_DETAIL, p::SHARPEN_MASKING] {
        param(ui, s, &q);
    }
    ui.add_space(4.0);
    param(ui, s, &p::NOISE);
    if s.get(&p::NOISE) > 0.0 {
        param(ui, s, &p::NOISE_DETAIL);
    }
    param(ui, s, &p::COLOR_NOISE);
}

fn optics(ui: &mut Ui, s: &mut Settings, source: Option<&Source>) {
    if source.is_some_and(|o| o.lens.is_some()) {
        switch(ui, s, &p::LENS_PROFILE);
        if s.on(&p::LENS_PROFILE) {
            switch(ui, s, &p::REMOVE_CA);
            param(ui, s, &p::LENS_DISTORTION);
            param(ui, s, &p::LENS_VIGNETTING);
        }
    } else {
        hint(ui, "This photo carries no lens corrections (only Sony raws do).");
    }
    sub(ui, "Manual");
    for q in [p::MANUAL_DISTORTION, p::MANUAL_VIGNETTING] {
        param(ui, s, &q);
    }
    if s.get(&p::MANUAL_VIGNETTING) != 0.0 {
        param(ui, s, &p::MANUAL_VIGNETTING_MIDPOINT);
    }
    param(ui, s, &p::DEFRINGE);
}

fn geometry(ui: &mut Ui, s: &mut Settings) {
    for q in [p::VERTICAL, p::HORIZONTAL, p::ROTATE, p::SCALE, p::OFFSET_X, p::OFFSET_Y] {
        param(ui, s, &q);
    }
}

/// The crop box as left, top, right, bottom fractions of the turned frame.
pub fn crop_box(s: &Settings) -> [f32; 4] {
    [s.get(&p::CROP_LEFT), s.get(&p::CROP_TOP), s.get(&p::CROP_RIGHT), s.get(&p::CROP_BOTTOM)]
}

pub fn set_crop_box(s: &mut Settings, c: [f32; 4]) {
    for (q, v) in [p::CROP_LEFT, p::CROP_TOP, p::CROP_RIGHT, p::CROP_BOTTOM].iter().zip(c) {
        s.set(q, v);
    }
}

/// A quarter turn (clockwise or not), taking the crop box along.
pub fn turn(s: &mut Settings, clockwise: bool) {
    let r = s.get(&p::ROTATE_90) as i32;
    let [l, t, rr, b] = crop_box(s);
    // Flips act on the turned frame, so they stay as they are.
    let c = if clockwise { [1.0 - b, l, 1.0 - t, rr] } else { [t, 1.0 - rr, b, 1.0 - l] };
    s.set(&p::ROTATE_90, (r + if clockwise { 1 } else { 3 }).rem_euclid(4) as f32);
    set_crop_box(s, c);
}

fn flip(s: &mut Settings, horizontal: bool) {
    let [l, t, r, b] = crop_box(s);
    let (q, c) = if horizontal { (p::FLIP_H, [1.0 - r, t, 1.0 - l, b]) } else { (p::FLIP_V, [l, 1.0 - b, r, 1.0 - t]) };
    s.set(&q, if s.on(&q) { 0.0 } else { 1.0 });
    set_crop_box(s, c);
}

/// The largest centred box of aspect `ratio` (width / height) in a `fw` x `fh` frame.
fn aspect_box(ratio: f32, fw: f32, fh: f32) -> [f32; 4] {
    let (w, h) = if fw / fh > ratio { (fh * ratio / fw, 1.0) } else { (1.0, fw / ratio / fh) };
    [(1.0 - w) / 2.0, (1.0 - h) / 2.0, (1.0 + w) / 2.0, (1.0 + h) / 2.0]
}

fn crop(ui: &mut Ui, s: &mut Settings, source: Option<&Source>) {
    param(ui, s, &p::STRAIGHTEN);
    ui.add_space(2.0);
    ui.horizontal_wrapped(|ui| {
        if outline_button(ui, "⟲ Left").clicked() {
            turn(s, false);
        }
        if outline_button(ui, "⟳ Right").clicked() {
            turn(s, true);
        }
        if outline_button(ui, "Flip ↔").clicked() {
            flip(s, true);
        }
        if outline_button(ui, "Flip ↕").clicked() {
            flip(s, false);
        }
    });
    if let Some(src) = source {
        sub(ui, "Aspect");
        let geom = src.geometry(s, true);
        let (fw, fh) = (geom.frame_w as f32, geom.frame_h as f32);
        let long = fw.max(fh) / fw.min(fh);
        let landscape = fw >= fh;
        ui.horizontal_wrapped(|ui| {
            if chip(ui, crop_box(s) == [0.0, 0.0, 1.0, 1.0], "Original").clicked() {
                set_crop_box(s, [0.0, 0.0, 1.0, 1.0]);
            }
            for (name, r) in [("1:1", 1.0), ("4:5", 5.0 / 4.0), ("5:7", 7.0 / 5.0), ("16:9", 16.0 / 9.0)] {
                if (r - long).abs() < 0.01 {
                    continue;
                }
                let ratio = if landscape { r } else { 1.0 / r };
                let b = aspect_box(ratio, fw, fh);
                let same = crop_box(s).iter().zip(b).all(|(a, b)| (a - b).abs() < 1e-3);
                if chip(ui, same, name).clicked() {
                    set_crop_box(s, b);
                }
            }
        });
    }
    sub(ui, "Edges");
    let [l, t, r, b] = crop_box(s);
    let edges = [("Left", l, 0.0, 0), ("Top", t, 0.0, 1), ("Right", r, 1.0, 2), ("Bottom", b, 1.0, 3)];
    for (name, v, def, i) in edges {
        let slider = DevelopSlider { label: name, text: format!("{:.1} %", v * 100.0), value: v, min: 0.0, max: 1.0, origin: def, default: def, swatch: None };
        if let Some(n) = slider.show(ui) {
            let mut c = crop_box(s);
            c[i] = n;
            // Keep at least a twentieth of the frame.
            if c[2] - c[0] >= 0.05 && c[3] - c[1] >= 0.05 {
                set_crop_box(s, c);
            }
        }
    }
}

fn masks(ui: &mut Ui, s: &mut Settings) {
    hint(ui, "Masks made on the phone render here. Subject and sky masks need the phone's finder, so they select nothing yet.");
    let mut remove = None;
    for (i, m) in s.masks.iter_mut().enumerate() {
        let kind = match m.kind {
            MaskKind::Linear => "Linear gradient",
            MaskKind::Radial => "Radial gradient",
            MaskKind::Subject => "Subject",
            MaskKind::Sky => "Sky",
        };
        ui.add_space(4.0);
        ui.horizontal(|ui| {
            ui.label(RichText::new(format!("{} {}", i + 1, kind)).color(palette(ui.ctx()).strong));
            crate::widgets::right_to_left(ui, |ui| {
                if ui.add(egui::Label::new(RichText::new("Remove").size(12.0).color(palette(ui.ctx()).text3)).sense(Sense::click())).clicked() {
                    remove = Some(i);
                }
            });
        });
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
    }
    if let Some(i) = remove {
        s.masks.remove(i);
    }
}

/// The point curve editor: the master curve or one channel's, drag points, click to add,
/// double-click a point to take it away.
fn curve(ui: &mut Ui, s: &mut Settings) {
    let pal = palette(ui.ctx());
    let id = ui.id().with("curve-channel");
    let mut ch: usize = ui.memory(|m| m.data.get_temp(id).unwrap_or(0));
    ui.horizontal(|ui| {
        for (i, name) in ["RGB", "Red", "Green", "Blue"].into_iter().enumerate() {
            if chip(ui, ch == i, name).clicked() {
                ch = i;
            }
        }
    });
    ui.memory_mut(|m| m.data.insert_temp(id, ch));
    let w = ui.available_width();
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(w, w), Sense::click_and_drag());
    let painter = ui.painter_at(rect);
    painter.rect_filled(rect, 0.0, pal.panel.gamma_multiply(0.6));
    painter.rect_stroke(rect, 0.0, Stroke::new(1.0_f32, pal.border));
    for k in 1..4 {
        let f = k as f32 / 4.0;
        painter.vline(rect.left() + f * w, rect.y_range(), Stroke::new(1.0_f32, pal.border.gamma_multiply(0.6)));
        painter.hline(rect.x_range(), rect.top() + f * w, Stroke::new(1.0_f32, pal.border.gamma_multiply(0.6)));
    }
    painter.line_segment([rect.left_bottom(), rect.right_top()], Stroke::new(1.0_f32, pal.border));
    let to_screen = |(x, y): (f32, f32)| Pos2::new(rect.left() + x * w, rect.bottom() - y * w);
    let from_screen = |p: Pos2| (((p.x - rect.left()) / w).clamp(0.0, 1.0), ((rect.bottom() - p.y) / w).clamp(0.0, 1.0));
    let ink = [pal.text, Color32::from_rgb(0xC8, 0x3A, 0x34), Color32::from_rgb(0x4E, 0x8C, 0x5A), Color32::from_rgb(0x3E, 0x6A, 0xB0)][ch];
    let mut c: Curve = s.curves[ch].clone();
    let drag_id = ui.id().with("curve-drag");
    let mut dragging: Option<usize> = ui.memory(|m| m.data.get_temp(drag_id));
    let near = |c: &Curve, at: Pos2| {
        c.points
            .iter()
            .enumerate()
            .map(|(i, &pt)| (i, to_screen(pt).distance(at)))
            .filter(|(_, d)| *d < 10.0)
            .min_by(|a, b| a.1.total_cmp(&b.1))
            .map(|(i, _)| i)
    };
    if let Some(at) = resp.interact_pointer_pos() {
        if resp.double_clicked() {
            if let Some(i) = near(&c, at) {
                if i != 0 && i != c.points.len() - 1 {
                    c.points.remove(i);
                }
            }
            dragging = None;
        } else if resp.drag_started() || (resp.is_pointer_button_down_on() && dragging.is_none()) {
            dragging = near(&c, at).or_else(|| {
                let (x, _) = from_screen(at);
                let y = c.table(1024)[(x * 1023.0).round() as usize];
                let i = c.points.iter().position(|pt| pt.0 > x).unwrap_or(c.points.len());
                c.points.insert(i, (x, y));
                Some(i)
            });
        }
        if let (Some(i), true) = (dragging, resp.dragged() || resp.is_pointer_button_down_on()) {
            let (mut x, y) = from_screen(at);
            let lo = if i == 0 { 0.0 } else { c.points[i - 1].0 + 0.01 };
            let hi = if i + 1 == c.points.len() { 1.0 } else { c.points[i + 1].0 - 0.01 };
            x = x.clamp(lo, hi.max(lo));
            c.points[i] = (x, y);
        }
    }
    if !resp.is_pointer_button_down_on() {
        dragging = None;
    }
    ui.memory_mut(|m| match dragging {
        Some(i) => m.data.insert_temp(drag_id, i),
        None => m.data.remove::<usize>(drag_id),
    });
    let table = c.table(256);
    let line: Vec<Pos2> = table.iter().enumerate().map(|(i, &y)| to_screen((i as f32 / 255.0, y))).collect();
    painter.add(egui::Shape::line(line, Stroke::new(1.5_f32, ink)));
    for &pt in &c.points {
        painter.rect_filled(Rect::from_center_size(to_screen(pt), Vec2::splat(7.0)), 1.0, ink);
    }
    s.curves[ch] = c;
    ui.add_space(6.0);
    for q in [p::CURVE_HIGHLIGHTS, p::CURVE_LIGHTS, p::CURVE_DARKS, p::CURVE_SHADOWS] {
        param(ui, s, &q);
    }
}

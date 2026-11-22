//! The date scrubber on the library grid's right edge, like the phone's fast scroller. It takes the
//! place of the plain scroll bar: a slim handle that shows up while the grid scrolls (or when the
//! pointer comes to the edge) and fades away again. Dragging it flies through the library pixel by
//! pixel, with the day you'd land on shown beside it; pressing the rail anywhere jumps there and
//! keeps dragging. While it's in use the rail marks where each year (or, within one year, each
//! month) starts.

use crate::theme::{display, palette};
use chrono::{Datelike, Duration, Local, NaiveDate};
use eframe::egui::{self, Color32, CursorIcon, FontId, Id, Pos2, Rect, Rounding, Sense, Stroke, Ui, Vec2};

/// The handle's height.
const HANDLE_H: f32 = 44.0;
/// How long the scrubber stays after the grid stops moving, in seconds.
const LINGER: f64 = 1.5;
/// The rail's width, which is also where the pointer brings it up.
const RAIL_W: f32 = 18.0;
/// Rail labels closer than this to the one above are left out.
const LABEL_GAP: f32 = 18.0;

#[derive(Clone, Copy, Default)]
struct Memo {
    offset: f32,
    moved_at: f64,
    /// While dragging: where on the handle it was taken, from its top.
    grab: Option<f32>,
}

/// Draws the scrubber over a scroll area showing `area` of content `content` points tall, scrolled
/// to `offset`. `days` holds each day heading's top in the content and its day, top to bottom.
/// Answers the offset to scroll to while the scrubber is being dragged.
pub fn show(ui: &Ui, id: Id, area: Rect, offset: f32, content: f32, days: &[(f32, NaiveDate)]) -> Option<f32> {
    let range = content - area.height();
    if range < 1.0 || area.height() < HANDLE_H * 2.0 {
        return None;
    }
    let ctx = ui.ctx();
    let p = palette(ctx);
    let now = ui.input(|i| i.time);
    let mut memo: Memo = ctx.data(|d| d.get_temp(id)).unwrap_or_default();
    if (offset - memo.offset).abs() > 0.5 {
        memo.offset = offset;
        memo.moved_at = now;
    }

    let rail = Rect::from_min_max(Pos2::new(area.right() - RAIL_W - 2.0, area.top() + 6.0), Pos2::new(area.right() - 2.0, area.bottom() - 6.0));
    let travel = rail.height() - HANDLE_H;
    let handle_at = |f: f32| Rect::from_min_size(Pos2::new(rail.left(), rail.top() + f * travel), Vec2::new(RAIL_W, HANDLE_H));
    let resp = ui.interact(rail, id, Sense::click_and_drag());

    let mut fraction = (offset / range).clamp(0.0, 1.0);
    let mut to = None;
    if resp.is_pointer_button_down_on() {
        if let Some(pointer) = resp.interact_pointer_pos() {
            let grab = *memo.grab.get_or_insert_with(|| {
                let origin = ui.input(|i| i.pointer.press_origin()).unwrap_or(pointer);
                let handle = handle_at(fraction);
                // On the handle it's taken where it was pressed; elsewhere on the rail it jumps under the pointer.
                if handle.contains(origin) {
                    origin.y - handle.top()
                } else {
                    HANDLE_H / 2.0
                }
            });
            fraction = ((pointer.y - grab - rail.top()) / travel).clamp(0.0, 1.0);
            let o = (fraction * range).round();
            if (o - offset).abs() >= 0.5 {
                to = Some(o);
            }
            memo.offset = o;
            memo.moved_at = now;
        }
    } else {
        memo.grab = None;
    }
    let dragging = memo.grab.is_some();
    let handle = handle_at(fraction);
    let hover = resp.hovered() || dragging;
    let on_handle = dragging || resp.hover_pos().is_some_and(|h| handle.contains(h));
    if dragging {
        ctx.set_cursor_icon(CursorIcon::Grabbing);
    } else if on_handle {
        ctx.set_cursor_icon(CursorIcon::Grab);
    }

    let since = now - memo.moved_at;
    let shown = hover || since < LINGER;
    let alpha = ctx.animate_bool_with_time(id.with("fade"), shown, if shown { 0.12 } else { 0.4 });
    if shown && !hover {
        ctx.request_repaint_after(std::time::Duration::from_secs_f64(LINGER - since));
    }
    ctx.data_mut(|d| d.insert_temp(id, memo));
    if alpha <= 0.0 {
        return to;
    }

    let fade = |c: Color32| c.gamma_multiply(alpha);
    let painter = ui.painter().with_clip_rect(area);
    let cx = rail.center().x;
    // Each point of the rail stands for the spot in the library that sits at the same height in view,
    // so the top of the rail is the first day and the bottom the last.
    let y_of = |top: f32| rail.top() + HANDLE_H / 2.0 + (top / content).clamp(0.0, 1.0) * travel;

    if hover {
        painter.vline(cx, rail.y_range(), Stroke::new(p.line.max(1.0), fade(p.border)));
        let years = days.first().zip(days.last()).is_some_and(|(a, b)| a.1.year() != b.1.year());
        let mut last: Option<(i32, f32)> = None;
        for &(top, day) in days {
            let key = if years { day.year() } else { day.month() as i32 };
            if last.is_some_and(|(k, _)| k == key) {
                continue;
            }
            let y = y_of(top);
            if last.is_some_and(|(_, ly)| y - ly < LABEL_GAP) {
                last = Some((key, last.unwrap().1));
                continue;
            }
            last = Some((key, y));
            let text = if years { day.year().to_string() } else { day.format("%b").to_string() };
            let galley = painter.layout_no_wrap(text, FontId::proportional(10.5), fade(p.text3));
            let r = Rect::from_min_size(Pos2::new(rail.left() - 6.0 - galley.size().x, y - galley.size().y / 2.0), galley.size()).expand2(Vec2::new(4.0, 1.0));
            painter.rect_filled(r, Rounding::same(p.radius.min(r.height() / 2.0)), fade(p.bg.gamma_multiply(0.9)));
            painter.galley(r.min + Vec2::new(4.0, 1.0), galley, fade(p.text3));
        }
    }

    let width = if hover { 8.0 } else { 5.0 };
    let pill = Rect::from_center_size(Pos2::new(cx, handle.center().y), Vec2::new(width, HANDLE_H));
    let fill = if dragging {
        p.accent
    } else if on_handle {
        p.text2
    } else {
        p.text3
    };
    painter.rect_filled(pill, Rounding::same(p.radius.min(width / 2.0)), fade(fill));

    // The day beside the handle while it's held or pointed at, or the day under the pointer on the rail.
    let label_at =
        if on_handle { Some(handle.center().y) } else { resp.hover_pos().map(|h| h.y.clamp(rail.top() + HANDLE_H / 2.0, rail.bottom() - HANDLE_H / 2.0)) };
    if let Some(y) = label_at {
        let f = (y - rail.top() - HANDLE_H / 2.0) / travel;
        let target = f * content;
        let i = days.partition_point(|d| d.0 <= target).saturating_sub(1);
        if let Some(&(_, day)) = days.get(i) {
            bubble(&painter, &p, Pos2::new(rail.left() - 8.0, y), &day_label(day, Local::now().date_naive()), alpha);
        }
    }
    to
}

/// A small card whose right edge sits at `right`, centred on its height.
fn bubble(painter: &egui::Painter, p: &crate::theme::Palette, right: Pos2, text: &str, alpha: f32) {
    let galley = painter.layout_no_wrap(text.to_owned(), FontId::new(13.0, display()), p.text.gamma_multiply(alpha));
    let size = galley.size() + Vec2::new(24.0, 12.0);
    let rect = Rect::from_min_size(Pos2::new(right.x - size.x, right.y - size.y / 2.0), size);
    let rounding = Rounding::same(p.radius.min(size.y / 2.0));
    if p.shadow > 0.0 {
        painter.rect_filled(rect.translate(Vec2::splat(p.shadow)), rounding, p.shadow_color.gamma_multiply(alpha));
    }
    painter.rect(rect, rounding, p.panel.gamma_multiply(alpha), Stroke::new(p.line, p.border.gamma_multiply(alpha)));
    painter.galley(rect.center() - galley.size() / 2.0, galley, p.text.gamma_multiply(alpha));
}

/// "Today", "Yesterday", "Sat, Nov 21", or with the year when it isn't this one.
pub fn day_label(day: NaiveDate, today: NaiveDate) -> String {
    if day == today {
        "Today".into()
    } else if day == today - Duration::days(1) {
        "Yesterday".into()
    } else if day.year() == today.year() {
        day.format("%a, %b %-d").to_string()
    } else {
        day.format("%a, %b %-d %Y").to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn labels_days_like_the_phone() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 22).unwrap();
        assert_eq!(day_label(today, today), "Today");
        assert_eq!(day_label(NaiveDate::from_ymd_opt(2026, 11, 21).unwrap(), today), "Yesterday");
        assert_eq!(day_label(NaiveDate::from_ymd_opt(2026, 3, 4).unwrap(), today), "Wed, Mar 4");
        assert_eq!(day_label(NaiveDate::from_ymd_opt(2025, 12, 31).unwrap(), today), "Wed, Dec 31 2025");
    }
}

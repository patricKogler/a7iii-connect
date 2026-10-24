//! Scrolling that feels like the rest of the desktop. egui moves a scroll area only while wheel
//! events arrive, so a touchpad flick stops dead when the fingers lift, where GTK and browsers
//! glide on and slow down. This adds that glide: it follows how fast the touchpad (pixel deltas)
//! was moving and, once its events stop, keeps sending a decaying stream of small deltas. Mouse
//! wheels (line deltas) get bigger steps and egui's own easing. Deltas are cut into small pieces,
//! which egui applies right away instead of easing them in after the fingers.

use eframe::egui::{self, Event, MouseWheelUnit, RawInput, Vec2};

/// Points per wheel notch, over egui's 40.
const LINE_GAIN: f32 = 2.5;
/// Touchpad deltas, a little faster than 1:1.
const POINT_GAIN: f32 = 1.4;
/// How long a glide takes to lose 63% of its speed, in seconds.
const DECAY: f32 = 0.33;
/// The touchpad counts as let go after this long without events.
const LET_GO: f64 = 0.045;
/// Slower than this (points per second), a glide stops.
const MIN_SPEED: f32 = 40.0;
/// egui eases in any wheel delta this long or longer; shorter ones apply at once.
const PIECE: f32 = 7.5;

#[derive(Default)]
pub struct Wheel {
    velocity: Vec2,
    /// When the last touchpad delta came, and when the last frame was.
    last: f64,
    frame: f64,
    gliding: bool,
}

impl Wheel {
    /// Rewrites this frame's wheel events and adds the glide. Answers whether to draw again soon.
    pub fn feed(&mut self, raw: &mut RawInput) -> Option<f64> {
        let now = raw.time.unwrap_or(self.frame);
        let dt = (now - self.frame).clamp(0.0, 1.0 / 30.0) as f32;
        self.frame = now;
        let mut touch = Vec2::ZERO;
        let mut events = Vec::with_capacity(raw.events.len());
        for e in raw.events.drain(..) {
            match e {
                Event::MouseWheel { unit: MouseWheelUnit::Point, delta, modifiers } => {
                    let d = delta * POINT_GAIN;
                    touch += d;
                    pieces(&mut events, d, modifiers);
                }
                Event::MouseWheel { unit: MouseWheelUnit::Line, delta, modifiers } => {
                    self.stop();
                    events.push(Event::MouseWheel { unit: MouseWheelUnit::Line, delta: delta * LINE_GAIN, modifiers });
                }
                Event::PointerButton { pressed: true, .. } => {
                    self.stop();
                    events.push(e);
                }
                e => events.push(e),
            }
        }
        raw.events = events;

        if touch != Vec2::ZERO {
            let since = (now - self.last) as f32;
            let instant = touch / since.clamp(1.0 / 250.0, 1.0 / 30.0);
            // A new swipe starts from its own speed; one in progress is smoothed.
            self.velocity = if self.gliding || since > 0.1 { instant } else { self.velocity * 0.6 + instant * 0.4 };
            self.last = now;
            self.gliding = false;
            return Some(LET_GO);
        }
        if self.velocity == Vec2::ZERO {
            return None;
        }
        if now - self.last < LET_GO {
            return Some(LET_GO - (now - self.last));
        }
        if !self.gliding {
            self.gliding = self.velocity.length() > MIN_SPEED * 4.0;
            if !self.gliding {
                self.stop();
                return None;
            }
        }
        pieces(&mut raw.events, self.velocity * dt, egui::Modifiers::default());
        self.velocity *= (-dt / DECAY).exp();
        if self.velocity.length() < MIN_SPEED {
            self.stop();
            return None;
        }
        Some(0.0)
    }

    fn stop(&mut self) {
        self.velocity = Vec2::ZERO;
        self.gliding = false;
    }
}

/// A delta as small wheel events, which egui applies without easing.
fn pieces(out: &mut Vec<Event>, delta: Vec2, modifiers: egui::Modifiers) {
    let n = (delta.length() / PIECE).ceil().max(1.0);
    for _ in 0..n as usize {
        out.push(Event::MouseWheel { unit: MouseWheelUnit::Point, delta: delta / n, modifiers });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(w: &mut Wheel, t: f64, events: Vec<Event>) -> (Vec2, Option<f64>) {
        let mut raw = RawInput { time: Some(t), events, ..Default::default() };
        let again = w.feed(&mut raw);
        let total = raw
            .events
            .iter()
            .map(|e| match e {
                Event::MouseWheel { delta, unit: MouseWheelUnit::Point, .. } => {
                    assert!(delta.length() <= PIECE + 1e-3);
                    *delta
                }
                Event::MouseWheel { delta, .. } => *delta * 40.0,
                _ => Vec2::ZERO,
            })
            .fold(Vec2::ZERO, |a, b| a + b);
        (total, again)
    }

    fn pad(y: f32) -> Event {
        Event::MouseWheel { unit: MouseWheelUnit::Point, delta: Vec2::new(0.0, y), modifiers: Default::default() }
    }

    #[test]
    fn a_flick_glides_on_and_slows_down() {
        let mut w = Wheel::default();
        let mut t = 0.0;
        for _ in 0..10 {
            t += 1.0 / 60.0;
            let (moved, again) = frame(&mut w, t, vec![pad(-20.0)]);
            assert!((moved.y + 28.0).abs() < 0.01);
            assert!(again.is_some());
        }
        // Fingers lift: nothing for a moment, then the glide.
        let mut steps = Vec::new();
        for _ in 0..200 {
            t += 1.0 / 60.0;
            let (moved, again) = frame(&mut w, t, vec![]);
            if moved.y != 0.0 {
                steps.push(-moved.y);
            }
            if again.is_none() {
                break;
            }
        }
        assert!(steps.len() > 10, "{steps:?}");
        assert!(steps.windows(2).all(|p| p[1] <= p[0] + 1e-3), "slows down: {steps:?}");
        let glide: f32 = steps.iter().sum();
        assert!(glide > 300.0 && glide < 1200.0, "{glide}");
    }

    #[test]
    fn a_click_or_a_mouse_wheel_stops_the_glide() {
        let mut w = Wheel::default();
        let mut t = 0.0;
        for _ in 0..5 {
            t += 1.0 / 60.0;
            frame(&mut w, t, vec![pad(-30.0)]);
        }
        t += 0.1;
        assert!(frame(&mut w, t, vec![]).0.y != 0.0);
        let line = Event::MouseWheel { unit: MouseWheelUnit::Line, delta: Vec2::new(0.0, 1.0), modifiers: Default::default() };
        t += 1.0 / 60.0;
        let (moved, again) = frame(&mut w, t, vec![line]);
        assert_eq!(moved.y, 40.0 * LINE_GAIN);
        assert!(again.is_none());
    }

    #[test]
    fn a_slow_drag_does_not_glide() {
        let mut w = Wheel::default();
        let mut t = 0.0;
        for _ in 0..5 {
            t += 1.0 / 60.0;
            frame(&mut w, t, vec![pad(-0.5)]);
        }
        t += 0.1;
        assert_eq!(frame(&mut w, t, vec![]), (Vec2::ZERO, None));
    }
}

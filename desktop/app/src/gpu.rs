//! The develop shader on the GPU (the phone's GpuPreview and GpuRender): the photo's
//! geometry-corrected camera pixels go up as a 16-bit float texture with the look's lookup tables,
//! and `develop.wgsl` runs the per-pixel pipeline into an 8-bit sRGB target. The live preview
//! draws into a texture egui shows; exports draw in tiles and read the pixels back.

use eframe::wgpu::{self, util::DeviceExt};
use half::f16;
use shootback_core::develop::geometry::Geometry;
use shootback_core::develop::image::LinearImage;
use shootback_core::develop::look::{Guide, ShaderParams, MASK_MAX, MASK_STRIDE};
use shootback_core::develop::lut::Lut;
use shootback_core::develop::maps::MaskImage;
use std::sync::Arc;

pub const SHADER: &str = include_str!("develop.wgsl");

/// Frame pixels per node of the coords texture (see [`coords_data`]).
pub const COORD_STEP: usize = 8;

/// What the shader draws into: 8-bit sRGB, which the GPU encodes on write (the shader hands it
/// linear values) and egui decodes on sampling; read back, the bytes are display sRGB.
const TARGET: wgpu::TextureFormat = wgpu::TextureFormat::Rgba8UnormSrgb;

/// Floats in the uniform block (71 vec4s, the order of `struct U`).
const UNIFORM_FLOATS: usize = 71 * 4;

/// The drawn area and where it sits in the whole frame: a tile of a bigger render is `width` x
/// `height` at (`origin_x`, `origin_y`) of a `frame_w` x `frame_h` frame, and its scene has
/// `pad_x` / `pad_y` pixels of margin around it for the neighbour taps.
#[derive(Clone, Copy, Debug)]
pub struct Placement {
    pub width: usize,
    pub height: usize,
    pub origin_x: usize,
    pub origin_y: usize,
    pub frame_w: usize,
    pub frame_h: usize,
    pub pad_x: usize,
    pub pad_y: usize,
}

impl Placement {
    pub fn whole(width: usize, height: usize) -> Placement {
        Placement { width, height, origin_x: 0, origin_y: 0, frame_w: width, frame_h: height, pad_x: 0, pad_y: 0 }
    }
}

/// A texture with its size.
pub struct Tex {
    pub texture: wgpu::Texture,
    pub view: wgpu::TextureView,
    pub width: usize,
    pub height: usize,
}

/// Everything one draw reads.
pub struct Inputs<'a> {
    pub scene: &'a Tex,
    pub tone: &'a Tex,
    pub curves: Option<&'a Tex>,
    pub guide: Option<(&'a Tex, [f32; 3])>,
    pub coords: Option<&'a Tex>,
    pub lut: Option<&'a Tex>,
    /// The subject (red), sky (green) and nearness (blue) maps, and which of them are found.
    pub found: Option<(&'a Tex, [bool; 3])>,
    /// Masks' own maps, see [`maps_data`].
    pub maps: Option<&'a Tex>,
    /// The mask to tint on top (its index).
    pub overlay: Option<usize>,
    pub params: &'a ShaderParams,
    pub place: Placement,
}

pub struct Engine {
    pub device: Arc<wgpu::Device>,
    pub queue: Arc<wgpu::Queue>,
    pipeline: wgpu::RenderPipeline,
    layout: wgpu::BindGroupLayout,
    sampler: wgpu::Sampler,
    dummy: Tex,
}

impl Engine {
    pub fn new(device: Arc<wgpu::Device>, queue: Arc<wgpu::Queue>) -> Engine {
        let module = device.create_shader_module(wgpu::ShaderModuleDescriptor { label: Some("develop"), source: wgpu::ShaderSource::Wgsl(SHADER.into()) });
        let tex_entry = |binding: u32| wgpu::BindGroupLayoutEntry {
            binding,
            visibility: wgpu::ShaderStages::FRAGMENT,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: true },
                view_dimension: wgpu::TextureViewDimension::D2,
                multisampled: false,
            },
            count: None,
        };
        let mut entries = vec![
            wgpu::BindGroupLayoutEntry {
                binding: 0,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Buffer { ty: wgpu::BufferBindingType::Uniform, has_dynamic_offset: false, min_binding_size: None },
                count: None,
            },
            wgpu::BindGroupLayoutEntry {
                binding: 1,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                count: None,
            },
        ];
        entries.extend((2..=9).map(tex_entry));
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor { label: Some("develop"), entries: &entries });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("develop"),
            bind_group_layouts: &[&layout],
            push_constant_ranges: &[],
        });
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("develop"),
            layout: Some(&pipeline_layout),
            vertex: wgpu::VertexState { module: &module, entry_point: "vs", compilation_options: Default::default(), buffers: &[] },
            fragment: Some(wgpu::FragmentState {
                module: &module,
                entry_point: "fs",
                compilation_options: Default::default(),
                targets: &[Some(wgpu::ColorTargetState { format: TARGET, blend: None, write_mask: wgpu::ColorWrites::ALL })],
            }),
            primitive: wgpu::PrimitiveState::default(),
            depth_stencil: None,
            multisample: wgpu::MultisampleState::default(),
            multiview: None,
            cache: None,
        });
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("develop"),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        let dummy = upload(&device, &queue, 1, 1, &[0.0, 0.0, 0.0, 1.0]);
        Engine { device, queue, pipeline, layout, sampler, dummy }
    }

    /// A 16-bit float RGBA texture from `rgba` (4 floats per pixel, rows top to bottom).
    pub fn texture(&self, width: usize, height: usize, rgba: &[f32]) -> Tex {
        upload(&self.device, &self.queue, width, height, rgba)
    }

    /// A render target, which egui can also show.
    pub fn target(&self, width: usize, height: usize) -> Tex {
        let texture = self.device.create_texture(&wgpu::TextureDescriptor {
            label: Some("develop-target"),
            size: wgpu::Extent3d { width: width as u32, height: height as u32, depth_or_array_layers: 1 },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: TARGET,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_SRC,
            view_formats: &[],
        });
        let view = texture.create_view(&Default::default());
        Tex { texture, view, width, height }
    }

    /// Draws one frame into `target` (its top-left `place.width` x `place.height`).
    pub fn draw<'a>(&'a self, inputs: &Inputs<'a>, target: &Tex) {
        let u = uniforms(inputs);
        let buffer = self.device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("develop-uniforms"),
            contents: bytemuck::cast_slice(&u),
            usage: wgpu::BufferUsages::UNIFORM,
        });
        let dummy = &self.dummy;
        let view = |t: Option<&'a Tex>| &t.unwrap_or(dummy).view;
        let views = [
            &inputs.scene.view,
            &inputs.tone.view,
            view(inputs.curves),
            view(inputs.guide.map(|g| g.0)),
            view(inputs.coords),
            view(inputs.found.map(|f| f.0)),
            view(inputs.lut),
            view(inputs.maps),
        ];
        let mut entries = vec![
            wgpu::BindGroupEntry { binding: 0, resource: buffer.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::Sampler(&self.sampler) },
        ];
        entries.extend(views.iter().enumerate().map(|(i, v)| wgpu::BindGroupEntry { binding: i as u32 + 2, resource: wgpu::BindingResource::TextureView(v) }));
        let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor { label: Some("develop"), layout: &self.layout, entries: &entries });
        let mut encoder = self.device.create_command_encoder(&Default::default());
        {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("develop"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &target.view,
                    resolve_target: None,
                    ops: wgpu::Operations { load: wgpu::LoadOp::Clear(wgpu::Color::BLACK), store: wgpu::StoreOp::Store },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &group, &[]);
            pass.set_viewport(0.0, 0.0, inputs.place.width as f32, inputs.place.height as f32, 0.0, 1.0);
            pass.draw(0..3, 0..1);
        }
        self.queue.submit([encoder.finish()]);
    }

    /// The top-left `width` x `height` of `target` as RGBA bytes (waits for the GPU).
    pub fn read(&self, target: &Tex, width: usize, height: usize) -> Vec<u8> {
        let row = (width * 4).div_ceil(wgpu::COPY_BYTES_PER_ROW_ALIGNMENT as usize) * wgpu::COPY_BYTES_PER_ROW_ALIGNMENT as usize;
        let buffer = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("develop-read"),
            size: (row * height) as u64,
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            mapped_at_creation: false,
        });
        let mut encoder = self.device.create_command_encoder(&Default::default());
        encoder.copy_texture_to_buffer(
            target.texture.as_image_copy(),
            wgpu::ImageCopyBuffer { buffer: &buffer, layout: wgpu::ImageDataLayout { offset: 0, bytes_per_row: Some(row as u32), rows_per_image: None } },
            wgpu::Extent3d { width: width as u32, height: height as u32, depth_or_array_layers: 1 },
        );
        self.queue.submit([encoder.finish()]);
        let slice = buffer.slice(..);
        slice.map_async(wgpu::MapMode::Read, |_| {});
        self.device.poll(wgpu::Maintain::Wait);
        let data = slice.get_mapped_range();
        let mut out = Vec::with_capacity(width * height * 4);
        for y in 0..height {
            out.extend_from_slice(&data[y * row..y * row + width * 4]);
        }
        drop(data);
        buffer.unmap();
        out
    }
}

fn upload(device: &wgpu::Device, queue: &wgpu::Queue, width: usize, height: usize, rgba: &[f32]) -> Tex {
    let size = wgpu::Extent3d { width: width as u32, height: height as u32, depth_or_array_layers: 1 };
    let texture = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("develop-input"),
        size,
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba16Float,
        usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
        view_formats: &[],
    });
    let half: Vec<u16> = rgba.iter().map(|v| f16::from_f32(*v).to_bits()).collect();
    queue.write_texture(
        texture.as_image_copy(),
        bytemuck::cast_slice(&half),
        wgpu::ImageDataLayout { offset: 0, bytes_per_row: Some(width as u32 * 8), rows_per_image: None },
        size,
    );
    let view = texture.create_view(&Default::default());
    Tex { texture, view, width, height }
}

/// The uniform block, in the order of the shader's `struct U`.
fn uniforms(i: &Inputs) -> Vec<f32> {
    let p = i.params;
    let at = &i.place;
    let flag = |b: bool| if b { 1.0 } else { 0.0 };
    let mut u = Vec::with_capacity(UNIFORM_FLOATS);
    let mut v4 = |v: [f32; 4]| u.extend(v);
    let (gw, gh) = i.guide.map_or((1, 1), |g| (g.0.width, g.0.height));
    v4([at.frame_w as f32, at.frame_h as f32, at.origin_x as f32, at.origin_y as f32]);
    v4([at.pad_x as f32, at.pad_y as f32, gw as f32 / at.frame_w as f32, gh as f32 / at.frame_h as f32]);
    let m = p.matrix;
    v4([m[0], m[1], m[2], 0.0]);
    v4([m[3], m[4], m[5], 0.0]);
    v4([m[6], m[7], m[8], 0.0]);
    v4([flag(i.curves.is_some()), flag(i.guide.is_some()), flag(p.mono), flag(p.has_mixer)]);
    v4([p.ev, p.shadows, p.highlights, p.clarity]);
    v4([p.dehaze, p.texture, p.texture_radius, p.sat]);
    let air = i.guide.map_or([0.0; 3], |g| g.1);
    v4([air[0], air[1], air[2], p.vib]);
    let c = p.band_centers;
    let prev: [f32; 8] = std::array::from_fn(|k| (c[k] - c[(k + 7) % 8] + 360.0) % 360.0);
    let next: [f32; 8] = std::array::from_fn(|k| (c[(k + 1) % 8] - c[k] + 360.0) % 360.0);
    for a in [p.mix_hue, p.mix_sat, p.mix_lum, c, prev, next] {
        u.extend(a);
    }
    let masks = match (i.coords.is_some(), i.overlay) {
        (false, _) => 0,
        (true, Some(k)) if k < p.mask_total => p.mask_total,
        (true, _) => p.mask_count,
    };
    u.extend([flag(p.has_grading), p.blending, p.balance, masks as f32]);
    u.extend(p.grade_offset);
    u.extend(p.grade_lum);
    u.extend([p.vignette, p.vig_mid, p.vig_feather, p.vig_round]);
    u.extend([p.vig_highlights, p.grain, p.grain_cell, p.grain_rough]);
    u.extend([p.sharpen, p.sharpen_radius, p.sharpen_limit, p.sharpen_masking]);
    let found = i.found.map_or([false; 3], |f| f.1);
    u.extend([p.scale, flag(found[0] && p.uses_subject), flag(found[1] && p.uses_sky), if i.lut.is_some() { p.lut_amount } else { 0.0 }]);
    let step = 1.0 / COORD_STEP as f32;
    u.extend([p.source_size[0], p.source_size[1], step, step]);
    let (fw, fh) = i.found.map_or((1, 1), |f| (f.0.width, f.0.height));
    u.extend([fw as f32, fh as f32, i.lut.map_or(2, |l| l.height) as f32, 0.0]);
    u.extend(p.masks);
    let overlay = i.overlay.filter(|&k| k < masks).map_or(0.0, |k| k as f32 + 1.0);
    u.extend([flag(found[2] && p.uses_depth), overlay, 0.0, 0.0]);
    u.resize(UNIFORM_FLOATS, 0.0);
    debug_assert_eq!(MASK_MAX * MASK_STRIDE, 152);
    u
}

/// Columns [x0, x0 + w) of geometry-corrected camera pixels, as RGBA.
pub fn scene_data(img: &LinearImage, x0: usize, w: usize) -> Vec<f32> {
    let mut out = Vec::with_capacity(w * img.height * 4);
    for y in 0..img.height {
        for x in x0..x0 + w {
            let i = y * img.width + x;
            out.extend([img.r[i], img.g[i], img.b[i], 1.0]);
        }
    }
    out
}

/// The tone table as a (n x 1) texture.
pub fn tone_data(tone: &[f32]) -> Vec<f32> {
    tone.iter().flat_map(|&v| [v, v, v, 1.0]).collect()
}

/// The red, green and blue point curves as rows of a (n x 3) texture.
pub fn curves_data(curves: &[Vec<f32>; 3]) -> Vec<f32> {
    curves.iter().flat_map(|c| c.iter().flat_map(|&v| [v, v, v, 1.0])).collect()
}

/// The guide's maps in one texture: base and medium log-luminance (stored as (v + 20) / 40, to stay
/// positive) and the haze map relative to the airlight.
pub fn guide_data(g: &Guide) -> Vec<f32> {
    let amax = g.air[0].max(g.air[1]).max(g.air[2]);
    (0..g.width * g.height).flat_map(|i| [(g.base[i] + 20.0) / 40.0, (g.medium[i] + 20.0) / 40.0, g.dark[i] / amax / 2.0, 1.0]).collect()
}

/// Where each part of a `w` x `h` area at (`ox`, `oy`) of the frame (at `scale` frame pixels per
/// full-size pixel) comes from in the upright photo, as fractions of its size: one node every
/// [`COORD_STEP`] pixels, which the shader interpolates. Masks are placed by these positions.
pub fn coords_data(geom: &Geometry, scale: f64, w: usize, h: usize, ox: usize, oy: usize) -> (usize, usize, Vec<f32>) {
    let (nx, ny) = (w / COORD_STEP + 2, h / COORD_STEP + 2);
    let mut out = Vec::with_capacity(nx * ny * 4);
    for j in 0..ny {
        for i in 0..nx {
            let m = geom.map((ox + i * COORD_STEP) as f64 / scale, (oy + j * COORD_STEP) as f64 / scale);
            out.extend([(m[2] / geom.src_w) as f32, (m[3] / geom.src_h) as f32, 0.0, 1.0]);
        }
    }
    (nx, ny, out)
}

/// The photo's found maps in one texture: subject in red, sky in green, nearness in blue, each
/// resized to the first one's size. None when none is found.
pub fn found_data(maps: [Option<&MaskImage>; 3]) -> Option<(usize, usize, Vec<f32>, [bool; 3])> {
    let first = maps.iter().flatten().next()?;
    let (w, h) = (first.width, first.height);
    let planes: Vec<Option<Vec<f32>>> = maps.iter().map(|m| m.map(|m| fit(m, w, h))).collect();
    let mut out = vec![0.0; w * h * 4];
    for (c, p) in planes.iter().enumerate() {
        if let Some(p) = p {
            for i in 0..w * h {
                out[i * 4 + c] = p[i];
            }
        }
    }
    for i in 0..w * h {
        out[i * 4 + 3] = 1.0;
    }
    Some((w, h, out, [planes[0].is_some(), planes[1].is_some(), planes[2].is_some()]))
}

/// Masks' own maps by slot (see `map_slots`), packed two rows of four channels: a texture twice
/// the first map's height.
pub fn maps_data(slots: &[Option<&MaskImage>]) -> Option<(usize, usize, Vec<f32>)> {
    let first = slots.iter().flatten().next()?;
    let (w, h) = (first.width, first.height);
    let mut out = vec![0.0; w * h * 2 * 4];
    for (k, m) in slots.iter().enumerate().take(8) {
        let Some(m) = m else { continue };
        let p = fit(m, w, h);
        let (half, ch) = (k / 4, k % 4);
        for i in 0..w * h {
            out[(half * w * h + i) * 4 + ch] = p[i];
        }
    }
    Some((w, 2 * h, out))
}

fn fit(m: &MaskImage, w: usize, h: usize) -> Vec<f32> {
    if m.width == w && m.height == h {
        m.data.clone()
    } else {
        shootback_core::ai::tensor::resize_map(&m.data, m.width, m.height, w, h)
    }
}

/// A LUT as one texture: its blue slices side by side, red across each and green down, so the
/// shader blends two bilinear taps (the slices either side) into a trilinear one.
pub fn lut_data(lut: &Lut) -> (usize, usize, Vec<f32>) {
    let n = lut.size;
    let mut out = Vec::with_capacity(n * n * n * 4);
    for y in 0..n {
        for x in 0..n * n {
            let i = (((x / n) * n + y) * n + x % n) * 3;
            out.extend([lut.data[i], lut.data[i + 1], lut.data[i + 2], 1.0]);
        }
    }
    (n * n, n, out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shader_compiles() {
        let module = naga::front::wgsl::parse_str(SHADER).unwrap_or_else(|e| panic!("{}", e.emit_to_string(SHADER)));
        naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::empty())
            .validate(&module)
            .unwrap_or_else(|e| panic!("{}", e.emit_to_string(SHADER)));
        let u = module.types.iter().find(|(_, t)| t.name.as_deref() == Some("U")).expect("struct U");
        match &u.1.inner {
            naga::TypeInner::Struct { span, .. } => assert_eq!(*span as usize, UNIFORM_FLOATS * 4),
            _ => panic!("U is not a struct"),
        }
    }

    /// A device to test on (a software one such as lavapipe will do); None on machines without.
    fn engine() -> Option<Engine> {
        let instance = wgpu::Instance::default();
        let adapter = pollster::block_on(instance.request_adapter(&Default::default()))?;
        let (device, queue) = pollster::block_on(adapter.request_device(&Default::default(), None)).ok()?;
        Some(Engine::new(Arc::new(device), Arc::new(queue)))
    }

    #[test]
    fn an_unedited_jpeg_comes_out_as_it_went_in() {
        use shootback_core::develop::color::CameraColor;
        use shootback_core::develop::look::{Look, Source};
        use shootback_core::develop::Settings;
        let Some(engine) = engine() else {
            eprintln!("no GPU adapter; skipped");
            return;
        };
        let (w, h) = (64, 48);
        let srgb: Vec<u8> = (0..w * h).flat_map(|i| [(i % w * 4) as u8, (i / w * 5) as u8, 128]).collect();
        let image = LinearImage::from_srgb8(&srgb, w, h);
        let source = Source { image, color: CameraColor::srgb(), baked: [1.0; 3], is_raw: false, full_width: w, full_height: h, lens: None };
        let look = Look::new(&source, &Settings::default());
        let params = look.shader_params(1.0);
        let scene = engine.texture(w, h, &scene_data(&source.image, 0, w));
        let tone = engine.texture(params.tone.len(), 1, &tone_data(&params.tone));
        let target = engine.target(w, h);
        let inputs = Inputs {
            scene: &scene,
            tone: &tone,
            curves: None,
            guide: None,
            coords: None,
            lut: None,
            found: None,
            maps: None,
            overlay: None,
            params: &params,
            place: Placement::whole(w, h),
        };
        engine.draw(&inputs, &target);
        let out = engine.read(&target, w, h);
        let worst = (0..w * h).flat_map(|i| (0..3).map(move |c| (i, c))).map(|(i, c)| (out[i * 4 + c] as i32 - srgb[i * 3 + c] as i32).abs()).max().unwrap();
        assert!(worst <= 2, "off by {worst}");
    }
}

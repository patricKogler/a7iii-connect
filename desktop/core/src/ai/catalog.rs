//! The models Shootback can download, where they come from and under what licence. All of them
//! are open: their weights may be used and passed on, commercially too.

/// How a downloaded file becomes a model's files.
pub enum Packaging {
    /// Used as it is, under this name.
    File(&'static str),
    /// gzip, unpacked to this name.
    Gzip(&'static str),
    /// A zip archive: (path inside, name) for each file taken out.
    Zip(&'static [(&'static str, &'static str)]),
    /// A .tar.gz, likewise.
    Tgz(&'static [(&'static str, &'static str)]),
}

pub struct Source {
    pub url: &'static str,
    /// About how big the download is, for the progress bar.
    pub bytes: u64,
    pub packaging: Packaging,
}

pub struct Model {
    pub id: &'static str,
    pub label: &'static str,
    /// What it's for, in a line.
    pub about: &'static str,
    pub licence: &'static str,
    /// Where the weights come from (who trained them).
    pub origin: &'static str,
    pub sources: &'static [Source],
    /// The files it ends up as, in the model's folder.
    pub files: &'static [&'static str],
}

impl Model {
    pub fn bytes(&self) -> u64 {
        self.sources.iter().map(|s| s.bytes).sum()
    }
}

/// ONNX Runtime itself: the library that runs every model.
#[cfg(not(windows))]
pub static RUNTIME: Model = Model {
    id: "onnxruntime",
    label: "ONNX Runtime",
    about: "Runs the models, on the processor",
    licence: "MIT",
    origin: "Microsoft",
    sources: &[Source {
        url: "https://github.com/microsoft/onnxruntime/releases/download/v1.22.0/onnxruntime-linux-x64-1.22.0.tgz",
        bytes: 7_798_730,
        packaging: Packaging::Tgz(&[("onnxruntime-linux-x64-1.22.0/lib/libonnxruntime.so.1.22.0", "libonnxruntime.so")]),
    }],
    files: &["libonnxruntime.so"],
};

#[cfg(windows)]
pub static RUNTIME: Model = Model {
    id: "onnxruntime",
    label: "ONNX Runtime",
    about: "Runs the models, on the processor",
    licence: "MIT",
    origin: "Microsoft",
    sources: &[Source {
        url: "https://github.com/microsoft/onnxruntime/releases/download/v1.22.0/onnxruntime-win-x64-1.22.0.zip",
        bytes: 72_368_545,
        packaging: Packaging::Zip(&[
            ("onnxruntime-win-x64-1.22.0/lib/onnxruntime.dll", "onnxruntime.dll"),
            ("onnxruntime-win-x64-1.22.0/lib/onnxruntime_providers_shared.dll", "onnxruntime_providers_shared.dll"),
        ]),
    }],
    files: &["onnxruntime.dll"],
};

/// Subject masks: the main subject of a photo, whatever it is.
pub static SUBJECT: Model = Model {
    id: "subject-birefnet-lite",
    label: "Subject finder",
    about: "Finds the main subject for Subject masks",
    licence: "MIT",
    origin: "BiRefNet lite (Zheng et al., Nankai University)",
    sources: &[Source {
        url: "https://github.com/danielgatis/rembg/releases/download/v0.0.0/BiRefNet-general-bb_swin_v1_tiny-epoch_232.onnx",
        bytes: 224_005_088,
        packaging: Packaging::File("model.onnx"),
    }],
    files: &["model.onnx"],
};

/// Object masks: click on anything to select it.
pub static OBJECTS: Model = Model {
    id: "objects-sam21-tiny",
    label: "Object selection",
    about: "Selects what you click for Object masks",
    licence: "Apache-2.0",
    origin: "Segment Anything 2.1 Tiny (Meta)",
    sources: &[Source {
        url: "https://github.com/darktable-org/darktable-ai/releases/download/release-5.6.0/mask-object-sam21-tiny.dtmodel",
        bytes: 143_293_697,
        packaging: Packaging::Zip(&[("mask-object-sam21-tiny/encoder.onnx", "encoder.onnx"), ("mask-object-sam21-tiny/decoder.onnx", "decoder.onnx")]),
    }],
    files: &["encoder.onnx", "decoder.onnx"],
};

/// Depth masks: how far away each part of the photo is.
pub static DEPTH: Model = Model {
    id: "depth-anything-v2-small",
    label: "Depth",
    about: "Estimates distance for Depth masks",
    licence: "Apache-2.0",
    origin: "Depth Anything V2 Small (TikTok / HKU)",
    sources: &[Source {
        url: "https://github.com/fabio-sim/Depth-Anything-ONNX/releases/download/v2.0.0/depth_anything_v2_vits.onnx",
        bytes: 99_373_606,
        packaging: Packaging::File("model.onnx"),
    }],
    files: &["model.onnx"],
};

/// Raw denoise: cleans the sensor data before demosaicing.
pub static RAW_DENOISE: Model = Model {
    id: "rawdenoise-nind",
    label: "Raw denoise",
    about: "Removes noise from raw files, and demosaics them",
    licence: "GPL-3.0",
    origin: "RawNIND UtNet2 (Brummer, UCLouvain)",
    sources: &[Source {
        url: "https://github.com/darktable-org/darktable-ai/releases/download/release-5.6.0/rawdenoise-nind.dtmodel",
        bytes: 57_700_134,
        packaging: Packaging::Zip(&[("rawdenoise-nind/model_bayer.onnx", "bayer.onnx"), ("rawdenoise-nind/model_linear.onnx", "linear.onnx")]),
    }],
    files: &["bayer.onnx", "linear.onnx"],
};

/// Upscaling, 2x and 4x.
pub static UPSCALE: Model = Model {
    id: "upscale-realplksr",
    label: "Upscale",
    about: "Doubles or quadruples the size on export",
    licence: "MIT",
    origin: "RealPLKSR (Lee et al., University of Seoul)",
    sources: &[Source {
        url: "https://github.com/darktable-org/darktable-ai/releases/download/release-5.6.0/upscale-realplksr.dtmodel",
        bytes: 54_935_127,
        packaging: Packaging::Zip(&[("upscale-realplksr/model_x2.onnx", "x2.onnx"), ("upscale-realplksr/model_x4.onnx", "x4.onnx")]),
    }],
    files: &["x2.onnx", "x4.onnx"],
};

/// Faces: finding them, and telling them apart the way the phone does (the same SFace model).
pub static FACES: Model = Model {
    id: "faces-yunet-sface",
    label: "Faces",
    about: "Finds faces and tells people apart, like the phone",
    licence: "MIT / Apache-2.0",
    origin: "YuNet and SFace (OpenCV Zoo)",
    sources: &[
        Source {
            url: "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx",
            bytes: 232_589,
            packaging: Packaging::File("yunet.onnx"),
        },
        Source {
            url: "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_recognition_sface/face_recognition_sface_2021dec_int8.onnx",
            bytes: 9_896_933,
            packaging: Packaging::File("sface.onnx"),
        },
    ],
    files: &["yunet.onnx", "sface.onnx"],
};

/// The Remove tool: filling in what's painted out from its surroundings.
pub static INPAINT: Model = Model {
    id: "inpaint-lama",
    label: "Remove",
    about: "Fills in what you paint out, from what's around it",
    licence: "Apache-2.0",
    origin: "LaMa (Samsung AI), via OpenCV Zoo",
    sources: &[Source {
        url: "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/inpainting_lama/inpainting_lama_2025jan.onnx",
        bytes: 92_591_623,
        packaging: Packaging::File("lama.onnx"),
    }],
    files: &["lama.onnx"],
};

/// Search in words: pictures and text in one space.
pub static CLIP: Model = Model {
    id: "clip-vit-b32",
    label: "Search",
    about: "Finds photos from a description",
    licence: "MIT",
    origin: "CLIP ViT-B/32 (OpenAI)",
    sources: &[
        Source {
            url: "https://clip-as-service.s3.us-east-2.amazonaws.com/models-436c69702d61732d53657276696365/onnx/ViT-B-32/visual.onnx",
            bytes: 351_520_836,
            packaging: Packaging::File("visual.onnx"),
        },
        Source {
            url: "https://clip-as-service.s3.us-east-2.amazonaws.com/models-436c69702d61732d53657276696365/onnx/ViT-B-32/textual.onnx",
            bytes: 254_120_156,
            packaging: Packaging::File("textual.onnx"),
        },
        Source {
            url: "https://raw.githubusercontent.com/openai/CLIP/main/clip/bpe_simple_vocab_16e6.txt.gz",
            bytes: 1_356_917,
            packaging: Packaging::Gzip("vocab.txt"),
        },
    ],
    files: &["visual.onnx", "textual.onnx", "vocab.txt"],
};

/// Everything the Models window lists, in its order.
pub static ALL: [&Model; 9] = [&SUBJECT, &OBJECTS, &DEPTH, &RAW_DENOISE, &INPAINT, &UPSCALE, &FACES, &CLIP, &RUNTIME];

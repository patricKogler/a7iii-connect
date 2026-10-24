//! The parts of Shootback that don't depend on a platform: talking to the camera (Wi-Fi and FTP),
//! reading photos' metadata and previews, and the library's marks and albums. Formats match the
//! Android app's, so the two can share a library.

pub mod ai;
pub mod albums;
pub mod camera;
pub mod cull;
pub mod develop;
pub mod ftp;
pub mod import;
pub mod library;
pub mod marks;
pub mod meta;
pub mod sync;
pub mod tiff;

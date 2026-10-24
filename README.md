# A7III Connect

An Android app that pulls photos from a Sony A7III to the phone over the camera's
"Send to Smartphone" Wi-Fi, without Imaging Edge Mobile.

The camera runs a UPnP/DLNA media server (normally `http://192.168.122.1:64321/dd.xml`);
the app joins the camera's Wi-Fi, browses its ContentDirectory and downloads the photos.

## Install

Every push to `main` is built by GitHub Actions and published as the `latest` release.
Open the release on the phone and install `a7iii-connect.apk`.

## Shootback for Windows and Linux

`desktop/` holds the desktop app, written in Rust with egui. It copies photos from the camera
the same three ways: over the camera's Wi-Fi (Send to Smartphone), through an FTP inbox the
camera uploads to as it shoots, or from a card or folder. The library, picks and rejects, and
albums are kept in the same formats as the phone app, ready for syncing with it.

Every push to `main` that touches `desktop/` is built for both systems and published as the
`desktop-latest` release. To build it yourself:

```sh
cd desktop
cargo run --release -p shootback
```

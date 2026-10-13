# A7III Connect

An Android app that pulls photos from a Sony A7III to the phone over the camera's
"Send to Smartphone" Wi-Fi, without Imaging Edge Mobile.

The camera runs a UPnP/DLNA media server (normally `http://192.168.122.1:64321/dd.xml`);
the app joins the camera's Wi-Fi, browses its ContentDirectory and downloads the photos.

## Install

Every push to `main` is built by GitHub Actions and published as the `latest` release.
Open the release on the phone and install `a7iii-connect.apk`.
